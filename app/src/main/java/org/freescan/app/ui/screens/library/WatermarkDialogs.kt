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

package org.freescan.app.ui.screens.library

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freescan.app.R
import org.freescan.app.data.QuarkWatermarkProcessor
import org.freescan.app.platform.QrRegionRepair
import org.freescan.app.platform.WatermarkKind
import org.freescan.app.platform.WatermarkPoint
import org.freescan.app.platform.WatermarkRegion
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class WatermarkReviewRequest(
    val page: QuarkWatermarkProcessor.ReviewPage,
    val answer: CompletableDeferred<List<WatermarkRegion>> = CompletableDeferred(),
)

@Composable
fun WatermarkDialogs(
    progress: QuarkWatermarkProcessor.Progress?,
    review: WatermarkReviewRequest?,
    onCancel: () -> Unit,
) {
    if (review != null) {
        key(review) { WatermarkSelectionDialog(review, onCancel) }
    } else if (progress != null) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.home_remove_quark_watermark)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.watermark_page_progress, progress.pageNumber, progress.pageCount))
                    Text(stringResource(when (progress.phase) {
                        QuarkWatermarkProcessor.Phase.DETECT -> R.string.watermark_detecting
                        QuarkWatermarkProcessor.Phase.REPAIR -> R.string.watermark_repairing
                        QuarkWatermarkProcessor.Phase.SAVE -> R.string.watermark_saving
                    }))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.watermark_cancel)) } },
        )
    }
}

@Composable
private fun WatermarkSelectionDialog(request: WatermarkReviewRequest, onCancel: () -> Unit) {
    val page = request.page
    var regions by remember { mutableStateOf(page.regions) }
    var selected by remember { mutableStateOf(page.regions.indices.filter {
        page.regions[it].kind != WatermarkKind.QR_UNVERIFIED
    }.toSet()) }
    val eligible = regions.indices.filter { regions[it].kind != WatermarkKind.QR_UNVERIFIED }.toSet()
    var manual by remember { mutableStateOf(false) }
    var repaired by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val displayed = repaired ?: page.preview
    val image = remember(displayed) { displayed.asImageBitmap() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val imageRect = fittedRect(canvasSize, page.preview.width, page.preview.height)
    fun normalize(point: Offset) = Offset(
        ((point.x - imageRect.left) / max(1f, imageRect.width)).coerceIn(0f, 1f),
        ((point.y - imageRect.top) / max(1f, imageRect.height)).coerceIn(0f, 1f),
    )
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.home_remove_quark_watermark), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.watermark_page_progress, page.pageNumber, page.pageCount))
                    Text(stringResource(R.string.watermark_selection_hint), style = MaterialTheme.typography.bodySmall)
                    if (page.isPdf) Text(stringResource(R.string.watermark_pdf_note), style = MaterialTheme.typography.bodySmall)
                }
            },
            bottomBar = {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    previewError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selected == eligible && eligible.isNotEmpty(),
                            enabled = !busy,
                            onClick = { selected = eligible; repaired = null },
                            label = { Text(stringResource(R.string.watermark_all)) },
                        )
                        FilterChip(
                            selected = selected.isNotEmpty() && selected == eligible.filter { regions[it].atPageEdge }.toSet(),
                            enabled = !busy,
                            onClick = { selected = eligible.filter { regions[it].atPageEdge }.toSet(); repaired = null },
                            label = { Text(stringResource(R.string.watermark_edges)) },
                        )
                        FilterChip(
                            selected = manual, enabled = !busy,
                            onClick = { manual = !manual; repaired = null },
                            label = { Text(stringResource(R.string.watermark_add_box)) },
                        )
                        TextButton(enabled = !busy && regions.any { it.kind == WatermarkKind.MANUAL }, onClick = {
                            val index = regions.indexOfLast { it.kind == WatermarkKind.MANUAL }
                            regions = regions.filterIndexed { i, _ -> i != index }
                            selected = selected.filter { it != index }.map { if (it > index) it - 1 else it }.toSet()
                            repaired = null
                        }) { Text(stringResource(R.string.watermark_undo_box)) }
                    }
                    Text(stringResource(R.string.watermark_selection_count, selected.size), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.watermark_cancel)) }
                        TextButton(enabled = !busy && selected.isNotEmpty(), onClick = {
                            if (repaired != null) {
                                repaired = null
                            } else {
                                val chosen = regions.filterIndexed { index, _ -> index in selected }
                                scope.launch {
                                    busy = true
                                    previewError = null
                                    var working: Bitmap? = null
                                    try {
                                        val result = withContext(Dispatchers.Default) {
                                            val copy = page.preview.copy(Bitmap.Config.ARGB_8888, true)
                                                ?: error("无法生成预览")
                                            working = copy
                                            QrRegionRepair.erase(copy, chosen)
                                            currentCoroutineContext().ensureActive()
                                            copy
                                        }
                                        // Displayed bitmaps are released by GC after Compose stops using them.
                                        repaired = result
                                        working = null
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        previewError = failure.message
                                    } finally {
                                        working?.recycle()
                                        busy = false
                                    }
                                }
                            }
                        }) { Text(stringResource(if (repaired == null) R.string.watermark_preview else R.string.watermark_original)) }
                        Button(enabled = !busy && !request.answer.isCompleted, onClick = {
                            request.answer.complete(regions.filterIndexed { index, _ -> index in selected })
                        }) {
                            Text(stringResource(when {
                                selected.isEmpty() -> R.string.watermark_skip_page
                                page.pageNumber < page.pageCount -> R.string.watermark_next_page
                                else -> R.string.watermark_save
                            }))
                        }
                    }
                }
            },
        ) { padding ->
            val gestures = if (busy || repaired != null) Modifier else if (manual) {
                Modifier.pointerInput(imageRect, regions) {
                    detectDragGestures(
                        onDragStart = { point ->
                            if (imageRect.contains(point)) { dragStart = normalize(point); dragEnd = dragStart }
                        },
                        onDrag = { change, _ ->
                            if (dragStart != null) { change.consume(); dragEnd = normalize(change.position) }
                        },
                        onDragCancel = { dragStart = null; dragEnd = null },
                        onDragEnd = {
                            val start = dragStart
                            val end = dragEnd
                            if (start != null && end != null) {
                                val box = RectF(min(start.x, end.x), min(start.y, end.y), max(start.x, end.x), max(start.y, end.y))
                                if (box.width() * imageRect.width >= 8 && box.height() * imageRect.height >= 8) {
                                    selected = selected + regions.size
                                    regions = regions + WatermarkRegion.rectangle(box)
                                }
                            }
                            dragStart = null
                            dragEnd = null
                        },
                    )
                }
            } else {
                Modifier.pointerInput(imageRect, regions, selected) {
                    detectTapGestures { point ->
                        if (imageRect.contains(point)) {
                            val p = normalize(point)
                            val index = regions.indices.reversed().firstOrNull { containsPoint(regions[it].corners, p) }
                            if (index != null) selected = if (index in selected) selected - index else selected + index
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize().padding(padding)) {
                Canvas(Modifier.fillMaxSize().onSizeChanged { canvasSize = it }.then(gestures)) {
                    drawRect(Color(0xFF242424))
                    drawImage(image, dstOffset = IntOffset(imageRect.left.roundToInt(), imageRect.top.roundToInt()),
                        dstSize = IntSize(max(1, imageRect.width.roundToInt()), max(1, imageRect.height.roundToInt())))
                    if (repaired == null) {
                        regions.forEachIndexed { index, region ->
                            val path = Path().apply {
                                region.corners.forEachIndexed { i, p ->
                                    val x = imageRect.left + p.x * imageRect.width
                                    val y = imageRect.top + p.y * imageRect.height
                                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                                }
                                close()
                            }
                            val color = if (index in selected) Color(0xFF34D399) else Color(0xFFFFC857)
                            drawPath(path, color.copy(alpha = if (index in selected) 0.20f else 0.04f))
                            drawPath(path, color, style = Stroke(2.dp.toPx()))
                        }
                        val start = dragStart
                        val end = dragEnd
                        if (start != null && end != null) {
                            drawRect(Color.Cyan,
                                topLeft = Offset(imageRect.left + min(start.x, end.x) * imageRect.width,
                                    imageRect.top + min(start.y, end.y) * imageRect.height),
                                size = androidx.compose.ui.geometry.Size(kotlin.math.abs(start.x - end.x) * imageRect.width,
                                    kotlin.math.abs(start.y - end.y) * imageRect.height), style = Stroke(2.dp.toPx()))
                        }
                    }
                }
            }
        }
    }
}

private fun fittedRect(size: IntSize, width: Int, height: Int): Rect {
    val scale = min(size.width.toFloat() / width, size.height.toFloat() / height)
    val w = width * scale
    val h = height * scale
    return Rect((size.width - w) / 2, (size.height - h) / 2, (size.width + w) / 2, (size.height + h) / 2)
}

private fun containsPoint(corners: List<WatermarkPoint>, point: Offset): Boolean {
    var inside = false
    var j = corners.lastIndex
    for (i in corners.indices) {
        val a = corners[i]
        val b = corners[j]
        if ((a.y > point.y) != (b.y > point.y) &&
            point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}
