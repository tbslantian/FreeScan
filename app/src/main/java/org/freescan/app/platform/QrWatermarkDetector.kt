package org.freescan.app.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvException
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.GraphicalCodeDetector
import org.opencv.objdetect.QRCodeDetector
import org.opencv.objdetect.QRCodeDetectorAruco
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Normalized, orientation-corrected image coordinates, also used by the selection screen. */
data class WatermarkPoint(val x: Float, val y: Float)

enum class WatermarkKind { QR, QR_UNVERIFIED, QUARK_FOOTER, MANUAL }

data class WatermarkRegion(
    val corners: List<WatermarkPoint>,
    val kind: WatermarkKind = WatermarkKind.QR,
) {
    val bounds: RectF get() = RectF(
        corners.minOf { it.x }, corners.minOf { it.y },
        corners.maxOf { it.x }, corners.maxOf { it.y },
    )

    val atPageEdge: Boolean get() = bounds.let {
        it.left < 0.12f || it.top < 0.12f || it.right > 0.88f || it.bottom > 0.88f
    }

    companion object {
        fun rectangle(rect: RectF, kind: WatermarkKind = WatermarkKind.MANUAL) = WatermarkRegion(
            listOf(
                WatermarkPoint(rect.left, rect.top), WatermarkPoint(rect.right, rect.top),
                WatermarkPoint(rect.right, rect.bottom), WatermarkPoint(rect.left, rect.bottom),
            ), kind,
        )
    }
}

/** A geometric candidate is never erased automatically until its QR payload is decoded. */
class QrWatermarkDetector {
    private val standard = QRCodeDetector()
    private val alternate = QRCodeDetectorAruco()

    suspend fun detect(bitmap: Bitmap): List<WatermarkRegion> {
        val regions = mutableListOf<WatermarkRegion>()
        // An overview finds large codes; overlapping original-resolution tiles retain small codes.
        detectView(bitmap, 0, 0, bitmap.width, bitmap.height, regions)
        if (max(bitmap.width, bitmap.height) > TILE_SIZE) {
            for (top in tileStarts(bitmap.height)) {
                for (left in tileStarts(bitmap.width)) {
                    currentCoroutineContext().ensureActive()
                    detectView(
                        bitmap, left, top, min(TILE_SIZE, bitmap.width - left),
                        min(TILE_SIZE, bitmap.height - top), regions,
                    )
                }
            }
        }

        // Keep the original automatic Quark-footer compatibility path, including its logo/text.
        // It is deliberately independent of successful QR decoding or detection.
        detectQuarkFooter(bitmap)?.let { footer ->
            regions.removeAll { overlapFraction(it.bounds, footer.bounds) > 0.7f }
            regions.add(footer)
        }
        Log.i(TAG, "Detected ${regions.count { it.kind == WatermarkKind.QR }} verified QR regions, " +
            "${regions.count { it.kind == WatermarkKind.QR_UNVERIFIED }} unverified candidates, " +
            "${regions.count { it.kind == WatermarkKind.QUARK_FOOTER }} Quark footers")
        return regions
    }

    private suspend fun detectView(
        source: Bitmap, left: Int, top: Int, width: Int, height: Int,
        regions: MutableList<WatermarkRegion>,
    ) {
        currentCoroutineContext().ensureActive()
        if (min(width, height) < 24) return
        val crop = Bitmap.createBitmap(source, left, top, width, height)
        val scale = min(1f, OVERVIEW_EDGE.toFloat() / max(width, height))
        val view = if (scale < 1f) Bitmap.createScaledBitmap(
            crop, max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()), true,
        ) else crop
        val rgba = Mat()
        val gray = Mat()
        val enhanced = Mat()
        try {
            Utils.bitmapToMat(view, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            suspend fun collect(input: Mat): Boolean {
                var verified = false
                for (detector in listOf(standard, alternate)) {
                    currentCoroutineContext().ensureActive()
                    for (quad in detectQuads(detector, input)) {
                        val readable = canDecode(detector, input, quad) ||
                            (input !== gray && canDecode(standard, gray, quad))
                        val region = WatermarkRegion(quad.map { point ->
                            WatermarkPoint(
                                ((left + point.x * width / view.width) / source.width).coerceIn(0f, 1f),
                                ((top + point.y * height / view.height) / source.height).coerceIn(0f, 1f),
                            )
                        }, if (readable) WatermarkKind.QR else WatermarkKind.QR_UNVERIFIED)
                        val safeSize = max(region.bounds.width() * source.width,
                            region.bounds.height() * source.height) <=
                            min(source.width, source.height) * MAX_AUTO_QR_EDGE_FRACTION
                        val candidate = if (readable && safeSize) region else
                            region.copy(kind = WatermarkKind.QR_UNVERIFIED)
                        if (candidate.kind == WatermarkKind.QR) verified = true
                        val existing = regions.indexOfFirst { sameRegion(it.bounds, candidate.bounds) }
                        if (existing < 0) regions.add(candidate)
                        else if (regions[existing].kind == WatermarkKind.QR_UNVERIFIED &&
                            candidate.kind == WatermarkKind.QR) regions[existing] = candidate
                    }
                }
                return verified
            }
            if (!collect(gray)) {
                val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                try { clahe.apply(gray, enhanced) } finally { clahe.collectGarbage() }
                if (!collect(enhanced)) {
                    Core.bitwise_not(gray, enhanced)
                    if (!collect(enhanced)) {
                        Imgproc.adaptiveThreshold(gray, enhanced, 255.0,
                            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, 5.0)
                        collect(enhanced)
                    }
                }
            }
        } finally {
            rgba.release()
            gray.release()
            enhanced.release()
            if (view !== crop) view.recycle()
            if (crop !== source) crop.recycle()
        }
    }

    private fun canDecode(
        detector: GraphicalCodeDetector, image: Mat, quad: List<WatermarkPoint>,
    ): Boolean {
        val points = Mat(1, 4, CvType.CV_32FC2)
        try {
            points.put(0, 0, quad.flatMap { listOf(it.x, it.y) }.toFloatArray())
            return try {
                detector.decode(image, points).isNotEmpty() ||
                    detector.decodeBytes(image, points).isNotEmpty()
            } catch (_: CvException) {
                false
            }
        } finally {
            points.release()
        }
    }

    private fun detectQuads(detector: GraphicalCodeDetector, image: Mat): List<List<WatermarkPoint>> {
        val points = Mat()
        try {
            if (!detector.detectMulti(image, points)) {
                if (!detector.detect(image, points)) return emptyList()
            }
            if (points.empty()) return emptyList()
            val coordinates = FloatArray((points.total() * points.channels()).toInt())
            points.get(0, 0, coordinates)
            return coordinates.toList().chunked(8).mapNotNull { values ->
                if (values.size != 8 || values.any { !it.isFinite() }) return@mapNotNull null
                val quad = values.chunked(2).map { WatermarkPoint(it[0], it[1]) }
                val area = abs(quad.indices.sumOf { i ->
                    val a = quad[i]
                    val b = quad[(i + 1) % 4]
                    (a.x * b.y - a.y * b.x).toDouble()
                }) / 2
                val bounds = WatermarkRegion(quad).bounds
                if (area < 64 || min(bounds.width(), bounds.height()) < 8 ||
                    max(bounds.width(), bounds.height()) > min(bounds.width(), bounds.height()) * 5 ||
                    quad.any { it.x < -2 || it.y < -2 || it.x > image.cols() + 2 || it.y > image.rows() + 2 }
                ) null else quad
            }
        } catch (error: CvException) {
            // A malformed detection must not disable the other detector or Quark compatibility.
            Log.w(TAG, "QR detection pass failed", error)
            return emptyList()
        } finally {
            points.release()
        }
    }

    private fun tileStarts(length: Int): List<Int> {
        if (length <= TILE_SIZE) return listOf(0)
        val starts = (0..(length - TILE_SIZE) step TILE_STEP).toMutableList()
        if (starts.last() != length - TILE_SIZE) starts.add(length - TILE_SIZE)
        return starts
    }

    private fun sameRegion(a: RectF, b: RectF): Boolean =
        overlapFraction(a, b) > 0.65f && overlapFraction(b, a) > 0.65f

    private fun overlapFraction(a: RectF, b: RectF): Float {
        val intersection = max(0f, min(a.right, b.right) - max(a.left, b.left)) *
            max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        return intersection / max(0.000001f, a.width() * a.height())
    }

    private fun detectQuarkFooter(bitmap: Bitmap): WatermarkRegion? {
        val left = (bitmap.width * 0.62f).toInt()
        val right = (bitmap.width * 0.99f).toInt()
        val top = (bitmap.height * 0.90f).toInt()
        val row = IntArray(bitmap.width)
        var bluePixels = 0
        for (y in top until bitmap.height step 2) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in left until right step 2) {
                val color = row[x]
                val blue = Color.blue(color)
                if (blue >= 90 && blue - Color.red(color) >= 28 && blue - Color.green(color) >= 4) {
                    if (++bluePixels >= 12) return WatermarkRegion.rectangle(
                        RectF(0.64f, 0.90f, 1f, 1f), WatermarkKind.QUARK_FOOTER,
                    )
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "FreeScanWatermark"
        private const val OVERVIEW_EDGE = 1600
        private const val TILE_SIZE = 1280
        private const val TILE_STEP = 1024
        // A page-spanning candidate is not a typical export watermark; allow manual review.
        private const val MAX_AUTO_QR_EDGE_FRACTION = 0.30f
    }
}
