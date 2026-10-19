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
package org.freescan.app.ui.screens.document

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.freescan.app.platform.InpaintStroke
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun InpaintingCanvas(
    bitmap: Bitmap,
    strokes: List<InpaintStroke>,
    brushWidthDp: Float,
    enabled: Boolean,
    onStrokeCompleted: (InpaintStroke) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current.density
    var activeStroke by remember(bitmap) { mutableStateOf<InpaintStroke?>(null) }
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(bitmap, brushWidthDp, enabled, onStrokeCompleted) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val container = Size(size.width.toFloat(), size.height.toFloat())
                    val fitted = fitImageRect(container, bitmap.width, bitmap.height)
                    val firstPoint = down.position.toBitmapPointOrNull(
                        fitted, container, bitmap, zoom, pan,
                    ) ?: return@awaitEachGesture

                    val points = mutableListOf(firstPoint)
                    val widthPx = (brushWidthDp * density / (fitted.scale * zoom))
                        .coerceAtLeast(1f)
                    activeStroke = InpaintStroke(points.toList(), widthPx)
                    down.consume()

                    var multiPointerGesture = false
                    var previousPointerCount = 1
                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            multiPointerGesture = true
                            activeStroke = null
                            if (previousPointerCount >= 2) {
                                val previousCentroid = pressed.centroid(previous = true)
                                val currentCentroid = pressed.centroid(previous = false)
                                val previousSpan = pressed.span(previous = true, previousCentroid)
                                val currentSpan = pressed.span(previous = false, currentCentroid)
                                val factor = if (previousSpan > 0f) currentSpan / previousSpan else 1f
                                val nextZoom = (zoom * factor).coerceIn(1f, 6f)
                                val appliedFactor = nextZoom / zoom
                                val center = Offset(container.width / 2f, container.height / 2f)
                                val nextPan = Offset(
                                    currentCentroid.x - center.x -
                                        (previousCentroid.x - center.x - pan.x) * appliedFactor,
                                    currentCentroid.y - center.y -
                                        (previousCentroid.y - center.y - pan.y) * appliedFactor,
                                )
                                zoom = nextZoom
                                pan = clampPan(nextPan, fitted, nextZoom)
                            }
                            pressed.forEach { it.consume() }
                            previousPointerCount = pressed.size
                            continue
                        }

                        if (multiPointerGesture) {
                            pressed.forEach { it.consume() }
                            previousPointerCount = pressed.size
                            finished = pressed.isEmpty()
                            continue
                        }

                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: break
                        change.position.toBitmapPointOrNull(fitted, container, bitmap, zoom, pan)
                            ?.let { point ->
                                val previous = points.lastOrNull()
                                if (previous == null || previous.x != point.x || previous.y != point.y) {
                                    points.add(point)
                                    activeStroke = InpaintStroke(points.toList(), widthPx)
                                }
                            }
                        finished = !change.pressed
                        change.consume()
                        previousPointerCount = pressed.size
                    }
                    if (!multiPointerGesture) activeStroke?.let(onStrokeCompleted)
                    activeStroke = null
                }
            }
    ) {
        val fitted = fitImageRect(size, bitmap.width, bitmap.height)
        val center = Offset(size.width / 2f, size.height / 2f)
        val imageWidth = fitted.width * zoom
        val imageHeight = fitted.height * zoom
        val imageLeft = center.x - imageWidth / 2f + pan.x
        val imageTop = center.y - imageHeight / 2f + pan.y
        drawImage(
            image = imageBitmap,
            dstOffset = IntOffset(imageLeft.roundToInt(), imageTop.roundToInt()),
            dstSize = IntSize(imageWidth.roundToInt(), imageHeight.roundToInt()),
        )

        (strokes + listOfNotNull(activeStroke)).forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            val color = Color(0x99FF3030)
            val width = stroke.widthPx * fitted.scale * zoom
            if (stroke.points.size == 1) {
                val point = stroke.points.first().toScreenPoint(fitted, center, zoom, pan)
                drawCircle(color, width / 2f, point)
            } else {
                val path = Path().apply {
                    val first = stroke.points.first().toScreenPoint(fitted, center, zoom, pan)
                    moveTo(first.x, first.y)
                    stroke.points.drop(1).forEach { point ->
                        val screenPoint = point.toScreenPoint(fitted, center, zoom, pan)
                        lineTo(screenPoint.x, screenPoint.y)
                    }
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

private data class FittedImageRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val scale: Float,
) {
    fun contains(point: Offset): Boolean =
        point.x in left..(left + width) && point.y in top..(top + height)
}

private fun fitImageRect(container: Size, imageWidth: Int, imageHeight: Int): FittedImageRect {
    val scale = min(container.width / imageWidth, container.height / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return FittedImageRect(
        left = (container.width - width) / 2f,
        top = (container.height - height) / 2f,
        width = width,
        height = height,
        scale = scale,
    )
}

private fun Offset.toBitmapPointOrNull(
    rect: FittedImageRect,
    container: Size,
    bitmap: Bitmap,
    zoom: Float,
    pan: Offset,
): PointF? {
    val center = Offset(container.width / 2f, container.height / 2f)
    val base = Offset(
        center.x + (x - center.x - pan.x) / zoom,
        center.y + (y - center.y - pan.y) / zoom,
    )
    if (!rect.contains(base)) return null
    return PointF(
        ((base.x - rect.left) / rect.scale).coerceIn(0f, bitmap.width - 1f),
        ((base.y - rect.top) / rect.scale).coerceIn(0f, bitmap.height - 1f),
    )
}

private fun PointF.toScreenPoint(
    rect: FittedImageRect,
    center: Offset,
    zoom: Float,
    pan: Offset,
): Offset {
    val baseX = rect.left + x * rect.scale
    val baseY = rect.top + y * rect.scale
    return Offset(
        center.x + (baseX - center.x) * zoom + pan.x,
        center.y + (baseY - center.y) * zoom + pan.y,
    )
}

private fun List<PointerInputChange>.centroid(previous: Boolean): Offset {
    if (isEmpty()) return Offset.Zero
    val sum = fold(Offset.Zero) { total, change ->
        total + (if (previous) change.previousPosition else change.position)
    }
    return sum / size.toFloat()
}

private fun List<PointerInputChange>.span(previous: Boolean, centroid: Offset): Float {
    if (isEmpty()) return 0f
    val total = fold(0f) { sum, change ->
        val point = if (previous) change.previousPosition else change.position
        val dx = point.x - centroid.x
        val dy = point.y - centroid.y
        sum + sqrt(dx * dx + dy * dy)
    }
    return total / size
}

private fun clampPan(pan: Offset, fitted: FittedImageRect, zoom: Float): Offset {
    val maxX = (fitted.width * (zoom - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (fitted.height * (zoom - 1f) / 2f).coerceAtLeast(0f)
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}
