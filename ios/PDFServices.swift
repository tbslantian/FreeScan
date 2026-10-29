/*
 * Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import AVFoundation
import Foundation
import PDFKit
import Photos
import UIKit

enum PDFServices {
    private static let exportFolderBookmarkKey = "exportFolderBookmark"

    private static var documentsDirectory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    static func setExportDirectory(_ url: URL) throws {
        let hasScopedAccess = url.startAccessingSecurityScopedResource()
        defer { if hasScopedAccess { url.stopAccessingSecurityScopedResource() } }
        let bookmark = try url.bookmarkData(options: .minimalBookmark,
                                            includingResourceValuesForKeys: nil,
                                            relativeTo: nil)
        UserDefaults.standard.set(bookmark, forKey: exportFolderBookmarkKey)
        UserDefaults.standard.set(url.lastPathComponent, forKey: "exportFolderDisplayName")
    }

    static func clearExportDirectory() {
        UserDefaults.standard.removeObject(forKey: exportFolderBookmarkKey)
        UserDefaults.standard.removeObject(forKey: "exportFolderDisplayName")
    }

    static func images(from url: URL, maxDimension: CGFloat = 2200) throws -> [UIImage] {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let coordinator = NSFileCoordinator(filePresenter: nil)
        var coordinationError: NSError?
        var result: Result<[UIImage], Error>?
        coordinator.coordinate(readingItemAt: url, options: .withoutChanges, error: &coordinationError) { readable in
            result = Result { try renderImages(from: readable, maxDimension: maxDimension) }
        }
        if let coordinationError { throw coordinationError }
        guard let result else {
            throw NSError(domain: "FreeScan.PDF", code: 4,
                          userInfo: [NSLocalizedDescriptionKey: "文件提供商未能提供可读取的 PDF。"])
        }
        return try result.get()
    }

    private static func renderImages(from url: URL, maxDimension: CGFloat) throws -> [UIImage] {
        guard let document = PDFDocument(url: url) else {
            throw NSError(domain: "FreeScan.PDF", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "无法打开 PDF。文件可能已损坏或受密码保护。"])
        }
        return (0..<document.pageCount).compactMap { index in
            guard let page = document.page(at: index) else { return nil }
            let bounds = page.bounds(for: .mediaBox)
            let scale = min(maxDimension / max(bounds.width, bounds.height), 2.5)
            let size = CGSize(width: max(1, bounds.width * scale), height: max(1, bounds.height * scale))
            return page.thumbnail(of: size, for: .mediaBox)
        }
    }

    /// Copy a PDF selected from Files into FreeScan's local library so it remains
    /// available after the document picker closes and across future launches.
    static func importIntoLibrary(from source: URL) throws -> URL {
        let hasScopedAccess = source.startAccessingSecurityScopedResource()
        defer { if hasScopedAccess { source.stopAccessingSecurityScopedResource() } }

        let destination = uniqueDocumentURL(
            baseName: source.deletingPathExtension().lastPathComponent,
            extension: "pdf"
        )
        try FileManager.default.copyItem(at: source, to: destination)
        return destination
    }

    static func makePDF(from images: [UIImage], title: String = "FreeScan",
                        searchableText: [[RecognizedTextLine]]? = nil,
                        filename: String? = nil) throws -> URL {
        guard !images.isEmpty else {
            throw NSError(domain: "FreeScan.PDF", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "没有可导出的页面。"])
        }
        let output = uniqueDocumentURL(baseName: filename ?? "FreeScan-\(UUID().uuidString)", extension: "pdf")
        // Match Android's per-page geometry: preserve each scan's aspect ratio and
        // constrain its longest edge to A4 height and its shortest edge to Letter width.
        // This allows portrait and landscape pages to coexist in one PDF.
        let maxPageEdge: CGFloat = 297 * 72 / 25.4
        let minPageEdge: CGFloat = 215.9 * 72 / 25.4
        let pageBounds = CGRect(x: 0, y: 0, width: maxPageEdge, height: maxPageEdge)
        let renderer = UIGraphicsPDFRenderer(bounds: pageBounds)
        let data = renderer.pdfData { context in
            for (index, image) in images.enumerated() {
                let imageWidth = max(1, image.size.width)
                let imageHeight = max(1, image.size.height)
                let longEdge = max(imageWidth, imageHeight)
                let shortEdge = min(imageWidth, imageHeight)
                let scale = min(1, min(maxPageEdge / longEdge, minPageEdge / shortEdge))
                let pageSize = CGSize(width: imageWidth * scale, height: imageHeight * scale)
                context.beginPage(withBounds: CGRect(origin: .zero, size: pageSize), pageInfo: [
                    kCGPDFContextTitle as String: title,
                    kCGPDFContextCreator as String: "FreeScan iOS",
                ])
                let fitted = AVMakeRect(aspectRatio: image.size, insideRect: CGRect(origin: .zero, size: pageSize))
                image.draw(in: fitted)
                if let searchableText, searchableText.indices.contains(index) {
                    drawInvisibleText(searchableText[index], in: fitted, context: context.cgContext)
                }
            }
        }
        try data.write(to: output, options: .atomic)
        try mirrorToConfiguredDirectory(output)
        return output
    }

    private static func drawInvisibleText(_ lines: [RecognizedTextLine], in imageRect: CGRect, context: CGContext) {
        context.saveGState()
        context.setTextDrawingMode(.invisible)
        for line in lines where !line.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let bounds = line.bounds.standardized.intersection(CGRect(x: 0, y: 0, width: 1, height: 1))
            guard bounds.width > 0, bounds.height > 0 else { continue }
            let textRect = CGRect(
                x: imageRect.minX + bounds.minX * imageRect.width,
                y: imageRect.minY + (1 - bounds.maxY) * imageRect.height,
                width: max(1, bounds.width * imageRect.width),
                height: max(1, bounds.height * imageRect.height)
            )
            OCRPDFText.draw(line.text, in: textRect, context: context)
        }
        context.restoreGState()
    }

    static func makeJPEGs(from images: [UIImage], quality: CGFloat? = nil,
                          baseName: String? = nil) throws -> [URL] {
        let configuredQuality = UserDefaults.standard.object(forKey: "exportQuality") as? Double ?? 0.92
        let compressionQuality = min(1, max(0.1, quality ?? CGFloat(configuredQuality)))
        return try images.enumerated().map { index, image in
            let stem = baseName ?? "FreeScan-\(UUID().uuidString)"
            let pageName = images.count == 1 ? stem : "\(stem)_\(index + 1)"
            let url = uniqueDocumentURL(baseName: pageName, extension: "jpg")
            guard let data = image.jpegData(compressionQuality: compressionQuality) else {
                throw NSError(domain: "FreeScan.Image", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: "无法编码 JPEG 页面。"])
            }
            try data.write(to: url, options: .atomic)
            try mirrorToConfiguredDirectory(url)
            return url
        }
    }

    private static func uniqueDocumentURL(baseName: String, extension fileExtension: String) -> URL {
        uniqueURL(in: documentsDirectory, baseName: baseName, extension: fileExtension)
    }

    private static func uniqueURL(in directory: URL, baseName: String, extension fileExtension: String) -> URL {
        let invalid = CharacterSet(charactersIn: "/\\:*?\"<>|")
        var cleaned = baseName.components(separatedBy: invalid).joined(separator: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: ".")))
        for suffix in [".pdf", ".jpeg", ".jpg"] where cleaned.lowercased().hasSuffix(suffix) {
            cleaned.removeLast(suffix.count)
            break
        }
        let stem = cleaned.isEmpty ? "FreeScan-\(UUID().uuidString)" : cleaned
        var candidate = directory.appendingPathComponent(stem).appendingPathExtension(fileExtension)
        var suffix = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            candidate = directory.appendingPathComponent("\(stem) (\(suffix))")
                .appendingPathExtension(fileExtension)
            suffix += 1
        }
        return candidate
    }

    /// Save a second copy to the user's Files folder. The app-local copy remains
    /// the share-sheet/library URL so its security scope is not lost.
    private static func mirrorToConfiguredDirectory(_ source: URL) throws {
        guard let bookmark = UserDefaults.standard.data(forKey: exportFolderBookmarkKey) else { return }
        var isStale = false
        let directory = try URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil,
                                bookmarkDataIsStale: &isStale)
        let hasScopedAccess = directory.startAccessingSecurityScopedResource()
        defer { if hasScopedAccess { directory.stopAccessingSecurityScopedResource() } }

        let target = uniqueURL(in: directory, baseName: source.deletingPathExtension().lastPathComponent,
                               extension: source.pathExtension)
        let coordinator = NSFileCoordinator(filePresenter: nil)
        var coordinationError: NSError?
        var copyError: Error?
        coordinator.coordinate(writingItemAt: target, options: .forReplacing, error: &coordinationError) { coordinatedURL in
            do { try FileManager.default.copyItem(at: source, to: coordinatedURL) }
            catch { copyError = error }
        }
        if let coordinationError { throw coordinationError }
        if let copyError { throw copyError }

        if isStale { try setExportDirectory(directory) }
    }

    static func printPDF(at url: URL) {
        let controller = UIPrintInteractionController.shared
        let info = UIPrintInfo(dictionary: nil)
        info.jobName = url.lastPathComponent
        info.outputType = .general
        controller.printInfo = info
        controller.printingItem = url
        controller.present(animated: true)
    }

    static func saveToPhotos(_ image: UIImage) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            throw NSError(domain: "FreeScan.Photos", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "请在系统设置中允许 FreeScan 保存到照片。"])
        }
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.creationRequestForAsset(from: image)
        }
    }
}

