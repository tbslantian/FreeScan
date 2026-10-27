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
    private static var documentsDirectory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    static func images(from url: URL, maxDimension: CGFloat = 2200) throws -> [UIImage] {
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
            let fontSize = max(4, min(72, textRect.height * 0.82))
            let attributed = NSAttributedString(
                string: line.text,
                attributes: [
                    .font: UIFont.systemFont(ofSize: fontSize),
                    .foregroundColor: UIColor.black,
                ]
            )
            attributed.draw(with: textRect, options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
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
            return url
        }
    }

    private static func uniqueDocumentURL(baseName: String, extension fileExtension: String) -> URL {
        let invalid = CharacterSet(charactersIn: "/\\:*?\"<>|")
        var cleaned = baseName.components(separatedBy: invalid).joined(separator: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: ".")))
        for suffix in [".pdf", ".jpeg", ".jpg"] where cleaned.lowercased().hasSuffix(suffix) {
            cleaned.removeLast(suffix.count)
            break
        }
        let stem = cleaned.isEmpty ? "FreeScan-\(UUID().uuidString)" : cleaned
        var candidate = documentsDirectory.appendingPathComponent(stem).appendingPathExtension(fileExtension)
        var suffix = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            candidate = documentsDirectory.appendingPathComponent("\(stem) (\(suffix))")
                .appendingPathExtension(fileExtension)
            suffix += 1
        }
        return candidate
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

