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
package org.freescan.imageprocessing

import org.freescan.imageprocessing.quad.findQuadFromContourOrientation
import org.freescan.imageprocessing.quad.minAreaRect
import org.freescan.imageprocessing.quad.scoreQuadAgainstProbmap
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.roundToInt

interface Mask {
    val width: Int
    val height: Int
    fun toMat(): Mat
}

enum class Mode {
    CAPTURE, IMPORT, LIVE_ANALYSIS
}

fun detectDocumentQuad(mask: Mask, originalSize: ImageSize, mode: Mode): Quad? {
    val mat = mask.toMat()
    // Best thresholds on test dataset: {0.95=146, 0.85=39, 0.75=35, 0.90=8, 0.70=1, 0.35=1}
    val thresholds =
        if (mode != Mode.CAPTURE) listOf(0.9) else listOf(0.5, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95)
    var vertices = findQuadFromOrientationWithAdaptiveThreshold(mat, originalSize, thresholds)
        ?.map { Point(it.x, it.y) }

    if (vertices == null && mode == Mode.CAPTURE) {
        // Fallback: bounding rectangle
        val biggest = biggestContour(mat)
        if (biggest != null) {
            val polygon = biggest.toList().map { Point(it.x, it.y) }
            vertices = minAreaRect(polygon, mask.width, mask.height)
        }
    }
    val maskSize = ImageSize(mask.width, mask.height)
    return if (vertices?.size == 4 && vertices.all { isInsideImage(it, maskSize) })
        createQuad(vertices)
    else null
}

fun findQuadFromOrientationWithAdaptiveThreshold(
    maskMat: Mat, originalSize: ImageSize, thresholds: List<Double>
): List<org.opencv.core.Point>? {
    val probmapU8 = Mat()
    val probmap = maskMat
    probmap.convertTo(probmapU8, CvType.CV_8U, 255.0)
    val probmapSmooth = Mat()
    Imgproc.GaussianBlur(probmapU8, probmapSmooth, Size(3.0, 3.0), 0.0)

    var bestQuad: List<org.opencv.core.Point>? = null
    var bestScore = 0.0
    for (thr in thresholds) {
        val bin = Mat()
        Imgproc.threshold(probmapSmooth, bin, thr * 255.0, 255.0, Imgproc.THRESH_BINARY)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel)
        val quad = findQuadFromOrientation(bin, originalSize)
        if (quad != null) {
            val probFloat = Mat()
            probmap.convertTo(probFloat, CvType.CV_32F)
            val score = scoreQuadAgainstProbmap(quad, probFloat, minQuadAreaRatio = 0.02)
            if (score > bestScore) {
                bestScore = score
                bestQuad = quad
            }
        }
        bin.release()
    }

    probmapSmooth.release()
    probmapU8.release()
    return bestQuad
}

fun isInsideImage(p: Point, imageSize: ImageSize): Boolean {
    return p.x >= 0 && p.x <= imageSize.width - 1
       && p.y >= 0 && p.y <= imageSize.height - 1
}

fun findQuadFromOrientation(maskMat: Mat, originalSize: ImageSize): List<org.opencv.core.Point>? {
    val contour = biggestContour(maskMat)
    contour?:return null

    val scaleX = originalSize.width / maskMat.size().width
    val scaleY = originalSize.height / maskMat.size().height

    // The mask may have a different width/height ratio than the original image.
    // It's crucial for angles that the width/height ratio is the one of the original image.
    return findQuadFromContourOrientation(
        contour.toList().map { org.opencv.core.Point(it.x * scaleX, it.y * scaleY) }
    )?.map { org.opencv.core.Point(it.x / scaleX, it.y / scaleY) }
}

fun biggestContour(mat: Mat): MatOfPoint? {
    val refinedMask = refineMask(mat)

    val blurred = Mat()
    Imgproc.GaussianBlur(refinedMask, blurred, Size(5.0, 5.0), 0.0)

    val edges = Mat()
    Imgproc.Canny(blurred, edges, 75.0, 200.0)

    val contours = mutableListOf<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE)

    var biggest: MatOfPoint? = null
    var maxArea = 0.0

    for (contour in contours) {
        val area = abs(Imgproc.contourArea(contour))
        if (area > maxArea) {
            maxArea = area
            biggest = contour
        }
    }
    return biggest
}

/**
 * Applies morphological operations to improve a document mask.
 */
fun refineMask(original: Mat): Mat {
    // Step 0: Ensure the mask is binary (just in case)
    val binaryMask = Mat()
    Imgproc.threshold(original, binaryMask, 128.0, 255.0, Imgproc.THRESH_BINARY)

    // Step 1: Closing (fills small holes)
    val kernelClose = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
    val closed = Mat()
    Imgproc.morphologyEx(binaryMask, closed, Imgproc.MORPH_CLOSE, kernelClose)

    // Step 2: Gentle opening (removes isolated noise)
    val kernelOpen = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
    val opened = Mat()
    Imgproc.morphologyEx(closed, opened, Imgproc.MORPH_OPEN, kernelOpen)

    return opened
}

fun extractDocument(
    inputMat: Mat,
    quad: Quad,
    rotationDegrees: Int,
    colorMode: ColorMode,
    maxPixels: Long,
    opticalMeasures: OpticalMeasures? = null,
    maxDimension: Int? = null,
    deskewText: Boolean = true,
    removeShadows: Boolean = true,
): Mat {
    val imageBounds = ImageSize(inputMat.cols(), inputMat.rows())
    val edgeRefinedQuad = refineQuadToImageEdges(inputMat, quad)
    val safeQuad = Quad(
        edgeRefinedQuad.topLeft.clampTo(imageBounds),
        edgeRefinedQuad.topRight.clampTo(imageBounds),
        edgeRefinedQuad.bottomRight.clampTo(imageBounds),
        edgeRefinedQuad.bottomLeft.clampTo(imageBounds),
    )
    require(safeQuad.isConvex()) { "Document corners must form a convex quadrilateral" }

    val estimatedDimensions = estimateRealDimensions(
        safeQuad, inputMat.cols(), inputMat.rows(), opticalMeasures
    ).snapToStandardFormat()
    val (estimatedWidth, estimatedHeight) = estimatedDimensions.toPixelDimensions(safeQuad)
    val outputWidth = estimatedWidth.roundToInt().coerceAtLeast(2)
    val outputHeight = estimatedHeight.roundToInt().coerceAtLeast(2)
    val srcPoints = MatOfPoint2f(
        safeQuad.topLeft.toCv(),
        safeQuad.topRight.toCv(),
        safeQuad.bottomRight.toCv(),
        safeQuad.bottomLeft.toCv(),
    )
    val dstPoints = MatOfPoint2f(
        org.opencv.core.Point(0.0, 0.0),
        org.opencv.core.Point((outputWidth - 1).toDouble(), 0.0),
        org.opencv.core.Point((outputWidth - 1).toDouble(), (outputHeight - 1).toDouble()),
        org.opencv.core.Point(0.0, (outputHeight - 1).toDouble())
    )

    val warped = Mat()
    try {
        val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        try {
            Imgproc.warpPerspective(
                inputMat, warped, transform, Size(outputWidth.toDouble(), outputHeight.toDouble()),
                Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, org.opencv.core.Scalar(0.0)
            )
        } finally {
            transform.release()
        }

        val edgeTrimmed = trimDarkFrame(warped)
        try {
            // Warp from the full-resolution source first. Resize only to the selected output tier,
            // keeping the output dimensions while bounding the later enhancement workload.
            val working = if (maxDimension != null) {
                resizeForMaxDimension(edgeTrimmed, maxDimension)
            } else {
                resizeForMaxPixels(edgeTrimmed, maxPixels.toDouble())
            }
            try {
                val deskewed = if (deskewText && colorMode != ColorMode.ORIGINAL) deskewTextLines(working) else working.clone()
                try {
                    val enhanced = enhanceCapturedImage(
                        deskewed,
                        colorMode,
                        if (maxDimension != null) 0L else maxPixels,
                        removeShadows,
                    )
                    try {
                        return rotate(enhanced, rotationDegrees)
                    } finally {
                        enhanced.release()
                    }
                } finally {
                    deskewed.release()
                }
            } finally {
                working.release()
            }
        } finally {
            edgeTrimmed.release()
        }
    } finally {
        srcPoints.release()
        dstPoints.release()
        warped.release()
    }
}

/** Detects a dark environment/frame transition at each page edge and trims only past that transition. */
fun trimDarkFrame(image: Mat): Mat {
    if (image.empty() || image.cols() < 32 || image.rows() < 32) return image.clone()
    val gray = Mat()
    when (image.channels()) {
        4 -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGRA2GRAY)
        3 -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
        else -> image.copyTo(gray)
    }
    val pixels = ByteArray(gray.rows() * gray.cols())
    gray.get(0, 0, pixels)
    gray.release()

    fun trimCount(horizontal: Boolean, reverse: Boolean): Int {
        val dimension = if (horizontal) image.rows() else image.cols()
        val edgeLength = if (horizontal) image.cols() else image.rows()
        // This is only the maximum distance inspected. The crop amount is derived from the
        // detected dark-to-paper transition and is different for every side and every image.
        val searchLimit = minOf(96, dimension / 12).coerceAtLeast(2)
        val firstAlongEdge = edgeLength / 12
        val lastAlongEdge = edgeLength - firstAlongEdge
        val sampleStep = ((lastAlongEdge - firstAlongEdge) / 700).coerceAtLeast(1)

        fun sample(distance: Int): Pair<Int, Double> {
            val index = if (reverse) dimension - distance - 1 else distance
            val values = ArrayList<Int>()
            var dark = 0
            var count = 0
            for (along in firstAlongEdge until lastAlongEdge step sampleStep) {
                val pixelIndex = if (horizontal) index * image.cols() + along
                    else along * image.cols() + index
                val luminance = pixels[pixelIndex].toInt() and 0xff
                values += luminance
                if (luminance < 96) dark++
                count++
            }
            if (values.isEmpty()) return Pair(255, 0.0)
            values.sort()
            return Pair(values[values.size / 2], dark.toDouble() / count)
        }

        val edge = sample(0)
        // Require evidence that most of the outer edge is dark, so text at the page margin
        // or a small speck cannot trigger a crop.
        if (edge.first >= 145 && edge.second < 0.18) return 0

        for (distance in 0 until searchLimit) {
            val outer = sample(distance)
            val inner = sample(distance + 2)
            val contrast = inner.first - outer.first
            val darkDrop = outer.second - inner.second
            val paperIsBrighter = inner.first >= maxOf(135, edge.first + 28)
            if (contrast >= 24 && darkDrop >= 0.12 && paperIsBrighter) {
                // Step just past the detected boundary to hide interpolation remnants.
                return (distance + 3).coerceAtMost(searchLimit)
            }
        }
        return 0
    }

    val top = trimCount(horizontal = true, reverse = false)
    val bottom = trimCount(horizontal = true, reverse = true)
    val left = trimCount(horizontal = false, reverse = false)
    val right = trimCount(horizontal = false, reverse = true)
    if (top + bottom >= image.rows() / 12 || left + right >= image.cols() / 12) return image.clone()
    if (top + bottom + left + right == 0) return image.clone()

    val crop = image.submat(
        org.opencv.core.Rect(
            left, top,
            (image.cols() - left - right).coerceAtLeast(1),
            (image.rows() - top - bottom).coerceAtLeast(1)
        )
    )
    return try {
        crop.clone()
    } finally {
        crop.release()
    }
}

private fun deskewTextLines(image: Mat): Mat {
    if (image.cols() < 400 || image.rows() < 400) return image.clone()
    val gray = Mat()
    when (image.channels()) {
        4 -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGRA2GRAY)
        3 -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
        else -> image.copyTo(gray)
    }
    val edges = Mat()
    Imgproc.Canny(gray, edges, 50.0, 150.0)
    gray.release()

    val marginX = (image.cols() * 0.08).roundToInt()
    val marginY = (image.rows() * 0.08).roundToInt()
    val roi = edges.submat(
        org.opencv.core.Rect(
            marginX, marginY,
            (image.cols() - marginX * 2).coerceAtLeast(1),
            (image.rows() - marginY * 2).coerceAtLeast(1)
        )
    )
    val lines = Mat()
    Imgproc.HoughLinesP(
        roi, lines, 1.0, Math.PI / 180.0,
        (image.cols() / 120).coerceAtLeast(20),
        image.cols() * 0.12,
        image.cols() * 0.015
    )
    roi.release()
    edges.release()

    val angles = mutableListOf<Double>()
    for (row in 0 until lines.rows()) {
        val line = lines.get(row, 0) ?: continue
        if (line.size < 4) continue
        val dx = line[2] - line[0]
        val dy = line[3] - line[1]
        var angle = Math.toDegrees(kotlin.math.atan2(dy, dx))
        if (angle > 90.0) angle -= 180.0
        if (angle < -90.0) angle += 180.0
        if (kotlin.math.abs(angle) <= 8.0) angles += angle
    }
    lines.release()

    if (angles.size < 4) return image.clone()
    val medianAngle = angles.sorted()[angles.size / 2]
    if (kotlin.math.abs(medianAngle) < 0.35 || kotlin.math.abs(medianAngle) > 4.0)
        return image.clone()

    val center = org.opencv.core.Point(image.cols() / 2.0, image.rows() / 2.0)
    val transform = Imgproc.getRotationMatrix2D(center, -medianAngle, 1.0)
    val result = Mat()
    try {
        Imgproc.warpAffine(
            image, result, transform, image.size(), Imgproc.INTER_CUBIC,
            Core.BORDER_REPLICATE, org.opencv.core.Scalar(0.0)
        )
    } finally {
        transform.release()
    }
    return result
}
private fun refineQuadToImageEdges(image: Mat, quad: Quad): Quad {
    val gray = Mat()
    when (image.channels()) {
        4 -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGRA2GRAY)
        3 -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
        else -> image.copyTo(gray)
    }
    val edges = Mat()
    Imgproc.Canny(gray, edges, 45.0, 135.0)
    gray.release()

    val corners = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
    val diagonal = sqrt(image.cols().toDouble() * image.cols() + image.rows().toDouble() * image.rows())
    val searchRadius = (diagonal * 0.006).roundToInt().coerceIn(3, 30)
    val offsets = mutableListOf<Double>()
    for (index in corners.indices) {
        val start = corners[index]
        val end = corners[(index + 1) % corners.size]
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 20.0) {
            offsets += 0.0
            continue
        }
        val nx = -dy / length
        val ny = dx / length
        val samples = mutableListOf<Double>()
        for (sampleIndex in 4..36 step 2) {
            val t = sampleIndex / 40.0
            val x = start.x + dx * t
            val y = start.y + dy * t
            var bestOffset: Double? = null
            for (distance in -searchRadius..searchRadius) {
                val px = (x + nx * distance).roundToInt()
                val py = (y + ny * distance).roundToInt()
                if (px in 0 until edges.cols() && py in 0 until edges.rows()
                    && edges.get(py, px)?.firstOrNull()?.let { it > 0.0 } == true) {
                    if (bestOffset == null || kotlin.math.abs(distance) < kotlin.math.abs(bestOffset)) {
                        bestOffset = distance.toDouble()
                    }
                }
            }
            bestOffset?.let(samples::add)
        }
        offsets += if (samples.size >= 6) samples.sorted()[samples.size / 2] else 0.0
    }
    edges.release()

    fun shifted(index: Int): Line {
        val start = corners[index]
        val end = corners[(index + 1) % corners.size]
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1.0)
        val amount = offsets[index]
        val nx = -dy / length
        val ny = dx / length
        return Line(
            Point(start.x + nx * amount, start.y + ny * amount),
            Point(end.x + nx * amount, end.y + ny * amount)
        )
    }

    fun intersect(a: Line, b: Line): Point? {
        val rx = a.to.x - a.from.x
        val ry = a.to.y - a.from.y
        val sx = b.to.x - b.from.x
        val sy = b.to.y - b.from.y
        val denominator = rx * sy - ry * sx
        if (kotlin.math.abs(denominator) < 1e-6) return null
        val qpx = b.from.x - a.from.x
        val qpy = b.from.y - a.from.y
        val t = (qpx * sy - qpy * sx) / denominator
        return Point(a.from.x + t * rx, a.from.y + t * ry)
    }

    val sides = corners.indices.map(::shifted)
    val refined = listOf(
        intersect(sides[3], sides[0]),
        intersect(sides[0], sides[1]),
        intersect(sides[1], sides[2]),
        intersect(sides[2], sides[3]),
    )
    if (refined.any { it == null }) return quad
    val candidate = refined.filterNotNull().let { Quad(it[0], it[1], it[2], it[3]) }
    val maxShift = diagonal * 0.02
    val safe = candidate.isConvex() &&
        candidate.edges().indices.all { index ->
            norm(corners[index], listOf(candidate.topLeft, candidate.topRight,
                candidate.bottomRight, candidate.bottomLeft)[index]) <= maxShift
        } &&
        listOf(candidate.topLeft, candidate.topRight, candidate.bottomRight, candidate.bottomLeft)
            .all { isInsideImage(it, ImageSize(image.cols(), image.rows())) }
    return if (safe) candidate else quad
}
private fun Point.clampTo(imageSize: ImageSize): Point = Point(
    x.coerceIn(0.0, (imageSize.width - 1.0).coerceAtLeast(0.0)),
    y.coerceIn(0.0, (imageSize.height - 1.0).coerceAtLeast(0.0)),
)
fun EstimatedDimensions.toPixelDimensions(quad: Quad): Pair<Double, Double> {
    val w = (norm(quad.topLeft, quad.topRight) + norm(quad.bottomLeft, quad.bottomRight)) / 2
    val h = (norm(quad.topLeft, quad.bottomLeft) + norm(quad.topRight, quad.bottomRight)) / 2
    val projectedArea = w * h

    val ratio = aspectRatio
    val targetWidth = sqrt(projectedArea / ratio)
    val targetHeight = targetWidth * ratio
    return Pair(targetWidth, targetHeight)
}

fun rotate(input: Mat, degrees: Int): Mat {
    val output = Mat()
    when ((degrees % 360 + 360) % 360) {
        0 -> input.copyTo(output)
        90 -> Core.rotate(input, output, Core.ROTATE_90_CLOCKWISE)
        180 -> Core.rotate(input, output, Core.ROTATE_180)
        270 -> Core.rotate(input, output, Core.ROTATE_90_COUNTERCLOCKWISE)
        else -> throw IllegalArgumentException("Only 0, 90, 180, 270 degrees are supported")
    }
    return output
}

fun Point.toCv(): org.opencv.core.Point {
    return org.opencv.core.Point(x, y)
}

fun Size.toImageSize(): ImageSize {
    return ImageSize(width, height)
}
