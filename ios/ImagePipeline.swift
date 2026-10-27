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

    /// HSV-threshold color mask with morphological close/dilation and local paper
    /// interpolation. It is not OpenCV Telea and can select colored print as well.
    static func removeColoredInk(_ image: UIImage) -> UIImage {
        let image = upright(image)
        guard let buffer = RGBAImage(image) else { return image }
        let pixelCount = buffer.width * buffer.height
        var sourceMask = [Bool](repeating: false, count: pixelCount)
        var marked = 0
        var maskLeft = buffer.width
        var maskTop = buffer.height
        var maskRight = -1
        var maskBottom = -1
        for y in 0..<buffer.height {
            for x in 0..<buffer.width {
                let p = buffer.pixel(x: x, y: y)
                let hi = max(p.r, max(p.g, p.b))
                let lo = min(p.r, min(p.g, p.b))
                let saturation = hi == 0 ? 0 : (hi - lo) / hi
                // Match Android's HSV lower bounds: S >= 30 and V >= 20 on 8-bit channels.
                let isInkColor = saturation * 255 >= 30 && hi * 255 >= 20
                let index = y * buffer.width + x
                sourceMask[index] = isInkColor
                if isInkColor {
                    marked += 1
                    maskLeft = min(maskLeft, x); maskRight = max(maskRight, x)
                    maskTop = min(maskTop, y); maskBottom = max(maskBottom, y)
                }
            }
        }
        guard marked > 0, Double(marked) / Double(pixelCount) < 0.10,
              maskRight >= maskLeft, maskBottom >= maskTop else { return image }

        // Process only the ink region plus the same 32 px context Android gives
        // OpenCV Telea, rather than expanding a 12 MP mask across the whole page.
        let maxDimension = max(buffer.width, buffer.height)
        let closeRadius = 1
        let expansionSize = min(9, max(5, Int((Double(maxDimension) / 1200).rounded()) * 2 + 1))
        let expansionRadius = expansionSize / 2
        let context = 32 + expansionRadius + closeRadius
        let left = max(0, maskLeft - context)
        let top = max(0, maskTop - context)
        let right = min(buffer.width, maskRight + context + 1)
        let bottom = min(buffer.height, maskBottom + context + 1)
        let width = right - left
        let height = bottom - top
        guard width > 0, height > 0 else { return image }

        var raw = [Bool](repeating: false, count: width * height)
        for y in top..<bottom {
            let src = y * buffer.width + left
            let dst = (y - top) * width
            for x in 0..<width { raw[dst + x] = sourceMask[src + x] }
        }
        sourceMask.removeAll(keepingCapacity: false)

        // 3x3 elliptical close (cross-shaped): connect tiny breaks in strokes.
        var closeDilated = [Bool](repeating: false, count: raw.count)
        if width > 2 && height > 2 {
            for y in 1..<(height - 1) {
                for x in 1..<(width - 1) {
                    let index = y * width + x
                    closeDilated[index] = raw[index] || raw[index - 1] || raw[index + 1] ||
                        raw[index - width] || raw[index + width]
                }
            }
        }
        raw.removeAll(keepingCapacity: false)
        var closed = [Bool](repeating: false, count: closeDilated.count)
        if width > 2 && height > 2 {
            for y in 1..<(height - 1) {
                for x in 1..<(width - 1) {
                    let index = y * width + x
                    closed[index] = closeDilated[index] && closeDilated[index - 1] &&
                        closeDilated[index + 1] && closeDilated[index - width] &&
                        closeDilated[index + width]
                }
            }
        }
        closeDilated.removeAll(keepingCapacity: false)

        // Android expands the closed mask with an elliptical 5–9 px kernel to
        // cover antialiased pen edges before repairing the paper texture.
        var mask = [Bool](repeating: false, count: closed.count)
        for y in 0..<height {
            for x in 0..<width where closed[y * width + x] {
                for dy in -expansionRadius...expansionRadius {
                    let yy = y + dy
                    guard yy >= 0, yy < height else { continue }
                    let rowRadius = Int((Double(expansionRadius * expansionRadius - dy * dy)).squareRoot())
                    let x0 = max(0, x - rowRadius)
                    let x1 = min(width - 1, x + rowRadius)
                    guard x0 <= x1 else { continue }
                    for xx in x0...x1 { mask[yy * width + xx] = true }
                }
            }
        }
        closed.removeAll(keepingCapacity: false)

        var pixels = buffer.pixels
        let repairRadius = max(4, min(9, Int(ceil(Double(maxDimension) / 850))))
        func sample(_ x: Int, _ y: Int, dx: Int, dy: Int) -> (RGBAImage.Pixel, Int)? {
            for distance in 1...repairRadius {
                let sx = x + dx * distance
                let sy = y + dy * distance
                guard sx >= 0, sy >= 0, sx < width, sy < height else { return nil }
                if !mask[sy * width + sx] {
                    return (buffer.pixel(x: left + sx, y: top + sy), distance)
                }
            }
            return nil
        }
        func interpolate(_ first: (RGBAImage.Pixel, Int)?, _ second: (RGBAImage.Pixel, Int)?)
            -> (RGBAImage.Pixel, CGFloat)? {
            guard let first, let second else { return nil }
            let total = first.1 + second.1
            let value = RGBAImage.Pixel.lerp(first.0, second.0, CGFloat(first.1) / CGFloat(total))
            let edgeCost = abs(first.0.luminance - second.0.luminance)
            return (value, edgeCost + CGFloat(total) / CGFloat(repairRadius * 32))
        }
        func consider(_ first: (RGBAImage.Pixel, Int)?, _ second: (RGBAImage.Pixel, Int)?,
                      best: inout RGBAImage.Pixel, bestCost: inout CGFloat) {
            guard let candidate = interpolate(first, second), candidate.1 < bestCost else { return }
            best = candidate.0
            bestCost = candidate.1
        }

        for y in 0..<height {
            for x in 0..<width where mask[y * width + x] {
                var repaired = buffer.pixel(x: left + x, y: top + y)
                var bestCost = CGFloat.infinity
                consider(sample(x, y, dx: -1, dy: 0), sample(x, y, dx: 1, dy: 0),
                         best: &repaired, bestCost: &bestCost)
                consider(sample(x, y, dx: 0, dy: -1), sample(x, y, dx: 0, dy: 1),
                         best: &repaired, bestCost: &bestCost)
                consider(sample(x, y, dx: -1, dy: -1), sample(x, y, dx: 1, dy: 1),
                         best: &repaired, bestCost: &bestCost)
                consider(sample(x, y, dx: 1, dy: -1), sample(x, y, dx: -1, dy: 1),
                         best: &repaired, bestCost: &bestCost)
                buffer.setPixel(repaired, x: left + x, y: top + y, in: &pixels)
            }
        }
        return buffer.image(from: pixels) ?? image
    }

    static func qrRectangles(in image: UIImage) -> [CGRect] {
        let image = upright(image)
        guard let cgImage = image.cgImage else { return [] }
        let sourceWidth = cgImage.width
        let sourceHeight = cgImage.height
        let tileSize = 1280
        let tileStep = 1024
        var regions: [CGRect] = []

        func overlapFraction(_ a: CGRect, _ b: CGRect) -> CGFloat {
            let intersection = a.intersection(b)
            guard !intersection.isNull, a.width > 0, a.height > 0 else { return 0 }
            return intersection.width * intersection.height / (a.width * a.height)
        }
        func tileStarts(_ length: Int) -> [Int] {
            guard length > tileSize else { return [0] }
            let last = length - tileSize
            var starts = Array(stride(from: 0, through: last, by: tileStep))
            if starts.last != last { starts.append(last) }
            return starts
        }
        func detect(in view: CGImage, originX: Int, originY: Int,
                    regionWidth: Int, regionHeight: Int) {
            guard min(view.width, view.height) >= 24 else { return }
            let request = VNDetectBarcodesRequest()
            request.symbologies = [.qr]
            do {
                try VNImageRequestHandler(cgImage: view, orientation: .up).perform([request])
            } catch {
                return
            }
            for observation in request.results ?? [] {
                // A decoded payload is the validation gate used by Android before auto-erasing.
                guard observation.symbology == .qr, observation.confidence >= 0.2,
                      let payload = observation.payloadString, !payload.isEmpty else { continue }
                let box = observation.boundingBox
                let detected = CGRect(
                    x: CGFloat(originX) + box.minX * CGFloat(regionWidth),
                    y: CGFloat(originY) + (1 - box.maxY) * CGFloat(regionHeight),
                    width: box.width * CGFloat(regionWidth),
                    height: box.height * CGFloat(regionHeight)
                )
                guard detected.width >= 8, detected.height >= 8,
                      max(detected.width, detected.height) <= CGFloat(min(sourceWidth, sourceHeight)) * 0.30 else {
                    // Oversized detections are left for manual repair instead of auto-erased.
                    continue
                }
                let padded = detected.insetBy(dx: -max(4, detected.width * 0.12),
                                              dy: -max(4, detected.height * 0.12))
                    .intersection(CGRect(x: 0, y: 0, width: sourceWidth, height: sourceHeight))
                guard !padded.isNull, padded.width > 0, padded.height > 0 else { continue }
                let normalized = CGRect(x: padded.minX / CGFloat(sourceWidth),
                                         y: padded.minY / CGFloat(sourceHeight),
                                         width: padded.width / CGFloat(sourceWidth),
                                         height: padded.height / CGFloat(sourceHeight))
                if !regions.contains(where: {
                    overlapFraction($0, normalized) > 0.65 && overlapFraction(normalized, $0) > 0.65
                }) {
                    regions.append(normalized)
                }
            }
        }

        // Downsample the whole page for large marks, then scan overlapping original-resolution
        // tiles so small codes remain detectable in camera-resolution scans.
        let scale = min(1, 1600 / CGFloat(max(sourceWidth, sourceHeight)))
        if scale < 1 {
            let bounds = CGRect(x: 0, y: 0,
                                width: max(1, (CGFloat(sourceWidth) * scale).rounded()),
                                height: max(1, (CGFloat(sourceHeight) * scale).rounded()))
            if let overview = ciContext.createCGImage(CIImage(cgImage: cgImage)
                .transformed(by: CGAffineTransform(scaleX: scale, y: scale)), from: bounds) {
                detect(in: overview, originX: 0, originY: 0,
                       regionWidth: sourceWidth, regionHeight: sourceHeight)
            }
        } else {
            detect(in: cgImage, originX: 0, originY: 0,
                   regionWidth: sourceWidth, regionHeight: sourceHeight)
        }
        if max(sourceWidth, sourceHeight) > tileSize {
            for top in tileStarts(sourceHeight) {
                for left in tileStarts(sourceWidth) {
                    let width = min(tileSize, sourceWidth - left)
                    let height = min(tileSize, sourceHeight - top)
                    guard let tile = cgImage.cropping(to: CGRect(x: left, y: top,
                                                                 width: width, height: height)) else { continue }
                    detect(in: tile, originX: left, originY: top,
                           regionWidth: width, regionHeight: height)
                }
            }
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
                    for x in stride(from: startX, to: pixels.width, by: 2) {
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
            regions.removeAll { overlapFraction($0, footer) > 0.7 }
            regions.append(footer)
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

    /// Repairs hand-painted marks by estimating pixels from the nearest unmasked
    /// samples on each axis. Best suited to paper and other locally smooth surfaces.
    static func repair(_ image: UIImage, strokes: [LocalRepairStroke]) -> UIImage {
        let image = upright(image)
        guard let buffer = RGBAImage(image), !strokes.isEmpty else { return image }
        let count = buffer.width * buffer.height
        var mask = [Bool](repeating: false, count: count)
        var left = buffer.width
        var top = buffer.height
        var right = -1
        var bottom = -1

        func markDisk(centerX: Int, centerY: Int, radius: Int) {
            let y0 = max(0, centerY - radius)
            let y1 = min(buffer.height - 1, centerY + radius)
            let x0 = max(0, centerX - radius)
            let x1 = min(buffer.width - 1, centerX + radius)
            guard x0 <= x1, y0 <= y1 else { return }
            for y in y0...y1 {
                for x in x0...x1 where (x - centerX) * (x - centerX) + (y - centerY) * (y - centerY) <= radius * radius {
                    mask[y * buffer.width + x] = true
                }
            }
            left = min(left, x0); right = max(right, x1)
            top = min(top, y0); bottom = max(bottom, y1)
        }

        for stroke in strokes where !stroke.points.isEmpty {
            let radius = max(2, Int((stroke.width * CGFloat(min(buffer.width, buffer.height)) / 2).rounded()))
            let centers = stroke.points.map { point in
                CGPoint(x: point.x * CGFloat(buffer.width - 1), y: point.y * CGFloat(buffer.height - 1))
            }
            if centers.count == 1, let point = centers.first {
                markDisk(centerX: Int(point.x.rounded()), centerY: Int(point.y.rounded()), radius: radius)
            }
            for (start, end) in zip(centers, centers.dropFirst()) {
                let dx = end.x - start.x
                let dy = end.y - start.y
                let distance = hypot(dx, dy)
                let steps = max(1, Int(ceil(distance / CGFloat(max(1, radius / 2)))) )
                for step in 0...steps {
                    let t = CGFloat(step) / CGFloat(steps)
                    markDisk(centerX: Int((start.x + dx * t).rounded()),
                             centerY: Int((start.y + dy * t).rounded()), radius: radius)
                }
            }
        }

        guard right >= left, bottom >= top else { return image }
        var pixels = buffer.pixels
        let maxSearch = max(8, min(64, Int(CGFloat(min(buffer.width, buffer.height)) * 0.025)))

        func sample(_ x: Int, _ y: Int, dx: Int, dy: Int) -> (RGBAImage.Pixel, Int)? {
            for distance in 1...maxSearch {
                let sx = x + dx * distance
                let sy = y + dy * distance
                guard sx >= 0, sy >= 0, sx < buffer.width, sy < buffer.height else { return nil }
                if !mask[sy * buffer.width + sx] { return (buffer.pixel(x: sx, y: sy), distance) }
            }
            return nil
        }

        for y in top...bottom {
            for x in left...right where mask[y * buffer.width + x] {
                let l = sample(x, y, dx: -1, dy: 0)
                let r = sample(x, y, dx: 1, dy: 0)
                let u = sample(x, y, dx: 0, dy: -1)
                let d = sample(x, y, dx: 0, dy: 1)
                let horizontal: RGBAImage.Pixel? = {
                    guard let l, let r else { return nil }
                    return .lerp(l.0, r.0, CGFloat(l.1) / CGFloat(l.1 + r.1))
                }()
                let vertical: RGBAImage.Pixel? = {
                    guard let u, let d else { return nil }
                    return .lerp(u.0, d.0, CGFloat(u.1) / CGFloat(u.1 + d.1))
                }()
                let repaired: RGBAImage.Pixel
                switch (horizontal, vertical) {
                case let (h?, v?):
                    let hCost = abs((l?.0.luminance ?? h.luminance) - (r?.0.luminance ?? h.luminance))
                    let vCost = abs((u?.0.luminance ?? v.luminance) - (d?.0.luminance ?? v.luminance))
                    repaired = hCost <= vCost ? h : v
                case let (h?, nil): repaired = h
                case let (nil, v?): repaired = v
                default: repaired = buffer.pixel(x: x, y: y)
                }
                buffer.setPixel(repaired, x: x, y: y, in: &pixels)
            }
        }
        return buffer.image(from: pixels) ?? image
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

