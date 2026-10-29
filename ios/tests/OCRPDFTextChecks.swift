/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
import Foundation
import CoreGraphics
import PDFKit

@main
enum OCRPDFTextChecks {
    static func main() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        var urls = [URL]()
        let text = "FreeScan 扫描文档 12345"
        let target = CGRect(x: 40, y: 40, width: 110, height: 12)
        for includeText in [false, true] {
            let url = directory.appendingPathComponent(includeText ? "searchable.pdf" : "original.pdf")
            var mediaBox = CGRect(x: 0, y: 0, width: 500, height: 300)
            let context = CGContext(url as CFURL, mediaBox: &mediaBox, nil)!
            context.beginPDFPage(nil)
            context.setFillColor(gray: 1, alpha: 1); context.fill(mediaBox)
            context.setFillColor(gray: 0, alpha: 1); context.fill(CGRect(x: 20, y: 20, width: 30, height: 30))
            // Match UIGraphicsPDFRenderer's top-left coordinate system.
            context.translateBy(x: 0, y: 300); context.scaleBy(x: 1, y: -1)
            if includeText { OCRPDFText.draw(text, in: target, context: context) }
            context.endPDFPage(); context.closePDF()
            urls.append(url)
        }
        let document = PDFDocument(url: urls[1])!
        let compact = (document.string ?? "").filter { !$0.isWhitespace }
        precondition(compact.contains("FreeScan扫描文档12345"), "PDF clipped or lost searchable Unicode text: \(compact)")
        let page = document.page(at: 0)!
        let selection = document.findString("12345", withOptions: []).first!
        let bounds = selection.bounds(for: page)
        precondition(bounds.minX >= target.minX - 2 && bounds.maxX <= target.maxX + 2, "Search selection outside OCR word width")
        precondition(bounds.minY >= 300 - target.maxY - 2 && bounds.maxY <= 300 - target.minY + 2, "Search selection outside OCR word height")
        func pixels(_ url: URL) -> Data {
            let pdf = CGPDFDocument(url as CFURL)!
            let context = CGContext(data: nil, width: 500, height: 300, bitsPerComponent: 8, bytesPerRow: 2000,
                                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            context.setFillColor(gray: 1, alpha: 1); context.fill(CGRect(x: 0, y: 0, width: 500, height: 300))
            context.drawPDFPage(pdf.page(at: 1)!)
            return Data(bytes: context.data!, count: 2000 * 300)
        }
        let original = pixels(urls[0])
        precondition(original.contains(0), "Source PDF failed to render")
        precondition(original == pixels(urls[1]), "Invisible OCR text changed the page appearance")
        print("All searchable PDF Unicode, coordinates and invisible-rendering checks passed")
    }
}
