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

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.engawapg.lib.zoomable.ZoomState
import net.engawapg.lib.zoomable.zoomable
import org.freescan.app.R
import org.freescan.app.BuildConfig
import org.freescan.app.domain.PageViewKey
import org.freescan.app.domain.Rotation
import org.freescan.app.domain.isLocalNativeFilter
import org.freescan.app.ui.Navigation
import org.freescan.app.ui.components.CommonPageListState
import org.freescan.app.ui.components.ConfirmationDialog
import org.freescan.app.ui.components.MainActionButton
import org.freescan.app.ui.components.MyScaffold
import org.freescan.app.ui.components.SecondaryActionButton
import org.freescan.app.ui.dummyNavigation
import org.freescan.app.ui.fakeDocument
import org.freescan.app.ui.fakeImage
import org.freescan.app.ui.theme.FreeScanTheme
import org.freescan.app.platform.InpaintStroke
import org.freescan.app.platform.inpaintBitmap
import org.freescan.imageprocessing.ColorMode
import org.freescan.imageprocessing.ColorMode.BLACK_AND_WHITE
import org.freescan.imageprocessing.ColorMode.COLOR
import org.freescan.imageprocessing.ColorMode.GRAYSCALE
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    uiState: DocumentUiState,
    navigation: Navigation,
    onExportClick: () -> Unit,
    onDeleteImage: () -> Unit,
    onRotateImage: (Boolean) -> Unit,
    onColorModeSelected: (ColorMode) -> Unit,
    onRemoveShadowsChanged: (Boolean) -> Unit = {},
    onCropClick: () -> Unit,
    onPageReorder: (String, Int) -> Unit,
    onPageSelected: (Int) -> Unit,
    onSaveManualEdit: suspend (PageViewKey, android.graphics.Bitmap) -> Unit = { _, _ -> },
) {
    val showDeletePageDialog = rememberSaveable { mutableStateOf(false) }

    val document = uiState.document
    val currentPageIndex = uiState.currentPageIndex
    val currentPageId = uiState.currentPage?.key?.pageId
    var eraserMode by rememberSaveable(currentPageId) { mutableStateOf(false) }
    val eraserStrokes = remember(currentPageId) { mutableStateListOf<InpaintStroke>() }
    var brushWidthDp by rememberSaveable(currentPageId) { mutableStateOf(24f) }
    var repairPreview by remember(currentPageId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isRepairing by remember(currentPageId) { mutableStateOf(false) }
    var isSavingEdit by remember(currentPageId) { mutableStateOf(false) }
    var eraseError by remember(currentPageId) { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    BackHandler {
        if (eraserMode) {
            eraserMode = false
            repairPreview = null
            eraserStrokes.clear()
            eraseError = null
        } else {
            navigation.back()
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(currentPageIndex) {
        listState.scrollToItem(currentPageIndex)
    }

    MyScaffold(
        navigation = navigation,
        pageListState = if (eraserMode) null else CommonPageListState(
            document,
            onPageClick = { index -> onPageSelected(index) },
            onPageReorder = onPageReorder,
            currentPageIndex = currentPageIndex,
            listState = listState,
            showPageNumbers = true,
        ),
        showOverflowMenu = !eraserMode,
        bottomBar = {
            if (!eraserMode) {
                BottomBar(onExportClick, navigation.toCameraScreen)
            }
        },
    ) { modifier ->
        DocumentPreview(
            uiState = uiState,
            onDeleteImage = { showDeletePageDialog.value = true },
            onRotateImage = onRotateImage,
            onColorModeSelected = onColorModeSelected,
            onRemoveShadowsChanged = onRemoveShadowsChanged,
            onCropClick = onCropClick,
            modifier = modifier,
            eraserMode = eraserMode,
            eraserStrokes = eraserStrokes,
            brushWidthDp = brushWidthDp,
            repairPreview = repairPreview,
            isRepairing = isRepairing,
            isSavingEdit = isSavingEdit,
            eraseError = eraseError,
            onEraserClick = {
                eraserMode = true
                eraserStrokes.clear()
                repairPreview = null
                eraseError = null
            },
            onStrokeCompleted = { stroke ->
                eraserStrokes.add(stroke)
                repairPreview = null
                eraseError = null
            },
            onBrushWidthChanged = { brushWidthDp = it },
            onUndoStroke = { if (eraserStrokes.isNotEmpty()) eraserStrokes.removeAt(eraserStrokes.lastIndex) },
            onClearStrokes = { eraserStrokes.clear(); repairPreview = null },
            onCancelEraser = {
                eraserMode = false
                eraserStrokes.clear()
                repairPreview = null
                eraseError = null
            },
            onRepair = {
                val bitmap = uiState.currentPage?.bitmap
                val strokes = eraserStrokes.toList()
                if (bitmap != null && strokes.isNotEmpty()) {
                    coroutineScope.launch {
                        isRepairing = true
                        eraseError = null
                        try {
                            repairPreview = withContext(Dispatchers.Default) {
                                inpaintBitmap(bitmap, strokes)
                            }
                        } catch (error: Exception) {
                            eraseError = error.message ?: "Could not repair the selected area."
                        } finally {
                            isRepairing = false
                        }
                    }
                }
            },
            onResumePainting = { repairPreview = null },
            onApplyRepair = {
                val page = uiState.currentPage
                val result = repairPreview
                if (page != null && result != null) {
                    coroutineScope.launch {
                        isSavingEdit = true
                        eraseError = null
                        try {
                            onSaveManualEdit(page.key, result)
                            eraserMode = false
                            eraserStrokes.clear()
                            repairPreview = null
                        } catch (error: Exception) {
                            eraseError = error.message ?: "Could not save the repaired image."
                        } finally {
                            isSavingEdit = false
                        }
                    }
                }
            },
        )
        if (showDeletePageDialog.value) {
            ConfirmationDialog(
                title = stringResource(R.string.delete_page),
                message = stringResource(R.string.delete_page_warning),
                showDialog = showDeletePageDialog
            ) { onDeleteImage() }
        }
    }
}

@Composable
private fun DocumentPreview(
    uiState: DocumentUiState,
    onDeleteImage: () -> Unit,
    onRotateImage: (Boolean) -> Unit,
    onColorModeSelected: (ColorMode) -> Unit,
    onRemoveShadowsChanged: (Boolean) -> Unit,
    onCropClick: () -> Unit,
    modifier: Modifier,
    eraserMode: Boolean,
    eraserStrokes: List<InpaintStroke>,
    brushWidthDp: Float,
    repairPreview: android.graphics.Bitmap?,
    isRepairing: Boolean,
    isSavingEdit: Boolean,
    eraseError: String?,
    onEraserClick: () -> Unit,
    onStrokeCompleted: (InpaintStroke) -> Unit,
    onBrushWidthChanged: (Float) -> Unit,
    onUndoStroke: () -> Unit,
    onClearStrokes: () -> Unit,
    onCancelEraser: () -> Unit,
    onRepair: () -> Unit,
    onResumePainting: () -> Unit,
    onApplyRepair: () -> Unit,
) {
    val currentPageIndex = uiState.currentPageIndex
    val document = uiState.document
    Column (
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Box (
            modifier = Modifier.fillMaxSize()
        ) {
            val bitmap = repairPreview ?: uiState.currentPage?.bitmap
            val pageKey = uiState.currentPage?.key
            if (bitmap != null && pageKey != null) {
                if (eraserMode) {
                    if (repairPreview == null) {
                        InpaintingCanvas(
                            bitmap = bitmap,
                            strokes = eraserStrokes,
                            brushWidthDp = brushWidthDp,
                            enabled = !isRepairing,
                            onStrokeCompleted = onStrokeCompleted,
                            modifier = Modifier.fillMaxSize(0.92f).align(Alignment.Center),
                        )
                    } else {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.repair_preview),
                            modifier = Modifier.fillMaxSize(0.92f).align(Alignment.Center),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        )
                    }
                    EraserControls(
                        hasStrokes = eraserStrokes.isNotEmpty(),
                        hasPreview = repairPreview != null,
                        brushWidthDp = brushWidthDp,
                        isBusy = isRepairing || isSavingEdit,
                        error = eraseError,
                        onBrushWidthChanged = onBrushWidthChanged,
                        onUndo = onUndoStroke,
                        onClear = onClearStrokes,
                        onCancel = onCancelEraser,
                        onRepair = onRepair,
                        onResumePainting = onResumePainting,
                        onApply = onApplyRepair,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                } else {
                    val imageBitmap = bitmap.asImageBitmap()
                    val zoomState = remember(pageKey) {
                        ZoomState(
                            contentSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
                        )
                    }

                    Box(modifier = Modifier
                        .fillMaxSize(0.92f)
                        .align(Alignment.Center)) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(4.dp)
                                .align(Alignment.Center)
                                .zoomable(zoomState)
                        )
                    }
                }
            }
            if (!eraserMode && (uiState.currentPage?.isLoading ?: false)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            if (!eraserMode) {
                EditButtons(
                    uiState = uiState,
                    onColorModeSelected = onColorModeSelected,
                    onRemoveShadowsChanged = onRemoveShadowsChanged,
                    onCropClick = onCropClick,
                    onEraserClick = onEraserClick,
                    hasManualEdits = uiState.currentPage?.hasManualEdits == true,
                    removeShadows = uiState.currentPage?.removeShadows ?: true,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
                RotationButtons(onRotateImage, Modifier.align(Alignment.BottomCenter))
                SecondaryActionButton(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.delete_page),
                    onClick = { onDeleteImage() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                )
            }
            Text("${currentPageIndex + 1} / ${document.pageCount()}",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(all = 16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun RotationButtons(
    onRotateImage: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // RotateLeft on the left, RotateRight on the right: for both LTR and RTL languages
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier = modifier.padding(8.dp)) {
            // Using AutoMirrored icons would lead to an opposite rotation in RTL languages
            @Suppress("DEPRECATION")
            SecondaryActionButton(
                icon = Icons.Default.RotateLeft,
                contentDescription = stringResource(R.string.rotate_left),
                onClick = { onRotateImage(false) }
            )
            Spacer(Modifier.width(8.dp))
            @Suppress("DEPRECATION")
            SecondaryActionButton(
                icon = Icons.Default.RotateRight,
                contentDescription = stringResource(R.string.rotate_right),
                onClick = { onRotateImage(true) }
            )
        }
    }
}

@Composable
fun EditButtons(
    uiState: DocumentUiState,
    onColorModeSelected: (ColorMode) -> Unit,
    onRemoveShadowsChanged: (Boolean) -> Unit,
    onCropClick: () -> Unit,
    onEraserClick: () -> Unit,
    hasManualEdits: Boolean,
    removeShadows: Boolean,
    modifier: Modifier
) {
    Row(modifier = modifier.padding(8.dp)) {
        if (!hasManualEdits) {
            uiState.currentPage?.colorMode?.let {
                ColorModeButton(
                    currentColorMode = it,
                    onColorModeSelected = onColorModeSelected,
                    removeShadows = removeShadows,
                    onRemoveShadowsChanged = onRemoveShadowsChanged,
                )
            }
        }
        if (uiState.currentPage?.canBeCropped ?: false) {
            Spacer(Modifier.width(8.dp))
            SecondaryActionButton(
                icon = Icons.Default.Crop,
                contentDescription = stringResource(R.string.crop),
                onClick = onCropClick,
            )
        }
        Spacer(Modifier.width(8.dp))
        SecondaryActionButton(
            icon = Icons.Default.Brush,
            contentDescription = stringResource(R.string.erase_tool),
            onClick = onEraserClick,
            enabled = uiState.currentPage?.bitmap != null,
        )
    }
}

@Composable
private fun EraserControls(
    hasStrokes: Boolean,
    hasPreview: Boolean,
    brushWidthDp: Float,
    isBusy: Boolean,
    error: String?,
    onBrushWidthChanged: (Float) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onRepair: () -> Unit,
    onResumePainting: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        tonalElevation = 4.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (!hasPreview) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.eraser_brush_size_value, brushWidthDp.roundToInt()),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = brushWidthDp,
                        onValueChange = onBrushWidthChanged,
                        valueRange = 8f..40f,
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onUndo, enabled = hasStrokes && !isBusy) {
                        Text(stringResource(R.string.eraser_undo))
                    }
                    TextButton(onClick = onClear, enabled = hasStrokes && !isBusy) {
                        Text(stringResource(R.string.eraser_clear))
                    }
                    TextButton(onClick = onCancel, enabled = !isBusy) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(onClick = onRepair, enabled = hasStrokes && !isBusy) {
                        Text(stringResource(if (isBusy) R.string.eraser_processing else R.string.eraser_repair))
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onResumePainting, enabled = !isBusy) {
                        Text(stringResource(R.string.eraser_continue_painting))
                    }
                    TextButton(onClick = onCancel, enabled = !isBusy) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(onClick = onApply, enabled = !isBusy) {
                        Text(stringResource(if (isBusy) R.string.eraser_saving else R.string.eraser_apply))
                    }
                }
            }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ColorModeButton(
    currentColorMode: ColorMode,
    onColorModeSelected: (ColorMode) -> Unit,
    removeShadows: Boolean = true,
    onRemoveShadowsChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        SecondaryActionButton(
            icon = Icons.Default.AutoFixHigh,
            contentDescription = stringResource(R.string.color_mode),
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ColorMode.entries
                .filter {
                    it != ColorMode.AI_ENHANCED &&
                        it != ColorMode.RED_STAMP &&
                        (BuildConfig.SCANLAB_ENABLED || !it.isLocalNativeFilter())
                }
                .forEach { colorMode ->
                DropdownMenuItem(
                    text = { Text(stringResource(colorMode.labelResource)) },
                    onClick = {
                        onColorModeSelected(colorMode)
                        expanded = false
                    },
                    trailingIcon = {
                        if (currentColorMode == colorMode) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
            }
            if (currentColorMode != ColorMode.AI_ENHANCED && currentColorMode != ColorMode.ORIGINAL) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.shadow_removal))
                            Text(
                                stringResource(R.string.shadow_removal_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { onRemoveShadowsChanged(!removeShadows) },
                    trailingIcon = {
                        Checkbox(checked = removeShadows, onCheckedChange = null)
                    },
                )
            }
        }
    }
}

private val ColorMode.labelResource: Int
    get() = when (this) {
        ColorMode.ORIGINAL -> R.string.color_mode_original
        COLOR -> R.string.color_mode_color
        GRAYSCALE -> R.string.color_mode_grayscale
        BLACK_AND_WHITE -> R.string.color_mode_black_and_white
        ColorMode.AI_ENHANCED -> R.string.color_mode_ai_enhanced
        ColorMode.LOCAL_BRIGHTNESS -> R.string.color_mode_local_brightness
        ColorMode.LOCAL_ENHANCED -> R.string.color_mode_local_enhanced
        ColorMode.LOCAL_GRAYSCALE -> R.string.color_mode_local_grayscale
        ColorMode.LOCAL_SOFT -> R.string.color_mode_local_soft
        ColorMode.RED_STAMP -> R.string.color_mode_color
    }

@Composable
private fun BottomBar(
    onExportClick: () -> Unit,
    onAddPageClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(
            onClick = onAddPageClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.add_page),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        MainActionButton(
            onClick = onExportClick,
            icon = Icons.Default.Done,
            text = stringResource(R.string.export),
        )
    }
}

@Composable
@Preview
@Preview(locale = "ar")
@Preview(name = "Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Landscape", showBackground = true, widthDp = 640, heightDp = 320)
fun DocumentScreenPreview() {
    FreeScanTheme {
        val image = fakeImage("gallica.bnf.fr-bpt6k5530456s-1", LocalContext.current).toBitmap()
        val document = fakeDocument(
            listOf(1, 2).map { "gallica.bnf.fr-bpt6k5530456s-$it" }.toImmutableList(),
            LocalContext.current
        )
        val key = PageViewKey("123", Rotation.R0, null, 0)
        DocumentScreen(
            uiState = DocumentUiState(1, CurrentPageUiState(key,image, COLOR, true), document),
            navigation = dummyNavigation(),
            onExportClick = {},
            onDeleteImage = { },
            onRotateImage = { _ -> },
            onColorModeSelected = { },
            onCropClick = { },
            onPageReorder = { _,_ -> },
            onPageSelected = { _ -> },
        )
    }
}
