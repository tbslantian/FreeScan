/*
 * Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import CoreImage
import CoreImage.CIFilterBuiltins
import CoreText
import CoreVideo
import Foundation
import UIKit
import Vision

struct RecognizedTextLine {
    let text: String
    /// Vision normalized bounds use a bottom-left origin.
    let bounds: CGRect
}

enum QRDetectionKind: String {
    case verified
    case unverified
    case quarkFooter
    case manual
}

struct QRDetectionCandidate: Identifiable {
    let id: UUID
    var bounds: CGRect
    let corners: [CGPoint]
    let kind: QRDetectionKind
    let automaticallyRepairable: Bool

    init(id: UUID = UUID(), bounds: CGRect, kind: QRDetectionKind, automaticallyRepairable: Bool, corners: [CGPoint]? = nil) {
        self.id = id
        self.bounds = bounds
        self.corners = corners ?? [CGPoint(x: bounds.minX, y: bounds.minY),
                                   CGPoint(x: bounds.maxX, y: bounds.minY),
                                   CGPoint(x: bounds.maxX, y: bounds.maxY),
                                   CGPoint(x: bounds.minX, y: bounds.maxY)]
        self.kind = kind
        self.automaticallyRepairable = automaticallyRepairable
    }
}

struct LocalRepairStroke {
    /// Normalized image coordinates with a top-left origin.
    let points: [CGPoint]
    /// Brush diameter as a fraction of the image's shorter edge.
    let width: CGFloat
}

enum ImagePipeline {
    private static let ciContext = CIContext(options: [.useSoftwareRenderer: false])

    static func supportedOCRLanguages() -> [String] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        return (try? request.supportedRecognitionLanguages()) ?? ["zh-Hans", "zh-Hant", "en-US"]
    }

    static func upright(_ image: UIImage) -> UIImage {
        guard image.imageOrientation != .up else { return image }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = image.scale
        format.opaque = false
        format.preferredRange = .standard
        return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
    }

    /// Shared by preview, PDF, JPEG and print.
    static func filtered(_ image: UIImage, mode: ScanFilter, removeShadows: Bool = false) -> UIImage {
        let source = upright(image)
        switch mode {
        case .original: return source
        case .removeHandwriting: return removeColoredInk(source)
        case .removeWatermark:
            let candidates = qrCandidates(in: source).filter { candidate in
                candidate.kind == .quarkFooter ||
                    (candidate.kind == .verified && candidate.automaticallyRepairable &&
                     (candidate.bounds.minY > 0.8 || candidate.bounds.maxY < 0.2))
            }
            return (try? repair(source, candidates: candidates)) ?? source
        default:
            return FreeScanOpenCV.filtered(source, mode: mode.nativeMode, shadows: removeShadows) ?? source
        }
    }

    static func rotatedClockwise(_ image: UIImage) -> UIImage {
        let source = upright(image)
        guard let cgImage = source.cgImage else { return source }
        return upright(UIImage(cgImage: cgImage, scale: source.scale, orientation: .right))
    }

    /// Keep the source frame so the proposed crop can be adjusted later.
    static func prepareScan(_ image: UIImage, autoCrop: Bool, maxDimension: Int,
                            filter: ScanFilter, geometry: ScanGeometryPolicy = .document,
                            bookPage: Bool = false) -> ScanPage {
        let source = resize(image, maxDimension: maxDimension)
        let quad = autoCrop ? detectDocumentQuad(source) : nil
        let corrected = quad.flatMap { cropForScan(source, quad: $0, geometry: geometry) }
        let result = corrected ?? refineGeometry(source, geometry: geometry, allowDewarp: bookPage)
        return ScanPage(id: UUID(), original: source, image: result, filter: filter,
                        cropQuad: corrected == nil ? nil : quad, geometry: geometry)
    }

    static func deskewText(_ image: UIImage) throws -> (image: UIImage, angle: Double) {
        guard let result = FreeScanOpenCV.deskewText(upright(image)) else {
            throw NSError(domain: "FreeScan.Deskew", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "无法完成文字行校正，请换一张图片后重试。"])
        }
        return (result.image, result.angle)
    }

    /// Caps the captured image's longest pixel edge to the selected scan size.
    static func resize(_ image: UIImage, maxDimension: Int) -> UIImage {
        let normalized = upright(image)
        guard let cgImage = normalized.cgImage, maxDimension > 0 else { return normalized }
        let longest = CGFloat(max(cgImage.width, cgImage.height))
        let scale = min(1, CGFloat(maxDimension) / longest)
        guard scale < 1 else { return normalized }
        let size = CGSize(width: max(1, (CGFloat(cgImage.width) * scale).rounded()),
                          height: max(1, (CGFloat(cgImage.height) * scale).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        format.preferredRange = .standard
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            normalized.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    private static func detectDocumentQuad(_ image: UIImage) -> DocumentQuad? {
        guard let cgImage = image.cgImage else { return nil }
        return detectDocumentEdges(CIImage(cgImage: cgImage)) ??
            detectDocumentWithVision(VNImageRequestHandler(cgImage: cgImage, orientation: .up))
    }

    static func detectDocumentQuad(_ buffer: CVPixelBuffer, orientation: CGImagePropertyOrientation) -> DocumentQuad? {
        let image = CIImage(cvPixelBuffer: buffer).oriented(orientation)
        return detectDocumentEdges(image) ??
            detectDocumentWithVision(VNImageRequestHandler(cvPixelBuffer: buffer, orientation: orientation))
    }

    private static func detectDocumentEdges(_ image: CIImage) -> DocumentQuad? {
        let extent = image.extent
        guard extent.width > 0, extent.height > 0 else { return nil }
        let modelSize: CGFloat = 256
        let input = image.transformed(by: CGAffineTransform(translationX: -extent.minX, y: -extent.minY))
            .transformed(by: CGAffineTransform(scaleX: modelSize / extent.width, y: modelSize / extent.height))
        let bounds = CGRect(x: 0, y: 0, width: modelSize, height: modelSize)
        guard let sample = ciContext.createCGImage(input, from: bounds),
              let corners = FreeScanOpenCV.documentCorners(sample), corners.count == 4 else { return nil }
        let quad = DocumentQuad(topLeft: corners[0].cgPointValue, topRight: corners[1].cgPointValue,
                                bottomRight: corners[2].cgPointValue, bottomLeft: corners[3].cgPointValue)
        return quad.isValid ? quad : nil
    }

    private static func detectDocumentWithVision(_ handler: VNImageRequestHandler) -> DocumentQuad? {
        let segmentation = VNDetectDocumentSegmentationRequest()
        if (try? handler.perform([segmentation])) != nil,
           let quad = segmentation.results?.compactMap(documentQuad).first {
            return quad
        }

        let request = VNDetectRectanglesRequest()
        request.maximumObservations = 6
        request.minimumConfidence = 0.65
        request.minimumSize = 0.24
        request.minimumAspectRatio = 0.25
        request.quadratureTolerance = 30
        guard (try? handler.perform([request])) != nil else { return nil }
        return request.results?.sorted { rectangleScore($0) > rectangleScore($1) }
            .compactMap(documentQuad).first
    }

    private static func rectangleScore(_ observation: VNRectangleObservation) -> CGFloat {
        let bounds = observation.boundingBox
        return bounds.width * bounds.height * CGFloat(observation.confidence)
    }

    private static func documentQuad(_ observation: VNRectangleObservation) -> DocumentQuad? {
        let bounds = observation.boundingBox
        guard bounds.width * bounds.height >= 0.08,
              bounds.width < 0.995 || bounds.height < 0.995 else { return nil }
        func point(_ p: CGPoint) -> CGPoint {
            CGPoint(x: min(1, max(0, p.x)), y: min(1, max(0, 1 - p.y)))
        }
        let quad = DocumentQuad(topLeft: point(observation.topLeft), topRight: point(observation.topRight),
                                bottomRight: point(observation.bottomRight), bottomLeft: point(observation.bottomLeft))
        return quad.isValid ? quad : nil
    }

    static func crop(_ image: UIImage, normalizedRect: CGRect) -> UIImage {
        let image = upright(image)
        guard let cgImage = image.cgImage else { return image }
        let rect = CGRect(
            x: normalizedRect.minX * CGFloat(cgImage.width),
            y: normalizedRect.minY * CGFloat(cgImage.height),
            width: normalizedRect.width * CGFloat(cgImage.width),
            height: normalizedRect.height * CGFloat(cgImage.height)
        ).integral.intersection(CGRect(x: 0, y: 0, width: cgImage.width, height: cgImage.height))
        guard rect.width > 1, rect.height > 1, let result = cgImage.cropping(to: rect) else { return image }
        return UIImage(cgImage: result, scale: image.scale, orientation: .up)
    }

    static func cropForScan(_ image: UIImage, quad: DocumentQuad, geometry: ScanGeometryPolicy? = nil) -> UIImage? {
        guard quad.isValid,
              let crop = FreeScanOpenCV.rectifyDocument(upright(image),
                                                        corners: quad.points.map { NSValue(cgPoint: $0) }) else { return nil }
        guard let geometry else { return (try? deskewText(crop).image) ?? crop }
        return refineGeometry(crop, geometry: geometry, allowDewarp: true, allowDeskewFallback: true)
    }

    private static func refineGeometry(_ image: UIImage, geometry: ScanGeometryPolicy, allowDewarp: Bool,
                                       allowDeskewFallback: Bool = false) -> UIImage {
        let oriented: UIImage
        if geometry.orientText {
            let fallback = { allowDeskewFallback ? (try? deskewText(image).image) ?? image : image }
            oriented = FreeScanOpenCV.orientDocument(image) ?? fallback()
        } else {
            oriented = image
        }
        guard geometry.flattenCurves && allowDewarp else { return oriented }
        return FreeScanOpenCV.flattenDocument(oriented) ?? oriented
    }

    /// Same OpenCV 4.12 HSV mask, morphological close/dilation and Telea repair
    /// as Android. Colored print can be selected along with handwritten ink.
    static func removeColoredInk(_ image: UIImage) -> UIImage {
        let source = upright(image)
        return FreeScanOpenCV.removeColoredInk(source) ?? source
    }

    static func qrCandidates(in image: UIImage) -> [QRDetectionCandidate] {
        let image = upright(image)
        guard image.cgImage != nil else { return [] }
        var regions = FreeScanOpenCV.qrCandidates(image).map { native in
            QRDetectionCandidate(bounds: native.bounds,
                                 kind: native.verified ? .verified : .unverified,
                                 automaticallyRepairable: native.verified,
                                 corners: native.corners.map(\.cgPointValue))
        }
        func overlapFraction(_ a: CGRect, _ b: CGRect) -> CGFloat {
            let intersection = a.intersection(b)
            guard !intersection.isNull, a.width > 0, a.height > 0 else { return 0 }
            return intersection.width * intersection.height / (a.width * a.height)
        }
        // Preserve the Android app's automatic Quark footer path: the brand and QR can be
        // detected as a footer even when the QR itself is too small for barcode recognition.
        var hasQuarkFooter = false
        if let pixels = RGBAImage(image) {
            let startX = Int(CGFloat(pixels.width) * 0.62)
            let startY = Int(CGFloat(pixels.height) * 0.90)
            var blueSamples = 0
            if startX < pixels.width && startY < pixels.height {
                for y in stride(from: startY, to: pixels.height, by: 2) {
                    for x in stride(from: startX, to: Int(CGFloat(pixels.width) * 0.99), by: 2) {
                        let pixel = pixels.pixel(x: x, y: y)
                        if pixel.b >= CGFloat(90) / 255 && pixel.b - pixel.r >= CGFloat(28) / 255 &&
                            pixel.b - pixel.g >= CGFloat(4) / 255 {
                            blueSamples += 1
                            if blueSamples >= 12 {
                                hasQuarkFooter = true
                                break
                            }
                        }
                    }
                    if blueSamples >= 12 { break }
                }
            }
        }
        if hasQuarkFooter {
            let footer = CGRect(x: 0.64, y: 0.90, width: 0.36, height: 0.10)
            regions.removeAll { overlapFraction($0.bounds, footer) > 0.7 }
            regions.append(QRDetectionCandidate(
                bounds: footer, kind: .quarkFooter, automaticallyRepairable: true
            ))
        }
        return regions
    }

    static func repair(_ image: UIImage, regions: [CGRect]) throws -> UIImage {
        try repair(image, candidates: regions.map {
            QRDetectionCandidate(bounds: $0, kind: .manual, automaticallyRepairable: false)
        })
    }

    /// Keep decoded QR quadrilaterals through selection and repair; don't widen
    /// a rotated code into a rectangular erase mask.
    static func repair(_ image: UIImage, candidates: [QRDetectionCandidate]) throws -> UIImage {
        let source = upright(image)
        guard !candidates.isEmpty else { return source }
        let polygons = candidates.map { $0.corners.map { NSValue(cgPoint: $0) } }
        let verified = candidates.map { NSNumber(value: $0.kind == .verified) }
        guard let repaired = FreeScanOpenCV.repairRegions(source, corners: polygons, verified: verified) else {
            throw NSError(domain: "FreeScan.QRRepair", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "选区周围没有足够的可用背景，或修复失败。请缩小选区后重试。"])
        }
        return repaired
    }

    /// Convert normalized brush points to native pixels and use Android's
    /// cropped OpenCV Telea path, preserving pixels outside the dilated mask.
    static func repair(_ image: UIImage, strokes: [LocalRepairStroke]) -> UIImage {
        let source = upright(image)
        guard let cg = source.cgImage, !strokes.isEmpty else { return source }
        let width = CGFloat(cg.width), height = CGFloat(cg.height)
        let points = strokes.map { stroke in
            stroke.points.map { NSValue(cgPoint: CGPoint(x: $0.x * width, y: $0.y * height)) }
        }
        let widths = strokes.map { NSNumber(value: Double($0.width * min(width, height))) }
        return FreeScanOpenCV.repair(source, strokes: points, widths: widths) ?? source
    }

    static func recognizeText(_ image: UIImage, language: String = "zh-Hans") async throws -> String {
        if UserDefaults.standard.string(forKey: "ocrEngine") == "tesseract" {
            return try await recognizeWithTesseract(image).text
        }
        let lines = try await recognizeTextLines(image, language: language)
        return lines.map(\.text).joined(separator: "\n")
    }

    static func recognizeTextLines(_ image: UIImage, language: String = "zh-Hans") async throws -> [RecognizedTextLine] {
        if UserDefaults.standard.string(forKey: "ocrEngine") == "tesseract" {
            return try await recognizeWithTesseract(image).words
        }
        let image = upright(image)
        guard let cgImage = image.cgImage else { return [] }
        return try await withCheckedThrowingContinuation { continuation in
            let request = VNRecognizeTextRequest { request, error in
                if let error { continuation.resume(throwing: error); return }
                let lines: [RecognizedTextLine] =
                    (request.results as? [VNRecognizedTextObservation] ?? []).compactMap { observation -> RecognizedTextLine? in
                    guard let text = observation.topCandidates(1).first?.string else { return nil }
                    return RecognizedTextLine(text: text, bounds: observation.boundingBox)
                }
                continuation.resume(returning: lines)
            }
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            if language == "auto" {
                request.automaticallyDetectsLanguage = true
            } else {
                let supported = supportedOCRLanguages()
                request.recognitionLanguages = supported.contains(language)
                    ? [language]
                    : [supported.first(where: { $0.hasPrefix("zh-Hans") }) ?? supported.first ?? "en-US"]
            }
            DispatchQueue.global(qos: .userInitiated).async {
                do { try VNImageRequestHandler(cgImage: cgImage, orientation: .up).perform([request]) }
                catch { continuation.resume(throwing: error) }
            }
        }
    }

    private static func recognizeWithTesseract(_ image: UIImage) async throws -> LocalOCRResult {
        let config = try await OCRLanguageStore.shared.configuration()
        return try await TesseractOCRRunner.shared.recognize(upright(image), path: config.path, languages: config.languages)
    }

}

private struct RGBAImage {
    struct Pixel {
        var r: CGFloat
        var g: CGFloat
        var b: CGFloat
        var a: CGFloat
        var bytes: [UInt8] { [UInt8(r * 255), UInt8(g * 255), UInt8(b * 255), UInt8(a * 255)] }
        var luminance: CGFloat { 0.299 * r + 0.587 * g + 0.114 * b }
        static func lerp(_ lhs: Pixel, _ rhs: Pixel, _ t: CGFloat) -> Pixel {
            Pixel(r: lhs.r + (rhs.r - lhs.r) * t, g: lhs.g + (rhs.g - lhs.g) * t,
                  b: lhs.b + (rhs.b - lhs.b) * t, a: lhs.a + (rhs.a - lhs.a) * t)
        }
    }

    let width: Int
    let height: Int
    let pixels: [UInt8]

    init?(_ image: UIImage) {
        guard let cg = image.cgImage else { return nil }
        width = cg.width
        height = cg.height
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                                      bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let data = context.data else { return nil }
        pixels = Array(UnsafeBufferPointer(start: data.assumingMemoryBound(to: UInt8.self),
                                           count: width * height * 4))
    }

    func pixel(x: Int, y: Int) -> Pixel {
        let i = (y * width + x) * 4
        return Pixel(r: CGFloat(pixels[i]) / 255, g: CGFloat(pixels[i + 1]) / 255,
                     b: CGFloat(pixels[i + 2]) / 255, a: CGFloat(pixels[i + 3]) / 255)
    }

    func setPixel(_ pixel: Pixel, x: Int, y: Int, in bytes: inout [UInt8]) {
        let index = (y * width + x) * 4
        let color = pixel.bytes
        bytes[index] = color[0]
        bytes[index + 1] = color[1]
        bytes[index + 2] = color[2]
        bytes[index + 3] = color[3]
    }

    func image(from rgba: [UInt8]) -> UIImage? {
        let bytes = rgba
        guard let provider = CGDataProvider(data: Data(bytes) as CFData),
              let cgImage = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                                    bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                    bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                                    provider: provider, decode: nil, shouldInterpolate: true,
                                    intent: .defaultIntent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

