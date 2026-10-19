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
import android.graphics.Canvas
import android.graphics.PointF
import org.opencv.core.Core
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

data class InpaintStroke(
    val points: List<PointF>,
    val widthPx: Float,
)

/** Repairs the painted region and returns a new bitmap, leaving [source] unchanged. */
fun inpaintBitmap(source: Bitmap, strokes: List<InpaintStroke>): Bitmap {
    require(strokes.isNotEmpty()) { "Paint over an area to erase first." }
    val allPoints = strokes.flatMap { it.points }
    require(allPoints.isNotEmpty()) { "Paint over an area to erase first." }

    val workingSource = if (source.config == Bitmap.Config.ARGB_8888) {
        source
    } else {
        source.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Could not prepare the image for repair.")
    }

    val maxStrokeWidth = strokes.maxOf { it.widthPx }.coerceAtLeast(1f)
    val inpaintRadius = (maxStrokeWidth * 0.22f).coerceIn(3f, 12f)
    val padding = max(48, ceil(maxStrokeWidth + inpaintRadius * 4).toInt())
    val left = (floor(allPoints.minOf { it.x }).toInt() - padding).coerceAtLeast(0)
    val top = (floor(allPoints.minOf { it.y }).toInt() - padding).coerceAtLeast(0)
    val right = (ceil(allPoints.maxOf { it.x }).toInt() + padding + 1).coerceAtMost(workingSource.width)
    val bottom = (ceil(allPoints.maxOf { it.y }).toInt() + padding + 1).coerceAtMost(workingSource.height)
    require(right > left && bottom > top) { "The painted area is outside the image." }

    val patchBitmap = Bitmap.createBitmap(workingSource, left, top, right - left, bottom - top)
    val rgba = Mat()
    val bgr = Mat()
    val mask = Mat.zeros(patchBitmap.height, patchBitmap.width, CvType.CV_8UC1)
    val dilatedMask = Mat()
    val inpainted = Mat()
    val outputRgba = Mat()
    var kernel: Mat? = null
    val repairedPatch = Bitmap.createBitmap(patchBitmap.width, patchBitmap.height, Bitmap.Config.ARGB_8888)
    try {
        Utils.bitmapToMat(patchBitmap, rgba)
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

        strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            val thickness = stroke.widthPx.roundToInt().coerceIn(1, max(patchBitmap.width, patchBitmap.height))
            val localPoints = stroke.points.map { point ->
                Point((point.x - left).toDouble(), (point.y - top).toDouble())
            }
            if (localPoints.size == 1) {
                Imgproc.circle(
                    mask,
                    localPoints.first(),
                    max(1, thickness / 2),
                    Scalar(255.0),
                    -1,
                    Imgproc.LINE_AA,
                )
            } else {
                localPoints.zipWithNext().forEach { (start, end) ->
                    Imgproc.line(mask, start, end, Scalar(255.0), thickness, Imgproc.LINE_AA)
                }
            }
        }

        kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.dilate(mask, dilatedMask, kernel)
        Photo.inpaint(bgr, dilatedMask, inpainted, inpaintRadius.toDouble(), Photo.INPAINT_TELEA)
        Imgproc.cvtColor(inpainted, outputRgba, Imgproc.COLOR_BGR2RGBA)
        Utils.matToBitmap(outputRgba, repairedPatch)

        val output = workingSource.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("Could not create the repaired image.")
        Canvas(output).drawBitmap(repairedPatch, left.toFloat(), top.toFloat(), null)
        return output
    } finally {
        rgba.release()
        bgr.release()
        mask.release()
        dilatedMask.release()
        inpainted.release()
        outputRgba.release()
        kernel?.release()
        repairedPatch.recycle()
        if (patchBitmap !== workingSource) patchBitmap.recycle()
        if (workingSource !== source) workingSource.recycle()
    }
}

/**
 * Removes colored pen marks with a sensitive HSV mask and local inpainting.
 * This is a color heuristic; it does not identify black handwriting or distinguish
 * colored handwriting from printed colored content.
 */
fun removeColoredInkBitmap(source: Bitmap): Bitmap? {
    val workingSource = if (source.config == Bitmap.Config.ARGB_8888) {
        source
    } else {
        source.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Could not prepare the image for repair.")
    }

    val rgba = Mat()
    val rgb = Mat()
    val hsv = Mat()
    val mask = Mat()
    val nonZero = Mat()
    val maskPoints = MatOfPoint()
    val patchRgba = Mat()
    val patchBgr = Mat()
    val patchMask = Mat()
    val closedMask = Mat()
    val expandedMask = Mat()
    val inpainted = Mat()
    val inpaintedRgba = Mat()
    var maskKernel: Mat? = null
    var sourcePatchBitmap: Bitmap? = null
    var patchBitmap: Bitmap? = null
    try {
        Utils.bitmapToMat(workingSource, rgba)
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)

        // Use a lower saturation floor and include highlights so pale/anti-aliased
        // colored strokes are not left behind on bright paper.
        Core.inRange(hsv, Scalar(0.0, 30.0, 20.0), Scalar(180.0, 255.0, 255.0), mask)
        val coloredPixels = Core.countNonZero(mask)
        if (coloredPixels == 0 || coloredPixels.toLong() * 10 > workingSource.width.toLong() * workingSource.height) {
            return null
        }

        Core.findNonZero(mask, nonZero)
        nonZero.convertTo(maskPoints, CvType.CV_32SC2)
        val bounds = Imgproc.boundingRect(maskPoints)
        val padding = 32
        val left = (bounds.x - padding).coerceAtLeast(0)
        val top = (bounds.y - padding).coerceAtLeast(0)
        val right = (bounds.x + bounds.width + padding).coerceAtMost(workingSource.width)
        val bottom = (bounds.y + bounds.height + padding).coerceAtMost(workingSource.height)
        if (right <= left || bottom <= top) return null

        val maskRegion = mask.submat(top, bottom, left, right)
        maskRegion.copyTo(patchMask)
        maskRegion.release()
        rgba.release()
        rgb.release()
        hsv.release()
        mask.release()
        nonZero.release()
        maskPoints.release()

        sourcePatchBitmap = Bitmap.createBitmap(workingSource, left, top, right - left, bottom - top)
        Utils.bitmapToMat(sourcePatchBitmap, patchRgba)
        Imgproc.cvtColor(patchRgba, patchBgr, Imgproc.COLOR_RGBA2BGR)
        val maxDimension = max(workingSource.width, workingSource.height)
        val expansionSize = ((maxDimension / 1200f).roundToInt() * 2 + 1).coerceIn(5, 9)
        val inpaintRadius = (maxDimension / 850.0).coerceIn(4.0, 9.0)
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        maskKernel = closeKernel
        val dilationKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            Size(expansionSize.toDouble(), expansionSize.toDouble()),
        )
        try {
            // Close tiny breaks in pen strokes, then cover their anti-aliased edges.
            Imgproc.morphologyEx(patchMask, closedMask, Imgproc.MORPH_CLOSE, closeKernel)
            Imgproc.dilate(closedMask, expandedMask, dilationKernel)
        } finally {
            dilationKernel.release()
        }
        Photo.inpaint(patchBgr, expandedMask, inpainted, inpaintRadius, Photo.INPAINT_TELEA)
        Imgproc.cvtColor(inpainted, inpaintedRgba, Imgproc.COLOR_BGR2RGBA)

        patchBitmap = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(inpaintedRgba, patchBitmap)
        val output = workingSource.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("Could not create the repaired image.")
        Canvas(output).drawBitmap(patchBitmap, left.toFloat(), top.toFloat(), null)
        return output
    } finally {
        rgba.release()
        rgb.release()
        hsv.release()
        mask.release()
        nonZero.release()
        maskPoints.release()
        patchRgba.release()
        patchBgr.release()
        patchMask.release()
        closedMask.release()
        expandedMask.release()
        maskKernel?.release()
        inpainted.release()
        inpaintedRgba.release()
        sourcePatchBitmap?.recycle()
        patchBitmap?.recycle()
        if (workingSource !== source) workingSource.recycle()
    }
}
