# FreeScan iOS

This is the native SwiftUI companion project for the Android FreeScan app. It targets iOS 17 or later and pins OpenCV 4.12.0 through `yeatse/opencv-spm` with a locked revision and binary checksum.

## Features

- Camera capture and photo import, Vision document-edge detection, perspective correction, and optional auto-crop.
- Nine certificate/document framing templates with live camera guides.
- Multi-page editing: page selection, rotation, manual crop, color/grayscale/black-and-white/enhance filters.
- Single or multiple PDF import from Files into a persistent app-local library, editable image pages, PDF/JPEG export with custom names, Files sharing, and AirPrint.
- On-device Vision OCR for Simplified Chinese, Traditional Chinese, and English.
- QR detection, Quark blue-footer compatibility detection, and local region repair for images and imported PDFs, plus multi-region manual repair.
- Local colored-ink heuristic cleanup and a brush eraser with undo/redo and before/after comparison for exam pages.
- Camera document candidate outline, flash/torch controls, and 1080p/2K/4K scan output sizing.
- Local settings for auto-crop, default filter, OCR language, export quality, and an optional Files export folder; exported files remain in the app library and are also copied to the selected folder.

Image-based PDF editing rasterizes imported pages before editing. QR repair uses local border interpolation and works best on small marks over smooth backgrounds; it is not content-aware inpainting. Colored-ink removal and manual brush repair now use OpenCV Telea with the same masks, crop padding and radius rules as Android. Color detection may affect colored print and does not identify black handwriting automatically.

## Platform mapping

The iOS implementation uses Apple Vision, Core Image, AVFoundation, PDFKit, PhotosUI, and UIKit. It is a native port of the user-facing workflows; it uses an iOS OpenCV 4.12.0 framework and does not reuse Android LiteRT/Tesseract binaries. OCR uses the on-device Vision recognizer instead of downloadable Tesseract language packs. PDF pages are rasterized for editing, so selectable text and original vector content are not preserved in an edited export. The automatic Quark compatibility check covers the blue footer heuristic and QR codes; it is not a byte-for-byte port of the Android multi-detector pipeline.

## Device IPA build

The GitHub Actions workflow builds a signed iPhone/iPad archive and exports an App Store Connect IPA. It does not produce a simulator build. The Release target is universal for iPhone and iPad (`TARGETED_DEVICE_FAMILY = 1,2`).

Configure these GitHub Actions repository secrets before running the `iOS IPA` workflow:

- `IOS_CERTIFICATE_P12_BASE64`: Base64 content of an Apple Distribution `.p12` certificate.
- `IOS_CERTIFICATE_PASSWORD`: Password used when exporting the `.p12` file.
- `IOS_PROVISIONING_PROFILE_BASE64`: Base64 content of an App Store Connect provisioning profile.
- `IOS_TEAM_ID`: Apple Developer Team ID that owns the certificate and profile.
- `IOS_BUNDLE_ID`: Explicit App ID / bundle identifier matching the provisioning profile. The default project bundle ID is `org.freescan.app`; use a registered identifier owned by your Apple Developer team.

The IPA artifact is signed for App Store Connect and can be uploaded using Transporter or App Store Connect. Exporting an App Store Connect IPA does not itself submit the app or publish it to TestFlight. Apple distribution signing requires an Apple Distribution certificate and matching provisioning profile. Do not commit those files or passwords to the repository.

The source was prepared on Windows and cannot be compiled with Xcode in this environment. The macOS GitHub Actions runner performs the archive and IPA export.

The iOS implementation is not yet at complete Android feature parity. See [ANDROID_PARITY.md](ANDROID_PARITY.md) for the audited remaining work; the presence of an IPA build does not imply that this feature migration is complete.

## License

GPL-3.0-or-later. See LICENSE.

OpenCV is licensed under Apache-2.0; see [OpenCV-LICENSE.txt](OpenCV-LICENSE.txt). The license is bundled in the app.
