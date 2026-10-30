# Legacy Android filter reconstruction

Target: `app/src/debug/jniLibs/arm64-v8a/libdoc_sdk_v1.6.2.1.so`

SHA-256: `16ccda64341bb023c90e65478ebc574ebb2229221c345d9b7886001b8bb82694`.

The REA Windows provider rejects ARM64 ELF decompilation. The existing Ghidra 12.1.4 installation was therefore used directly, with completed default analysis and selected-function decompilation. Addresses below are Ghidra addresses with image base `0x00100000`; subtract that base to obtain module-relative addresses. Decompiled pseudocode is analysis evidence, not original source. The Android ELF is not linked into the iOS app.

## Ported paths

| Android entry / implementation | Observed processing | iOS port |
|---|---|---|
| `0x001dd4e0` JNI `EnhanceBrightness` → `0x001e2a24` `EnhanceLight` → worker `0x001e31a0` | Resize to a 300 px short edge with linear interpolation. Compute a grayscale histogram, cumulative distribution, lower 2% and upper 95% cutoffs. Raise the upper cutoff by 30% of its remaining distance to 255 and reduce the lower cutoff to 75%; clamp and linearly map each original color channel to 0–255 with integer truncation. If the pre-adjusted interval is invalid, retain the image. | `LegacyDocumentFilterCore.hpp::legacyBrightness`, document-filter mode 5 |
| `0x001dd5b8` JNI `EnhanceGray` | RGBA-to-gray conversion, color code 11. There is no additional contrast, sharpening or Retinex operation in this JNI path. | `LegacyDocumentFilterCore.hpp::legacyGray`, document-filter mode 7; BGR-to-gray after the shared iOS RGBA conversion |

Brightness compatibility details: the Android JNI path drops RGBA alpha with color code 1, then the histogram code applies color code 6 to the resulting RGB matrix. The iOS port preserves that weighting by applying `COLOR_RGB2GRAY` to its BGR thumbnail. Histogram searches begin at bin 1; the upper cutoff is the last CDF bin at or below 95%. The lower-interval check occurs before multiplying the lower cutoff by 0.75. The pixel worker uses a double-precision scale of 255 and truncates the result to a byte.

Regression fixtures cover independently calculated tone-map values, constant black/mid-gray/white fields, RGB histogram compatibility, primary-color grayscale weights, neutral contrast preservation, source immutability, non-contiguous row strides and shadow-correction ordering. These checks validate the portable implementation against the recovered rules; they are not a runtime comparison with the original Android library. Device output comparison remains pending.

An additional [Ghidra emulation script](EmulateLegacyBrightness.java) verifies the selected original ARM64 pixel worker's instructions. It checks the artifact SHA-256 and image base, models a single OpenMP worker and TLS canary, then checks four endpoint intervals, 9,216 channel samples and non-contiguous row padding. The observed run completed 307,624 emulated instructions with all samples matching. This covers the pixel worker, not the full histogram/JNI pipeline or an Android application run. On an already analyzed Ghidra project, run `EmulateLegacyBrightness.java` with one argument: the output report path.

## Remaining enhanced / soft paths

`0x001dd3e0` JNI `EnhanceImg` calls implementation `0x001e3380` with a 600 px analysis size, softening 0 or 0.3, gamma 2.5, sharpening 1.5 and a final float parameter of approximately 0.95 (`0x0067df24`).

That implementation calls `GetLight` at `0x001e15a0`, selects among brightness fallback and background/reflection processing, and uses `Sharp` at `0x001e2764`. `GetLight` contains neighbor/block classification, inpainting and connected-component analysis; this path has not been reconstructed completely.

The reflection worker at `0x001e13e4` combines a truncated raw-intensity softening table with a gamma table of the clamped source/background ratio. The gamma table is `floor(pow(i / 255, 2.5) * 253 * (1 - softening))`; the raw table is `floor(i * softening)`. A zero background value has a separate branch and must not be replaced with an assumed generic division rule. `Sharp` uses a 3×3 Gaussian blur before its own pixel worker.

Enhanced and soft-enhanced modes therefore still use the documented Core Image approximations. The partial findings above do not establish full filter parity.
