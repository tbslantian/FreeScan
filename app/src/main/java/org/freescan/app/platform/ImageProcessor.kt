/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.freescan.app.platform

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.freescan.app.BuildConfig
import org.freescan.app.data.ImageTransformations
import org.freescan.app.domain.LocalNativeFilterProcessor
import org.freescan.app.domain.CapturedPage
import org.freescan.app.domain.EncodedImage
import org.freescan.app.domain.ExportQuality
import org.freescan.app.domain.Jpeg
import org.freescan.app.domain.PageMetadata
import org.freescan.app.domain.Png
import org.freescan.app.domain.Rotation
import org.freescan.app.domain.isLocalNativeFilter
import org.freescan.app.ui.screens.settings.DefaultColorMode
import org.freescan.imageprocessing.ColorMode
import org.freescan.imageprocessing.ImageSize
import org.freescan.imageprocessing.Mask
import org.freescan.imageprocessing.OpticalMeasures
import org.freescan.imageprocessing.Point
import org.freescan.imageprocessing.Quad
import org.freescan.imageprocessing.autoColorMode
import org.freescan.imageprocessing.createQuad
import org.freescan.imageprocessing.enhanceCapturedImage
import org.freescan.imageprocessing.extractDocument
import org.freescan.imageprocessing.resizeForMaxPixels
import org.freescan.imageprocessing.resizeForMaxDimension
import org.freescan.imageprocessing.rotate
import org.freescan.imageprocessing.scaledTo
import org.opencv.android.Utils
import org.opencv.core.CvException
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.min

class ImageProcessor(private val thumbnailSizePx: Int) : ImageTransformations {

    override fun rotate(input: EncodedImage, rotationDegrees: Int): EncodedImage {
        return transform(input, ExportQuality.BALANCED.jpegQuality) {
            rotate(it, rotationDegrees)
        }
    }

    override fun resizeToThumbnail(input: EncodedImage): EncodedImage {
        val maxSize = thumbnailSizePx.toFloat()
        return transform(input, 85) { src ->
            val ratio = min(maxSize / src.width(), maxSize / src.height())
            val newW = (src.width() * ratio).toDouble()
            val newH = (src.height() * ratio).toDouble()
            val scaled = Mat()
            try {
                Imgproc.resize(src, scaled, Size(newW, newH))
            } catch (e: CvException) {
                val msg = "Resize failed. src=${src.width()}x${src.height()} dst=${newW}x${newH}"
                throw IllegalStateException(msg, e)
            }
            scaled
        }
    }

    private fun transform(
        inputImage: EncodedImage,
        jpegQuality: Int,
        transform: (Mat) -> Mat,
    ): EncodedImage {
        val input = inputImage.toMat()
        var output: Mat? = null
        try {
            output = transform.invoke(input)
            return when (inputImage) {
                is Jpeg -> Jpeg.fromMat(output, jpegQuality)
                is Png -> Png.fromMat(output)
            }
        } finally {
            input.release()
            output?.release()
        }
    }

    override fun process(
        source: Jpeg,
        metadata: PageMetadata,
        colorMode: ColorMode,
    ): EncodedImage = process(source, metadata, colorMode, removeShadows = true)

    override fun process(
        source: Jpeg,
        metadata: PageMetadata,
        colorMode: ColorMode,
        removeShadows: Boolean,
    ): EncodedImage = processedImage(
        source, metadata, metadata.baseRotation, colorMode, ExportQuality.BALANCED,
        removeShadows = removeShadows,
    )
}

fun processedImage(
    source: Jpeg,
    metadata: PageMetadata,
    rotation: Rotation,
    colorMode: ColorMode,
    exportQuality: ExportQuality,
    maxDimension: Int? = metadata.scanMaxDimension,
    removeShadows: Boolean = true,
): EncodedImage {
    if (colorMode.isLocalNativeFilter()) {
        val fallbackMode = colorMode.nativeFallbackMode()
        if (!BuildConfig.SCANLAB_ENABLED) {
            return processedImage(
                source, metadata, rotation, fallbackMode, exportQuality, maxDimension, removeShadows,
            )
        }
        var page: Mat? = null
        return try {
            page = processedPageMat(
                source, metadata, rotation, colorMode, exportQuality, maxDimension, removeShadows,
            )
            encodeLocalNativeFilter(page, colorMode, exportQuality.jpegQuality)
        } catch (error: Exception) {
            Log.e("LocalScanTest", "Local native filter $colorMode failed; using the FreeScan filter fallback", error)
            processedImage(
                source, metadata, rotation, fallbackMode, exportQuality, maxDimension, removeShadows,
            )
        } catch (error: LinkageError) {
            Log.e("LocalScanTest", "Native filter library unavailable; using the FreeScan filter fallback", error)
            processedImage(
                source, metadata, rotation, fallbackMode, exportQuality, maxDimension, removeShadows,
            )
        } finally {
            page?.release()
        }
    }
    val page = processedPageMat(
        source, metadata, rotation, colorMode, exportQuality, maxDimension, removeShadows,
    )
    try {
        return encodeImage(page, colorMode, exportQuality)
    } finally {
        page.release()
    }
}

private fun processedPageMat(
    source: Jpeg,
    metadata: PageMetadata,
    rotation: Rotation,
    colorMode: ColorMode,
    exportQuality: ExportQuality,
    maxDimension: Int? = metadata.scanMaxDimension,
    removeShadows: Boolean = true,
): Mat {
    var sourceMat: Mat? = null
    try {
        sourceMat = source.toMat()
        val quad = metadata.normalizedQuad.scaledTo(1, 1, sourceMat.width(), sourceMat.height())
        return extractDocument(
            sourceMat, quad, rotation.degrees, colorMode,
            if (maxDimension == null) exportQuality.maxPixels(colorMode) else 0L,
            metadata.opticalMeasures, maxDimension, removeShadows = removeShadows,
        )
    } finally {
        sourceMat?.release()
    }
}

fun extractDocumentFromBitmap(
    source: Bitmap,
    quadInMask: Quad?,
    rotationDegrees: Int,
    mask: Mask?,
    viewModelScope: CoroutineScope,
    defaultColorMode: DefaultColorMode = DefaultColorMode.AUTO,
    opticalMeasures: OpticalMeasures?,
    maxDimension: Int? = null,
    jpegQualityOverride: Int? = null,
    deskewText: Boolean = true,
    removeShadows: Boolean = true,
    alreadyRectified: Boolean = false,
): CapturedPage {
    val exportQuality = ExportQuality.BALANCED
    var colorMode = ColorMode.COLOR
    var autoColorMode = colorMode
    var normalizedQuad = createQuad(listOf(
        Point(0.0, 0.0), Point(0.0, 1.0), Point(1.0, 1.0), Point(1.0, 0.0))
    )
    var page: Mat

    val rgba = Mat()
    Utils.bitmapToMat(source, rgba)
    val bgr = Mat()
    Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
    rgba.release()

    if (alreadyRectified) {
        // The local scanner already detected the page and applied perspective correction.
        // Re-running document detection/warping here would add latency and can crop twice.
        autoColorMode = detectFullFrameColorMode(bgr, normalizedQuad)
        colorMode = (defaultColorMode.colorMode ?: autoColorMode).availableMode()
        val resized = maxDimension?.let { resizeForMaxDimension(bgr, it) }
            ?: resizeForMaxPixels(bgr, exportQuality.maxPixels(colorMode).toDouble())
        page = try {
            // The model has already rectified the page, so only run the chosen image enhancement.
            enhanceCapturedImage(
                resized,
                colorMode,
                if (maxDimension != null) 0L else exportQuality.maxPixels(colorMode),
                removeShadows,
            )
        } finally {
            resized.release()
        }
    } else if (mask == null || quadInMask == null) {
        // Keep the image unwarped, but still apply the selected filter and shadow correction.
        autoColorMode = detectFullFrameColorMode(bgr, normalizedQuad)
        colorMode = (defaultColorMode.colorMode ?: autoColorMode).availableMode()
        val resized = maxDimension?.let { resizeForMaxDimension(bgr, it) }
            ?: resizeForMaxPixels(bgr, exportQuality.maxPixels(colorMode).toDouble())
        val oriented = rotate(resized, rotationDegrees)
        resized.release()
        page = try {
            enhanceCapturedImage(
                oriented,
                colorMode,
                if (maxDimension != null) 0L else exportQuality.maxPixels(colorMode),
                removeShadows,
            )
        } finally {
            oriented.release()
        }
    } else {
        val quad = quadInMask.scaledTo(mask.width, mask.height, source.width, source.height)
        normalizedQuad = quad.scaledTo(source.width, source.height, 1, 1)
        autoColorMode = autoColorMode(bgr, mask, quad)
        colorMode = (defaultColorMode.colorMode ?: autoColorMode).availableMode()
        page = extractDocument(
            bgr, quad, rotationDegrees, colorMode,
            if (maxDimension == null) exportQuality.maxPixels(colorMode) else 0L,
            opticalMeasures, maxDimension, deskewText, removeShadows)
    }

    val encodedImage = if (BuildConfig.SCANLAB_ENABLED && colorMode.isLocalNativeFilter()) {
        try {
            encodeLocalNativeFilter(page, colorMode, jpegQualityOverride ?: exportQuality.jpegQuality)
        } catch (error: Exception) {
            Log.e("LocalScanTest", "Native filter $colorMode failed during capture; using the processed page", error)
            encodeImage(page, colorMode.nativeFallbackMode(), exportQuality, jpegQualityOverride)
        } catch (error: LinkageError) {
            Log.e("LocalScanTest", "Native filter library unavailable during capture; using the processed page", error)
            encodeImage(page, colorMode.nativeFallbackMode(), exportQuality, jpegQualityOverride)
        }
    } else {
        encodeImage(page, colorMode, exportQuality, jpegQualityOverride)
    }

    bgr.release()
    page.release()

    val baseRotation = Rotation.fromDegrees(rotationDegrees)
    val sourceSize = ImageSize(source.width, source.height)
    val metadata =
        PageMetadata(
            normalizedQuad, baseRotation, autoColorMode, sourceSize, opticalMeasures,
            scanMaxDimension = maxDimension
        )
    val sourceJpegDeferred = viewModelScope.async(Dispatchers.IO) {
        compressSource(source)
    }
    return CapturedPage(encodedImage, sourceJpegDeferred, metadata, colorMode, removeShadows)
}

private fun ColorMode.nativeFallbackMode(): ColorMode = when (this) {
    ColorMode.LOCAL_GRAYSCALE -> ColorMode.GRAYSCALE
    else -> ColorMode.COLOR
}

private fun ColorMode.availableMode(): ColorMode =
    when {
        this == ColorMode.RED_STAMP -> ColorMode.COLOR
        BuildConfig.SCANLAB_ENABLED || !isLocalNativeFilter() -> this
        else -> nativeFallbackMode()
    }

private fun encodeLocalNativeFilter(page: Mat, colorMode: ColorMode, jpegQuality: Int): Jpeg {
    // OpenCV processing keeps three-channel pages in BGR order; Android Bitmap expects RGBA.
    val rgba = Mat()
    val bitmap = Bitmap.createBitmap(page.cols(), page.rows(), Bitmap.Config.ARGB_8888)
    try {
        Imgproc.cvtColor(page, rgba, Imgproc.COLOR_BGR2RGBA)
        Utils.matToBitmap(rgba, bitmap)
        LocalNativeFilterProcessor.apply(bitmap, colorMode)
        return LocalNativeFilterProcessor.encode(bitmap, jpegQuality)
    } finally {
        rgba.release()
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

private fun detectFullFrameColorMode(image: Mat, normalizedQuad: Quad): ColorMode {
    val fullFrameMask = object : Mask {
        override val width = image.cols()
        override val height = image.rows()
        override fun toMat(): Mat = Mat(height, width, CvType.CV_8UC1, Scalar(255.0))
    }
    val fullFrameQuad = normalizedQuad.scaledTo(1, 1, image.cols(), image.rows())
    return autoColorMode(image, fullFrameMask, fullFrameQuad)
}

private fun encodeImage(
    image: Mat,
    colorMode: ColorMode,
    quality: ExportQuality,
    jpegQualityOverride: Int? = null,
): EncodedImage {
    return if (colorMode == ColorMode.BLACK_AND_WHITE)
        Png.fromMat(image)
    else
        Jpeg.fromMat(image, jpegQualityOverride ?: quality.jpegQuality)
}

private fun compressSource(source: Bitmap): Jpeg {
    val rgba = Mat()
    Utils.bitmapToMat(source, rgba)
    val bgr = Mat()
    Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
    rgba.release()
    return try {
        Jpeg.fromMat(bgr, 90)
    } finally {
        bgr.release()
    }
}
