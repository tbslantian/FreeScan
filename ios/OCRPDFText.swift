/* Copyright 2025-2026 The FairScan authors
 * SPDX-License-Identifier: GPL-3.0-or-later */
import Foundation
import CoreGraphics
import CoreText

enum OCRPDFText {
    /// Bounds use the same top-left coordinates as UIGraphicsPDFRenderer.
    static func draw(_ text: String, in bounds: CGRect, context: CGContext) {
        guard bounds.width > 0, bounds.height > 0, bounds.width.isFinite, bounds.height.isFinite else { return }
        let font = CTFontCreateWithName("Helvetica" as CFString, 12, nil)
        let attributed = NSAttributedString(string: text, attributes: [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
        ])
        let line = CTLineCreateWithAttributedString(attributed as CFAttributedString)
        var ascent: CGFloat = 0, descent: CGFloat = 0
        let width = CGFloat(CTLineGetTypographicBounds(line, &ascent, &descent, nil))
        let height = ascent + descent
        guard width > 0, height > 0 else { return }
        context.saveGState()
        defer { context.restoreGState() }
        context.textMatrix = .identity
        context.translateBy(x: bounds.minX, y: bounds.maxY)
        context.scaleBy(x: bounds.width / width, y: -bounds.height / height)
        context.textPosition = CGPoint(x: 0, y: descent)
        context.setTextDrawingMode(.invisible)
        // Draw one complete line, fitted to its OCR bounds without wrapping or
        // clipping the trailing characters of narrow words/lines.
        CTLineDraw(line, context)
    }
}
