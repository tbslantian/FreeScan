# Android-to-iOS feature parity

Status is based on the current Android screens and services under `app/src/main/java/org/freescan/app` and the Swift sources under `ios/`. A matching button or screen is not counted as parity when its processing path is materially different.

## Available on iOS, with a different native implementation

- Document camera capture, photo import, automatic perspective correction, and manual crop.
- Nine document/certificate framing guides.
- Multi-page image editing, rotation, page navigation, filters, and shadow correction.
- PDF import as rendered pages, PDF/JPEG export, system sharing, and AirPrint.
- On-device OCR through Vision, including automatic language detection and languages supported by the current iOS device.
- Local QR detection/manual region repair and local color-heuristic handwriting cleanup.
- Settings for automatic crop, filter, shadow correction, scan resolution, export format/quality, and OCR language.

## Remaining parity work

- [x] Camera: throttled Vision rectangle detection, a smoothed four-corner perspective outline, 1080p/2K/4K output sizing, flash modes, and torch control.
- [x] Camera: optional three-frame capture that retains the sharpest frame.
- [x] Camera: match Android's three-consecutive-frame quad stabilizer and its 20 px movement threshold on a 640×480 reference preview.
- [ ] Camera: hardware volume-key shutter; the on-screen shutter remains available on iOS.
- [x] Editor: draggable four-corner perspective crop instead of only rectangular edge sliders.
- [x] Camera workflow: capture review/retake, continuous multi-page capture, and multiple-photo import in one operation.
- [x] Certificate scanner: nine template names, template-specific live preview aspect ratios, and matching document-field guide drawings.
- [x] Certificate scanning: keep Android's nine guide ratios and generic document rectification path, alternate front/back capture prompts, and export through the same mixed-orientation A4/Letter PDF path. Android does not apply a separate physical crop size per template.
- [x] Drafts: persist unfinished scan pages across app restarts.
- [x] Library: search, open into the editor, rename, print, share, and delete saved PDFs.
- [x] Library: show file size and modified date for app-local PDFs and sort newest first.
- [ ] Library: discover imported PDF references and PDFs in user-selected external folders as Android does.
- [x] Export: share every page in multi-page JPEG exports instead of silently sharing only the first page.
- [x] Export: size each PDF page independently from its image aspect ratio, preserving mixed portrait and landscape documents within Android's A4/Letter edge limits.
- [x] Export: use the saved PDF/JPEG preference and JPEG quality setting in scanner and QR/watermark exports.
- [x] Export: set a custom output filename for edited scans and avoid overwriting an existing export with the same name.
- [ ] Export: match Android's configurable output directory.
- [x] Export: include an invisible searchable Vision OCR text layer in scanner PDFs using the selected OCR language.
- [x] OCR: list languages supported by the current iOS Vision implementation and allow automatic language detection.
- [ ] OCR: compare Vision recognition coverage and language behavior with Android's downloadable Tesseract packs; iOS cannot download Tesseract packs through this port.
- [ ] QR/watermark processing: port Android candidate validation, Quark-specific detection, review behavior, and multi-page PDF handling; retain the generic QR/manual repair path.
- [x] Image filters: expose per-page automatic, original, color, grayscale, black-and-white, brightness, enhanced, enhanced grayscale, and soft-enhanced modes using native Core Image operators.
- [ ] Image processing: compare output quality for each Android filter and geometry operation against iOS, including scan resolution and shadow correction.
- [x] Paper eraser: manual brush strokes, brush sizing, undo/redo, clear, original/result comparison, repeat painting, and JPEG sharing.
- [ ] Paper eraser: match Android's full HSV mask closing and OpenCV Telea inpainting output; the current iOS cleanup now uses the Android HSV thresholds plus local mask expansion, but keeps a lighter row-based repair estimator.
- [ ] iPhone/iPad device validation: verify camera, photo/file permissions, page layouts, printing, and memory behavior on physical devices.

## IPA delivery

GitHub Actions is configured to archive for generic iOS devices and export an App Store Connect IPA for both iPhone and iPad. The workflow requires the Apple signing secrets documented in `README.md`. It does not upload a simulator artifact or automatically submit the IPA to App Store Connect.
