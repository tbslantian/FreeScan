/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

import Foundation
import SwiftUI
import UIKit
import Dispatch

enum ScanFilter: String, CaseIterable, Identifiable {
    case automatic = "自动"
    case original = "原图"
    case color = "彩色"
    case grayscale = "灰度"
    case blackAndWhite = "黑白"
    case brighten = "局部提亮"
    case enhance = "文档增强"
    case localGrayscale = "增强灰度"
    case localSoft = "柔和增强"

    var id: String { rawValue }
}

struct ScanPage: Identifiable {
    let id: UUID
    var original: UIImage
    var image: UIImage
    var filter: ScanFilter = .automatic
    var rotation: Int = 0
    var recognizedText: String?

    init(image: UIImage) {
        self.init(id: UUID(), original: image, image: image)
    }

    init(id: UUID, original: UIImage, image: UIImage, filter: ScanFilter = .automatic,
         rotation: Int = 0, recognizedText: String? = nil) {
        self.id = id
        self.original = original
        self.image = image
        self.filter = filter
        self.rotation = rotation
        self.recognizedText = recognizedText
    }
}

@MainActor
final class ScanLibrary: ObservableObject {
    @Published var pages: [ScanPage] = []
    @Published var recentExports: [URL] = []
    private let persistenceQueue = DispatchQueue(label: "org.freescan.draft-persistence", qos: .utility)

    init() {
        pages = ScanDraftPersistence.load()
    }

    func add(_ image: UIImage) {
        let filter = ScanFilter(rawValue: UserDefaults.standard.string(forKey: "defaultFilter") ?? "") ?? .automatic
        let upright = ImagePipeline.upright(image)
        pages.append(ScanPage(id: UUID(), original: upright, image: upright, filter: filter))
        persistDraft()
    }

    func add(contentsOf images: [UIImage]) {
        let filter = ScanFilter(rawValue: UserDefaults.standard.string(forKey: "defaultFilter") ?? "") ?? .automatic
        pages.append(contentsOf: images.map { image in
            let upright = ImagePipeline.upright(image)
            return ScanPage(id: UUID(), original: upright, image: upright, filter: filter)
        })
        persistDraft()
    }

    func update(_ id: UUID, _ change: (inout ScanPage) -> Void) {
        guard let index = pages.firstIndex(where: { $0.id == id }) else { return }
        change(&pages[index])
        persistDraft()
    }

    func remove(_ id: UUID) {
        pages.removeAll { $0.id == id }
        persistDraft()
    }

    func clear() {
        pages.removeAll()
        persistDraft()
    }

    private func persistDraft() {
        let snapshot = pages
        persistenceQueue.async {
            do {
                try ScanDraftPersistence.save(snapshot)
            } catch {
                NSLog("FreeScan draft persistence failed: %@", error.localizedDescription as NSString)
            }
        }
    }
}

/// Keep folder security scopes open while library rows are available to Files,
/// the editor, print UI and share sheet. Persist bookmarks rather than PDF copies.
@MainActor
final class ExternalPDFLibrary: ObservableObject {
    @Published private(set) var folders: [URL] = []
    @Published private(set) var pdfs: [URL] = []
    @Published var errorMessage: String?
    private let bookmarkKey = "externalPDFFolderBookmarks"
    private var scopedFolders = Set<URL>()

    init() {
        let bookmarks = UserDefaults.standard.array(forKey: bookmarkKey) as? [Data] ?? []
        for bookmark in bookmarks {
            do {
                var stale = false
                let url = try URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil,
                                  bookmarkDataIsStale: &stale)
                if url.startAccessingSecurityScopedResource() { scopedFolders.insert(url) }
                folders.append(url)
            } catch { errorMessage = "部分文件夹授权已失效，请重新选择。" }
        }
        persistBookmarks()
    }

    deinit {
        for url in scopedFolders { url.stopAccessingSecurityScopedResource() }
    }

    func addFolder(_ url: URL) throws {
        guard !folders.contains(where: { $0.standardizedFileURL == url.standardizedFileURL }) else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        do {
            _ = try url.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil)
            folders.append(url)
            if scoped { scopedFolders.insert(url) }
            persistBookmarks()
        } catch {
            if scoped { url.stopAccessingSecurityScopedResource() }
            throw error
        }
    }

    func removeFolder(_ url: URL) {
        folders.removeAll { $0 == url }
        if scopedFolders.remove(url) != nil { url.stopAccessingSecurityScopedResource() }
        pdfs.removeAll { $0.path.hasPrefix(url.path + "/") }
        persistBookmarks()
    }

    func refresh() async {
        let roots = folders
        let found = await Task.detached(priority: .utility) {
            var result = Set<URL>()
            var failed = false
            for root in roots {
                guard let enumerator = FileManager.default.enumerator(
                    at: root, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey],
                    options: [.skipsHiddenFiles, .skipsPackageDescendants], errorHandler: { _, _ in
                        failed = true
                        return true
                    }) else { failed = true; continue }
                for case let url as URL in enumerator {
                    if Task.isCancelled { break }
                    let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
                    guard values?.isSymbolicLink != true, values?.isRegularFile == true,
                          url.pathExtension.lowercased() == "pdf" else { continue }
                    result.insert(url.standardizedFileURL)
                }
            }
            return (Array(result), failed)
        }.value
        // A folder may have been removed while its enumeration was running.
        guard roots == folders, !Task.isCancelled else { return }
        pdfs = found.0
        if found.1 { errorMessage = "部分文件夹暂时无法读取，请检查文件提供商或重新授权。" }
    }

    private func persistBookmarks() {
        let bookmarks = folders.compactMap {
            try? $0.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil)
        }
        UserDefaults.standard.set(bookmarks, forKey: bookmarkKey)
    }
}

private enum ScanDraftPersistence {
    private struct Pointer: Codable {
        let generation: String
    }

    private struct Manifest: Codable {
        let version: Int
        let pages: [PageRecord]
    }

    private struct PageRecord: Codable {
        let id: UUID
        let originalFile: String
        let imageFile: String
        let filter: String
        let rotation: Int
        let recognizedText: String?
    }

    private static var root: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("FreeScan/Draft", isDirectory: true)
    }

    static func load() -> [ScanPage] {
        do {
            let pointerData = try Data(contentsOf: root.appendingPathComponent("active.json"))
            let pointer = try JSONDecoder().decode(Pointer.self, from: pointerData)
            let generationDirectory = root.appendingPathComponent(pointer.generation, isDirectory: true)
            let manifestData = try Data(contentsOf: generationDirectory.appendingPathComponent("manifest.json"))
            let manifest = try JSONDecoder().decode(Manifest.self, from: manifestData)
            guard manifest.version == 1 else { return [] }
            return manifest.pages.compactMap { record in
                let originalURL = generationDirectory.appendingPathComponent(record.originalFile)
                let imageURL = generationDirectory.appendingPathComponent(record.imageFile)
                guard let original = UIImage(contentsOfFile: originalURL.path),
                      let image = UIImage(contentsOfFile: imageURL.path) else { return nil }
                return ScanPage(
                    id: record.id,
                    original: original,
                    image: image,
                    filter: ScanFilter(rawValue: record.filter) ?? .automatic,
                    rotation: record.rotation,
                    recognizedText: record.recognizedText
                )
            }
        } catch {
            return []
        }
    }

    static func save(_ pages: [ScanPage]) throws {
        let manager = FileManager.default
        try manager.createDirectory(at: root, withIntermediateDirectories: true)

        let generation = "generation-\(UUID().uuidString)"
        let pendingDirectory = root.appendingPathComponent(".pending-\(generation)", isDirectory: true)
        try manager.createDirectory(at: pendingDirectory, withIntermediateDirectories: true)
        do {
            var records = [PageRecord]()
            for (index, page) in pages.enumerated() {
                let stem = "\(index)-\(page.id.uuidString)"
                let originalFile = "\(stem)-original.jpg"
                let imageFile = "\(stem)-image.jpg"
                guard let originalData = page.original.jpegData(compressionQuality: 0.94),
                      let imageData = page.image.jpegData(compressionQuality: 0.94) else {
                    throw NSError(domain: "FreeScan.Draft", code: 1,
                                  userInfo: [NSLocalizedDescriptionKey: "无法保存扫描草稿图片。"])
                }
                try originalData.write(to: pendingDirectory.appendingPathComponent(originalFile), options: .atomic)
                try imageData.write(to: pendingDirectory.appendingPathComponent(imageFile), options: .atomic)
                records.append(PageRecord(
                    id: page.id,
                    originalFile: originalFile,
                    imageFile: imageFile,
                    filter: page.filter.rawValue,
                    rotation: page.rotation,
                    recognizedText: page.recognizedText
                ))
            }

            let manifest = Manifest(version: 1, pages: records)
            let manifestData = try JSONEncoder().encode(manifest)
            try manifestData.write(to: pendingDirectory.appendingPathComponent("manifest.json"), options: .atomic)

            let generationDirectory = root.appendingPathComponent(generation, isDirectory: true)
            try manager.moveItem(at: pendingDirectory, to: generationDirectory)
            let pointerData = try JSONEncoder().encode(Pointer(generation: generation))
            try pointerData.write(to: root.appendingPathComponent("active.json"), options: .atomic)

            for item in try manager.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey]) {
                guard item.lastPathComponent != generation,
                      (try? item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true else { continue }
                try? manager.removeItem(at: item)
            }
        } catch {
            try? manager.removeItem(at: pendingDirectory)
            throw error
        }
    }
}

struct CertificateTemplate: Identifiable, Hashable {
    let id: String
    let title: String
    /// Width divided by height in portrait camera preview.
    let aspect: CGFloat

    static let all: [CertificateTemplate] = [
        .init(id: "single-card", title: "单卡证", aspect: 1.586),
        .init(id: "id-card", title: "身份证", aspect: 1.586),
        .init(id: "double-card", title: "卡证(双拼)", aspect: 3.172),
        .init(id: "household", title: "户口本", aspect: 1.28),
        .init(id: "household-double", title: "户口本(双拼)", aspect: 1.82),
        .init(id: "business-license", title: "营业执照", aspect: 0.707),
        .init(id: "passport", title: "护照", aspect: 1.42),
        .init(id: "driver-license", title: "驾驶证", aspect: 1.586),
        .init(id: "vehicle-license", title: "行驶证", aspect: 1.586),
    ]
}

