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
 *
 */

package org.freescan.app.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Repairs entire QR polygons, including their light modules, without filtering the rest of the page. */
object QrRegionRepair {
    suspend fun erase(bitmap: Bitmap, regions: List<WatermarkRegion>) {
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888)
        for (region in regions) {
            currentCoroutineContext().ensureActive()
            repairRegion(bitmap, region, regions)
        }
    }

    private fun repairRegion(bitmap: Bitmap, region: WatermarkRegion, all: List<WatermarkRegion>) {
        val normalized = region.bounds
        val bounds = RectF(
            normalized.left * bitmap.width, normalized.top * bitmap.height,
            normalized.right * bitmap.width, normalized.bottom * bitmap.height,
        )
        if (bounds.width() < 1 || bounds.height() < 1) return
        val edge = min(bounds.width(), bounds.height())
        val expansion = if (region.kind == WatermarkKind.QR) (edge * 0.015f).coerceIn(2f, 10f) else 1f
        val padding = max(24f, edge * 0.18f)
        val patch = Rect(
            floor(bounds.left - padding).toInt().coerceAtLeast(0),
            floor(bounds.top - padding).toInt().coerceAtLeast(0),
            ceil(bounds.right + padding).toInt().coerceAtMost(bitmap.width),
            ceil(bounds.bottom + padding).toInt().coerceAtMost(bitmap.height),
        )
        if (patch.isEmpty) return
        // Only the replacement background is resampled. Original text outside the mask is untouched.
        val scale = min(1f, 1024f / max(patch.width(), patch.height()))
        val width = max(1, (patch.width() * scale).roundToInt())
        val height = max(1, (patch.height() * scale).roundToInt())
        val sample = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rgba = Mat()
        val bgr = Mat()
        val mask = Mat.zeros(height, width, CvType.CV_8UC1)
        val repaired = Mat()
        val outputRgba = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        try {
            Canvas(sample).drawBitmap(bitmap, patch, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            for (other in all) {
                val quad = MatOfPoint(*other.corners.map {
                    Point(
                        ((it.x * bitmap.width - patch.left) * width / patch.width()).toDouble(),
                        ((it.y * bitmap.height - patch.top) * height / patch.height()).toDouble(),
                    )
                }.toTypedArray())
                try { Imgproc.fillConvexPoly(mask, quad, Scalar(255.0)) } finally { quad.release() }
            }
            val iterations = max(2, ceil(expansion * scale).toInt() + 1)
            Imgproc.dilate(mask, mask, kernel, Point(-1.0, -1.0), iterations)
            val pixels = IntArray(width * height)
            sample.getPixels(pixels, 0, width, 0, 0, width, height)
            val excluded = ByteArray(pixels.size)
            mask.get(0, 0, excluded)
            val background = paperBackground(pixels, excluded, width, height)
            if (background != null) {
                for (y in 0 until height) for (x in 0 until width) {
                    val u = x.toDouble() / max(1, width - 1)
                    val v = y.toDouble() / max(1, height - 1)
                    fun channel(index: Int) = (background[index][0] + background[index][1] * u +
                        background[index][2] * v).roundToInt().coerceIn(0, 255)
                    pixels[y * width + x] = Color.rgb(channel(0), channel(1), channel(2))
                }
                sample.setPixels(pixels, 0, width, 0, 0, width, height)
            } else {
                Utils.bitmapToMat(sample, rgba)
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                Photo.inpaint(bgr, mask, repaired, 5.0, Photo.INPAINT_TELEA)
                Imgproc.cvtColor(repaired, outputRgba, Imgproc.COLOR_BGR2RGBA)
                Utils.matToBitmap(outputRgba, sample)
            }
            val path = Path().apply {
                region.corners.forEachIndexed { index, point ->
                    if (index == 0) moveTo(point.x * bitmap.width, point.y * bitmap.height)
                    else lineTo(point.x * bitmap.width, point.y * bitmap.height)
                }
                close()
            }
            val expanded = Path()
            Paint().apply {
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = expansion * 2
                strokeJoin = Paint.Join.ROUND
            }.getFillPath(path, expanded)
            val canvas = Canvas(bitmap)
            val save = canvas.save()
            try {
                canvas.clipPath(expanded)
                canvas.drawBitmap(sample, null, patch, Paint(Paint.FILTER_BITMAP_FLAG))
            } finally { canvas.restoreToCount(save) }
        } finally {
            sample.recycle()
            rgba.release()
            bgr.release()
            mask.release()
            repaired.release()
            outputRgba.release()
            kernel.release()
        }
    }

    private data class Sample(val x: Double, val y: Double, val color: Int)

    /** Fit a smooth RGB plane to local bright paper samples, rejecting nearby printed ink. */
    private fun paperBackground(pixels: IntArray, mask: ByteArray, width: Int, height: Int): Array<DoubleArray>? {
        val samples = mutableListOf<Sample>()
        for (gy in 0 until 8) for (gx in 0 until 8) {
            val cell = mutableListOf<Sample>()
            val x0 = gx * width / 8
            val x1 = (gx + 1) * width / 8
            val y0 = gy * height / 8
            val y1 = (gy + 1) * height / 8
            for (y in y0 until y1 step max(1, (y1 - y0) / 10)) {
                for (x in x0 until x1 step max(1, (x1 - x0) / 10)) {
                    val index = y * width + x
                    if (mask[index].toInt() == 0 && Color.alpha(pixels[index]) >= 240) {
                        cell.add(Sample(x.toDouble() / max(1, width - 1),
                            y.toDouble() / max(1, height - 1), pixels[index]))
                    }
                }
            }
            if (cell.size >= 4) {
                cell.sortBy { luma(it.color) }
                samples.add(cell[(cell.size * 0.75).toInt()])
            }
        }
        // There must be surrounding pixels to estimate a replacement, not a whole-page selection.
        require(samples.size >= 3) { "选区周围没有足够的背景，请缩小选区后重试" }
        val median = samples.map { luma(it.color) }.sorted()[samples.size / 2]
        if (median < 110 || samples.count { colorSpread(it.color) > 65 } > samples.size / 3) return null
        val coefficients = fitPlane(samples) ?: return null
        val residual = samples.sumOf { sample ->
            val observed = doubleArrayOf(Color.red(sample.color).toDouble(),
                Color.green(sample.color).toDouble(), Color.blue(sample.color).toDouble())
            (0..2).sumOf { c -> abs(observed[c] - coefficients[c][0] -
                coefficients[c][1] * sample.x - coefficients[c][2] * sample.y) } / 3
        } / samples.size
        return coefficients.takeIf { residual < 18 }
    }

    private fun fitPlane(samples: List<Sample>): Array<DoubleArray>? {
        val matrix = Array(3) { DoubleArray(6) }
        for (sample in samples) {
            val v = doubleArrayOf(1.0, sample.x, sample.y)
            val colors = intArrayOf(Color.red(sample.color), Color.green(sample.color), Color.blue(sample.color))
            for (r in 0..2) {
                for (c in 0..2) matrix[r][c] += v[r] * v[c]
                for (c in 0..2) matrix[r][c + 3] += v[r] * colors[c]
            }
        }
        for (column in 0..2) {
            val pivot = (column..2).maxBy { abs(matrix[it][column]) }
            if (abs(matrix[pivot][column]) < 1e-8) return null
            val swap = matrix[column]
            matrix[column] = matrix[pivot]
            matrix[pivot] = swap
            val divisor = matrix[column][column]
            for (j in column..5) matrix[column][j] /= divisor
            for (row in 0..2) if (row != column) {
                val factor = matrix[row][column]
                for (j in column..5) matrix[row][j] -= factor * matrix[column][j]
            }
        }
        return Array(3) { channel -> DoubleArray(3) { coefficient -> matrix[coefficient][channel + 3] } }
    }

    private fun luma(color: Int) = (Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) / 256
    private fun colorSpread(color: Int) = maxOf(Color.red(color), Color.green(color), Color.blue(color)) -
        minOf(Color.red(color), Color.green(color), Color.blue(color))
}
