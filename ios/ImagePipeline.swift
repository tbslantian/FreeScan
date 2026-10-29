/*
 * Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import CoreImage
import CoreImage.CIFilterBuiltins
import CoreText
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
        return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
    }

    static func filtered(_ image: UIImage, mode: ScanFilter) -> UIImage {
        let image = upright(image)
        if mode == .automatic {
            let resolved: ScanFilter = containsColor(image) ? .color : .grayscale
            return filtered(image, mode: resolved)
        }
        if mode == .original { return image }
        guard let cgImage = image.cgImage else { return image }
        let input = CIImage(cgImage: cgImage)
        let controls = CIFilter.colorControls()
        controls.inputImage = input
        switch mode {
        case .automatic, .original:
            return image
        case .color:
            controls.saturation = 1.05
            controls.contrast = 1.08
            controls.brightness = 0.01
        case .grayscale:
            controls.saturation = 0
            controls.contrast = 1.12
            controls.brightness = 0.01
        case .blackAndWhite:
            controls.saturation = 0
            controls.contrast = 2.15
            controls.brightness = 0.08
        case .brighten:
            controls.saturation = 1
            controls.contrast = 1.04
            controls.brightness = 0.10
        case .enhance:
            controls.saturation = 0.92
            controls.contrast = 1.32
            controls.brightness = 0.035
        case .localGrayscale:
            controls.saturation = 0
            controls.contrast = 1.32
            controls.brightness = 0.025
        case .localSoft:
            controls.saturation = 0.96
            controls.contrast = 1.16
            controls.brightness = 0.035
        }
        guard var output = controls.outputImage else { return image }
        if mode == .brighten || mode == .enhance || mode == .localSoft {
            let shadows = CIFilter.highlightShadowAdjust()
            shadows.inputImage = output
            shadows.shadowAmount = mode == .brighten ? 0.65 : 0.35
            shadows.highlightAmount = 0.85
            output = shadows.outputImage ?? output
        }
        if mode == .enhance || mode == .localGrayscale || mode == .localSoft {
            let sharpen = CIFilter.sharpenLuminance()
            sharpen.inputImage = output
            sharpen.sharpness = mode == .enhance ? 0.65 : 0.32
            output = sharpen.outputImage ?? output
        }
        guard let result = ciContext.createCGImage(output, from: output.extent) else { return image }
        return UIImage(cgImage: result, scale: image.scale, orientation: .up)
    }

    private static func containsColor(_ image: UIImage) -> Bool {
        let sampleImage = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 64)).image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: 64, height: 64))
        }
        guard let buffer = RGBAImage(sampleImage), buffer.width > 0, buffer.height > 0 else { return true }
        var colored = 0
        var total = 0
        for y in 0..<buffer.height {
            for x in 0..<buffer.width {
                let pixel = buffer.pixel(x: x, y: y)
                let maximum = max(pixel.r, max(pixel.g, pixel.b))
                let minimum = min(pixel.r, min(pixel.g, pixel.b))
                if maximum > 0.12 && (maximum - minimum) / maximum > 0.12 { colored += 1 }
                total += 1
            }
        }
        return total > 0 && Double(colored) / Double(total) >= 0.025
    }

    /// Estimate the paper illumination on a coarse grid using bright local samples, then
    /// normalize each pixel against that smooth field. This avoids a page-wide blur that
    /// would wash out printed text.
    static func removeShadows(_ image: UIImage) -> UIImage {
        let image = upright(image)
        guard let source = RGBAImage(image), source.width > 24, source.height > 24 else { return image }
        let columns = 32
        let rows = 32
        var background = [CGFloat](repeating: 0.85, count: columns * rows)
        for gy in 0..<rows {
            for gx in 0..<columns {
                let x0 = gx * source.width / columns
                let x1 = max(x0 + 1, (gx + 1) * source.width / columns)
                let y0 = gy * source.height / rows
                let y1 = max(y0 + 1, (gy + 1) * source.height / rows)
                let xStep = max(1, (x1 - x0) / 10)
                let yStep = max(1, (y1 - y0) / 10)
                var samples = [CGFloat]()
                for y in stride(from: y0, to: min(y1, source.height), by: yStep) {
                    for x in stride(from: x0, to: min(x1, source.width), by: xStep) {
                        let i = (y * source.width + x) * 4
                        let r = CGFloat(source.pixels[i]) / 255
                        let g = CGFloat(source.pixels[i + 1]) / 255
                        let b = CGFloat(source.pixels[i + 2]) / 255
                        let luma = 0.299 * r + 0.587 * g + 0.114 * b
                        if luma > 0.35 { samples.append(luma) }
                    }
                }
                samples.sort()
                let upper = samples.suffix(max(1, samples.count / 3))
                background[gy * columns + gx] = upper.isEmpty ? 0.85 : upper.reduce(0, +) / CGFloat(upper.count)
            }
        }
        // Smooth cell boundaries so the correction field does not imprint a grid.
        for _ in 0..<2 {
            var smoothed = background
            for y in 0..<rows {
                for x in 0..<columns {
                    var total: CGFloat = 0
                    var count: CGFloat = 0
                    for sy in max(0, y - 1)...min(rows - 1, y + 1) {
                        for sx in max(0, x - 1)...min(columns - 1, x + 1) {
                            total += background[sy * columns + sx]
                            count += 1
                        }
                    }
                    smoothed[y * columns + x] = total / count
                }
            }
            background = smoothed
        }
        let sortedBackground = background.sorted()
        let target = sortedBackground[sortedBackground.count / 2]
        var output = source.pixels
        for y in 0..<source.height {
            let gy = CGFloat(y) / CGFloat(max(1, source.height - 1)) * CGFloat(rows - 1)
            let y0 = Int(gy)
            let y1 = min(rows - 1, y0 + 1)
            let ty = gy - CGFloat(y0)
            for x in 0..<source.width {
                let gx = CGFloat(x) / CGFloat(max(1, source.width - 1)) * CGFloat(columns - 1)
                let x0 = Int(gx)
                let x1 = min(columns - 1, x0 + 1)
                let tx = gx - CGFloat(x0)
                let top = background[y0 * columns + x0] * (1 - tx) + background[y0 * columns + x1] * tx
                let bottom = background[y1 * columns + x0] * (1 - tx) + background[y1 * columns + x1] * tx
                let local = max(0.18, top * (1 - ty) + bottom * ty)
                let gain = min(1.42, max(0.72, target / local))
                let index = (y * source.width + x) * 4
                output[index] = UInt8(min(255, CGFloat(source.pixels[index]) * gain))
                output[index + 1] = UInt8(min(255, CGFloat(source.pixels[index + 1]) * gain))
                output[index + 2] = UInt8(min(255, CGFloat(source.pixels[index + 2]) * gain))
            }
        }
        return source.image(from: output) ?? image
    }

    static func rotated(_ image: UIImage, clockwiseDegrees: Int) -> UIImage {
        let radians = CGFloat(clockwiseDegrees) * .pi / 180
        let size = CGSize(width: image.size.width, height: image.size.height)
        let rotatedSize = CGRect(origin: .zero, size: size).applying(CGAffineTransform(rotationAngle: radians)).integral.size
        let renderer = UIGraphicsImageRenderer(size: rotatedSize)
        return renderer.image { context in
            context.cgContext.translateBy(x: rotatedSize.width / 2, y: rotatedSize.height / 2)
            context.cgContext.rotate(by: radians)
            image.draw(in: CGRect(x: -size.width / 2, y: -size.height / 2, width: size.width, height: size.height))
        }
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
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            normalized.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    /// Vision rectangle detection followed by Core Image perspective correction.
    static func autoCropDocument(_ image: UIImage) -> UIImage {
        let image = upright(image)
        guard let cgImage = image.cgImage else { return image }
        let request = VNDetectRectanglesRequest()
        request.maximumObservations = 4
        request.minimumConfidence = 0.45
        request.minimumSize = 0.12
        request.quadratureTolerance = 25
        do {
            try VNImageRequestHandler(cgImage: cgImage, orientation: .up).perform([request])
        } catch {
            return image
        }
        guard let rectangle = request.results?.max(by: { $0.boundingBox.width * $0.boundingBox.height < $1.boundingBox.width * $1.boundingBox.height }),
              rectangle.boundingBox.width * rectangle.boundingBox.height > 0.08 else { return image }

        let input = CIImage(cgImage: cgImage)
        let correction = CIFilter.perspectiveCorrection()
        correction.inputImage = input
        correction.topLeft = scaled(rectangle.topLeft, size: input.extent.size)
        correction.topRight = scaled(rectangle.topRight, size: input.extent.size)
        correction.bottomLeft = scaled(rectangle.bottomLeft, size: input.extent.size)
        correction.bottomRight = scaled(rectangle.bottomRight, size: input.extent.size)
        guard let output = correction.outputImage,
              let result = ciContext.createCGImage(output, from: output.extent) else { return image }
        return UIImage(cgImage: result, scale: image.scale, orientation: .up)
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

    static func perspectiveCrop(_ image: UIImage, quad: DocumentQuad) -> UIImage {
        let image = upright(image)
        guard let cgImage = image.cgImage else { return image }
        let input = CIImage(cgImage: cgImage)
        let extent = input.extent
        func ciPoint(_ point: CGPoint) -> CGPoint {
            CGPoint(x: extent.minX + point.x * extent.width,
                    y: extent.minY + (1 - point.y) * extent.height)
        }
        let correction = CIFilter.perspectiveCorrection()
        correction.inputImage = input
        correction.topLeft = ciPoint(quad.topLeft)
        correction.topRight = ciPoint(quad.topRight)
        correction.bottomLeft = ciPoint(quad.bottomLeft)
        correction.bottomRight = ciPoint(quad.bottomRight)
        guard let output = correction.outputImage,
              let result = ciContext.createCGImage(output, from: output.extent) else { return image }
        return UIImage(cgImage: result, scale: image.scale, orientation: .up)
    }

    /// Same OpenCV 4.12 HSV mask, morphological close/dilation and Telea repair
    /// as Android. Colored print can be selected along with handwritten ink.
    static func removeColoredInk(_ image: UIImage) -> UIImage {
        let source = upright(image)
        return FreeScanOpenCV.removeColoredInk(source) ?? source
    }

    static func qrRectangles(in image: UIImage) -> [CGRect] {
        qrCandidates(in: image).filter(\.automaticallyRepairable).map(\.bounds)
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
        let lines = try await recognizeTextLines(image, language: language)
        return lines.map(\.text).joined(separator: "\n")
    }

    static func recognizeTextLines(_ image: UIImage, language: String = "zh-Hans") async throws -> [RecognizedTextLine] {
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

    private static func scaled(_ point: CGPoint, size: CGSize) -> CGPoint {
        CGPoint(x: point.x * size.width, y: point.y * size.height)
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

