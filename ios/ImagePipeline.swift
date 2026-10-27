/*
 * Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import UIKit
import Vision

enum ImagePipeline {
    private static let ciContext = CIContext(options: [.useSoftwareRenderer: false])

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
        guard let cgImage = image.cgImage else { return image }
        let input = CIImage(cgImage: cgImage)
        let controls = CIFilter.colorControls()
        controls.inputImage = input
        switch mode {
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
        }
        guard let output = controls.outputImage,
              let result = ciContext.createCGImage(output, from: output.extent) else { return image }
        return UIImage(cgImage: result, scale: image.scale, orientation: .up)
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

    /// Local HSV-like color heuristic for colored pen. It does not identify black ink
    /// and can also select colored printed content; users can undo by restoring Original.
    static func removeColoredInk(_ image: UIImage) -> UIImage {
        let image = upright(image)
        guard let buffer = RGBAImage(image) else { return image }
        var mask = [Bool](repeating: false, count: buffer.width * buffer.height)
        var marked = 0
        for y in 0..<buffer.height {
            for x in 0..<buffer.width {
                let p = buffer.pixel(x: x, y: y)
                let hi = max(p.r, max(p.g, p.b))
                let lo = min(p.r, min(p.g, p.b))
                let saturation = hi == 0 ? 0 : (hi - lo) / hi
                let isInkColor = saturation > 0.20 && hi > 0.18 && hi < 0.99
                let index = y * buffer.width + x
                mask[index] = isInkColor
                if isInkColor { marked += 1 }
            }
        }
        guard marked > 0, Double(marked) / Double(mask.count) < 0.10 else { return image }
        var pixels = buffer.pixels
        for y in 0..<buffer.height {
            var x = 0
            while x < buffer.width {
                let index = y * buffer.width + x
                guard mask[index] else { x += 1; continue }
                let start = x
                while x < buffer.width && mask[y * buffer.width + x] { x += 1 }
                let end = x - 1
                let left = start > 0 ? buffer.pixel(x: start - 1, y: y) : nil
                let right = x < buffer.width ? buffer.pixel(x: x, y: y) : nil
                for px in start...end {
                    let t = CGFloat(px - start + 1) / CGFloat(end - start + 2)
                    let color: RGBAImage.Pixel
                    if let left, let right {
                        color = .lerp(left, right, t)
                    } else if let left {
                        color = left
                    } else if let right {
                        color = right
                    } else {
                        color = buffer.pixel(x: px, y: y)
                    }
                    buffer.setPixel(color, x: px, y: y, in: &pixels)
                }
            }
        }
        return buffer.image(from: pixels) ?? image
    }

    static func qrRectangles(in image: UIImage) -> [CGRect] {
        let image = upright(image)
        guard let cgImage = image.cgImage else { return [] }
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        do {
            try VNImageRequestHandler(cgImage: cgImage, orientation: .up).perform([request])
        } catch {
            return []
        }
        var regions = (request.results ?? []).map { observation in
            let box = observation.boundingBox
            // Vision rectangles use a bottom-left origin; image-space uses top-left.
            let rect = CGRect(x: box.minX, y: 1 - box.maxY, width: box.width, height: box.height)
            return rect.insetBy(dx: -max(0.008, rect.width * 0.12), dy: -max(0.008, rect.height * 0.12))
                .intersection(CGRect(x: 0, y: 0, width: 1, height: 1))
        }
        // Preserve the Android app's automatic Quark footer path: the brand and QR can be
        // detected as a footer even when the QR itself is too small for barcode recognition.
        if let pixels = RGBAImage(image) {
            let startX = Int(CGFloat(pixels.width) * 0.62)
            let startY = Int(CGFloat(pixels.height) * 0.90)
            var blueSamples = 0
            if startX < pixels.width && startY < pixels.height {
                for y in stride(from: startY, to: pixels.height, by: 2) {
                    for x in stride(from: startX, to: pixels.width, by: 2) {
                        let pixel = pixels.pixel(x: x, y: y)
                        if pixel.b >= CGFloat(90) / 255 && pixel.b - pixel.r >= CGFloat(28) / 255 &&
                            pixel.b - pixel.g >= CGFloat(4) / 255 {
                            blueSamples += 1
                            if blueSamples >= 12 {
                                regions.append(CGRect(x: 0.64, y: 0.90, width: 0.36, height: 0.10))
                                break
                            }
                        }
                    }
                    if blueSamples >= 12 { break }
                }
            }
        }
        return regions
    }

    /// Fast local repair by interpolating colors from opposite region borders.
    /// This is suitable for small marks over plain paper, not complex photo content.
    static func repair(_ image: UIImage, regions: [CGRect]) -> UIImage {
        let image = upright(image)
        guard let buffer = RGBAImage(image), !regions.isEmpty else { return image }
        var pixels = buffer.pixels
        for region in regions {
            let r = region.standardized.intersection(CGRect(x: 0, y: 0, width: 1, height: 1))
            let left = max(0, Int(r.minX * CGFloat(buffer.width)))
            let right = min(buffer.width - 1, Int(r.maxX * CGFloat(buffer.width)))
            let top = max(0, Int(r.minY * CGFloat(buffer.height)))
            let bottom = min(buffer.height - 1, Int(r.maxY * CGFloat(buffer.height)))
            guard right > left, bottom > top else { continue }
            for y in top...bottom {
                let t = CGFloat(y - top) / CGFloat(max(1, bottom - top))
                let c0 = buffer.pixel(x: max(0, left - 1), y: y)
                let c1 = buffer.pixel(x: min(buffer.width - 1, right + 1), y: y)
                for x in left...right {
                    let from = buffer.pixel(x: x, y: max(0, top - 1))
                    let to = buffer.pixel(x: x, y: min(buffer.height - 1, bottom + 1))
                    let vertical = RGBAImage.Pixel.lerp(from, to, t)
                    let horizontal = RGBAImage.Pixel.lerp(c0, c1, CGFloat(x - left) / CGFloat(max(1, right - left)))
                    // Prefer the interpolation direction whose border samples are closer in luminance.
                    let horizontalCost = abs(c0.luminance - c1.luminance)
                    let verticalCost = abs(from.luminance - to.luminance)
                    buffer.setPixel(horizontalCost <= verticalCost ? horizontal : vertical, x: x, y: y, in: &pixels)
                }
            }
        }
        return buffer.image(from: pixels) ?? image
    }

    static func recognizeText(_ image: UIImage, language: String = "zh-Hans") async throws -> String {
        let image = upright(image)
        guard let cgImage = image.cgImage else { return "" }
        return try await withCheckedThrowingContinuation { continuation in
            let request = VNRecognizeTextRequest { request, error in
                if let error { continuation.resume(throwing: error); return }
                let text = (request.results as? [VNRecognizedTextObservation] ?? [])
                    .compactMap { $0.topCandidates(1).first?.string }
                    .joined(separator: "\n")
                continuation.resume(returning: text)
            }
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            request.recognitionLanguages = [language]
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

