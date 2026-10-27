# FreeScan iOS

This is the native SwiftUI companion project for the Android FreeScan app. It has no external package dependencies and targets iOS 17 or later.

## Features

- Camera capture and photo import, Vision document-edge detection, perspective correction, and optional auto-crop.
- Nine certificate/document framing templates with live camera guides.
- Multi-page editing: page selection, rotation, manual crop, color/grayscale/black-and-white/enhance filters.
- PDF import as editable image pages, PDF/JPEG export, Files sharing, and AirPrint.
- On-device Vision OCR for Simplified Chinese, Traditional Chinese, and English.
- QR detection, Quark blue-footer compatibility detection, and local region repair for images and imported PDFs, plus multi-region manual repair.
- Local colored-ink heuristic cleanup and manual region repair for exam pages.
- Local settings for auto-crop, default filter, OCR language, and export quality.

Image-based PDF editing rasterizes imported pages before editing. QR repair uses local border interpolation and works best on small marks over smooth backgrounds; it is not content-aware inpainting. Colored handwriting removal is a color heuristic and may affect colored print; manual repair is available for residual marks.

## Platform mapping

The iOS implementation uses Apple Vision, Core Image, AVFoundation, PDFKit, PhotosUI, and UIKit. It is a native port of the user-facing workflows; it does not reuse Android-only OpenCV/LiteRT/Tesseract binaries. OCR uses the on-device Vision recognizer instead of downloadable Tesseract language packs. PDF pages are rasterized for editing, so selectable text and original vector content are not preserved in an edited export. The automatic Quark compatibility check covers the blue footer heuristic and QR codes; it is not a byte-for-byte port of the Android multi-detector pipeline.

## Build

Open FreeScan.xcodeproj on a Mac with Xcode 15 or newer. Select the FreeScan scheme and an iOS simulator or device, then build. For device distribution, set your Apple Developer Team and signing identity in Xcode.

The source was prepared on Windows and could not be compiled with Xcode in this environment. Build and device validation must be completed on macOS.

## License

GPL-3.0-or-later. See LICENSE.
