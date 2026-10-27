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

    static func makePDF(from images: [UIImage], title: String = "FreeScan") throws -> URL {
        guard !images.isEmpty else {
            throw NSError(domain: "FreeScan.PDF", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "没有可导出的页面。"])
        }
        let output = documentsDirectory.appendingPathComponent("FreeScan-\(UUID().uuidString).pdf")
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792))
        let data = renderer.pdfData { context in
            for image in images {
                let pageSize = CGSize(width: 612, height: max(396, min(864, 612 * image.size.height / max(1, image.size.width))))
                context.beginPage(withBounds: CGRect(origin: .zero, size: pageSize), pageInfo: [
                    kCGPDFContextTitle as String: title,
                    kCGPDFContextCreator as String: "FreeScan iOS",
                ])
                let fitted = AVMakeRect(aspectRatio: image.size, insideRect: CGRect(origin: .zero, size: pageSize))
                image.draw(in: fitted)
            }
        }
        try data.write(to: output, options: .atomic)
        return output
    }

    static func makeJPEGs(from images: [UIImage], quality: CGFloat? = nil) throws -> [URL] {
        let configuredQuality = UserDefaults.standard.object(forKey: "exportQuality") as? Double ?? 0.92
        let compressionQuality = min(1, max(0.1, quality ?? CGFloat(configuredQuality)))
        try images.enumerated().map { index, image in
            let url = documentsDirectory
                .appendingPathComponent("FreeScan-\(index + 1)-\(UUID().uuidString).jpg")
            guard let data = image.jpegData(compressionQuality: compressionQuality) else {
                throw NSError(domain: "FreeScan.Image", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: "无法编码 JPEG 页面。"])
            }
            try data.write(to: url, options: .atomic)
            return url
        }
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

