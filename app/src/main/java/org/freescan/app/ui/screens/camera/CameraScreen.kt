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
package org.freescan.app.ui.screens.camera

import android.content.res.Configuration
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.freescan.app.MainViewModel
import org.freescan.app.R
import org.freescan.app.domain.CapturedPage
import org.freescan.app.domain.Jpeg
import org.freescan.app.domain.PageMetadata
import org.freescan.app.domain.Rotation.R0
import org.freescan.app.domain.ScanResolution
import org.freescan.app.ui.Navigation
import org.freescan.app.ui.Screen
import org.freescan.app.ui.components.CameraPermissionState
import org.freescan.app.ui.components.CommonPageListState
import org.freescan.app.ui.components.MainActionButton
import org.freescan.app.ui.components.MyScaffold
import org.freescan.app.ui.components.pageCountText
import org.freescan.app.ui.dummyNavigation
import org.freescan.app.ui.fakeDocument
import org.freescan.app.ui.theme.FreeScanTheme
import org.freescan.imageprocessing.ColorMode
import org.freescan.imageprocessing.Point
import org.freescan.imageprocessing.Quad

const val CAPTURED_IMAGE_DISPLAY_DURATION = 1500L
const val ANIMATION_DURATION = 200

@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    cameraViewModel: CameraViewModel,
    navigation: Navigation,
    liveAnalysisState: LiveAnalysisState,
    importState: ImportState,
    onImageAnalyzed: (ImageProxy) -> Unit,
    onFinalizePressed: () -> Unit,
    cameraPermission: CameraPermissionState,
    onImportClicked: () -> Unit,
) {
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val document by viewModel.documentUiModel.collectAsStateWithLifecycle()
    val thumbnailCoords = remember { mutableStateOf(Offset.Zero) }
    var isDebugMode by remember { mutableStateOf(false) }
    val isTorchEnabled by cameraViewModel.isTorchEnabled.collectAsStateWithLifecycle()
    val flashMode by cameraViewModel.flashMode.collectAsStateWithLifecycle()
    val resolutionMode by cameraViewModel.resolutionMode.collectAsStateWithLifecycle()
    val multiFrameEnabled by cameraViewModel.multiFrameEnabled.collectAsStateWithLifecycle()
    val autoCropEnabled by cameraViewModel.autoCropEnabled.collectAsStateWithLifecycle()
    val isCertificateScan by cameraViewModel.isCertificateScan.collectAsStateWithLifecycle()
    val certificateTemplate by cameraViewModel.certificateTemplate.collectAsStateWithLifecycle()
    var torchReapplied by remember { mutableStateOf(false) }

    val captureController = remember { CameraCaptureController() }
    DisposableEffect(Unit) {
        onDispose {
            captureController.shutdown()
            torchReapplied = false
        }
    }
    LaunchedEffect(captureController.cameraControl, isTorchEnabled) {
        captureController.cameraControl?.enableTorch(isTorchEnabled)
    }

    val captureState by cameraViewModel.captureState.collectAsStateWithLifecycle()
    if (captureState is CaptureState.CapturePreview) {
        LaunchedEffect(captureState) {
            delay(CAPTURED_IMAGE_DISPLAY_DURATION)
            cameraViewModel.addProcessedImage()
        }
    }

    var showDetectionError by remember { mutableStateOf(false) }
    LaunchedEffect(captureState) {
        if (captureState is CaptureState.CaptureError) {
            showDetectionError = true
            delay(1000)
            showDetectionError = false
            cameraViewModel.afterCaptureError()
        }
    }

    LaunchedEffect(Unit) {
        cameraViewModel.resetLiveAnalysis()
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraViewModel.cancelImport()
        }
    }

    DisposableEffect(isCertificateScan) {
        onDispose {
            if (isCertificateScan) cameraViewModel.endCertificateScan()
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(document.pageCount()) {
        if (!document.isEmpty()) {
            listState.animateScrollToItem(document.lastIndex())
        }
    }

    val onCapture: () -> Unit = capture@{
        if (captureState !is CaptureState.Idle) return@capture
        if (captureController.imageCapture == null) return@capture
        previewView?.bitmap?.let {
            Log.i("FreeScan", "Pressed <Capture>")
            cameraViewModel.onCapturePressed(it)
            captureController.takePicture(
                frameCount = if (multiFrameEnabled) 3 else 1,
                onImageCaptured = { imageProxy, opticalMeasures ->
                    cameraViewModel.onImageCaptured(imageProxy, opticalMeasures, resolutionMode, autoCropEnabled)
                }
            )
        }?: Unit
    }
    val currentOnCapture by rememberUpdatedState(onCapture)
    LaunchedEffect(Unit) {
        cameraViewModel.volumeKeyEvent.collect {
            currentOnCapture()
        }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    CameraScreenScaffold(
        cameraPreview = {
            CameraPreview(
                onImageAnalyzed = {
                    if (autoCropEnabled) onImageAnalyzed(it) else it.close()
                    // Re-apply torch once the camera session is actually active (first analysis)
                    if (!torchReapplied) {
                        captureController.cameraControl?.enableTorch(isTorchEnabled)
                        torchReapplied = true
                    }
                },
                captureController = captureController,
                onPreviewViewReady = { view ->
                    previewView = view
                    captureController.previewView = view
                },
                onCameraBound = {
                    captureController.setFlashMode(flashMode)
                    captureController.cameraControl?.enableTorch(isTorchEnabled)
                },
                scanResolution = resolutionMode,
                cameraPermission = cameraPermission,
                onError = { message, throwable -> cameraViewModel.logError(message, throwable) }
            )
        },
        pageListState =
            CommonPageListState(
                document = document,
                onPageClick = { index -> viewModel.navigateTo(Screen.Main.Document(index)) },
                onPageReorder = { id, index -> viewModel.movePage(id, index) },
                listState = listState,
                showPageNumbers = false,
                onLastItemPosition = { offset -> thumbnailCoords.value = offset },
            ),
            cameraUiState = CameraUiState(
                document.pageCount(),
                liveAnalysisState,
                captureState,
                importState,
                showDetectionError,
                isLandscape,
                isDebugMode,
                isTorchEnabled,
                flashMode,
                resolutionMode,
                multiFrameEnabled,
                autoCropEnabled,
                isCertificateScan,
                certificateTemplate,
            ),
        onCapture = onCapture,
        onFinalizePressed = onFinalizePressed,
        onDebugModeSwitched = { isDebugMode = !isDebugMode },
        onTorchChanged = { enabled -> cameraViewModel.setTorchEnabled(enabled) },
        onFlashModeChanged = { mode ->
            cameraViewModel.setFlashMode(mode)
            captureController.setFlashMode(mode)
        },
        onResolutionChanged = { mode ->
            if (mode != resolutionMode) {
                captureController.imageCapture = null
                cameraViewModel.setResolutionMode(mode)
            }
        },
        onMultiFrameChanged = { cameraViewModel.setMultiFrameEnabled(it) },
        onAutoCropChanged = { cameraViewModel.setAutoCropEnabled(it) },
        onCertificateTemplateChanged = { cameraViewModel.setCertificateTemplate(it) },
        thumbnailCoords = thumbnailCoords,
        navigation = navigation,
        captureController,
        isCameraPermissionGranted = cameraPermission.isGranted,
        onRequestCameraPermission = { cameraPermission.request() },
        onImportClicked = onImportClicked,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CameraScreenScaffold(
    cameraPreview: @Composable () -> Unit,
    pageListState: CommonPageListState,
    cameraUiState: CameraUiState,
    onCapture: () -> Unit,
    onFinalizePressed: () -> Unit,
    onDebugModeSwitched: () -> Unit,
    onTorchChanged: (Boolean) -> Unit,
    onFlashModeChanged: (Int) -> Unit,
    onResolutionChanged: (ScanResolution) -> Unit,
    onMultiFrameChanged: (Boolean) -> Unit,
    onAutoCropChanged: (Boolean) -> Unit,
    onCertificateTemplateChanged: (CertificateScanTemplate) -> Unit,
    thumbnailCoords: MutableState<Offset>,
    navigation: Navigation,
    captureController: CameraCaptureController,
    isCameraPermissionGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    onImportClicked: () -> Unit,
) {
    var showCameraSettings by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1000)
            focusPoint = null
        }
    }

    var tapCount by remember { mutableLongStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    val tapThreshold = 500L
    val onPageCountClick = {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTapTime < tapThreshold) {
            tapCount++
            if (tapCount >= 3) {
                onDebugModeSwitched()
                tapCount = 0
            }
        } else {
            tapCount = 1
        }
        lastTapTime = currentTime
    }

    Box {
        MyScaffold(
            navigation = navigation,
            pageListState = pageListState,
            showOverflowMenu = false,
            bottomBar = {
                Bar(cameraUiState.pageCount, onFinalizePressed, onImportClicked)
            }
        ) { modifier ->
            if (cameraUiState.importState is ImportState.Selecting) {
                // display nothing: photo picker is active
            } else if (cameraUiState.importState is ImportState.Importing) {
                ImportInProgress(cameraUiState.importState, modifier)
            } else if (!isCameraPermissionGranted) {
                CameraPermissionRationale(onRequestCameraPermission, modifier)
            } else {
                CameraPreviewBox(
                    cameraPreview,
                    cameraUiState,
                    focusPoint,
                    onCapture,
                    onOpenSettings = { showCameraSettings = true },
                    onCertificateTemplateChanged = onCertificateTemplateChanged,
                    modifier = modifier.pointerInput(Unit) {
                        detectTapGestures { offset ->
                            focusPoint = offset
                            captureController.tapToFocus(offset)
                            onPageCountClick()
                        }
                    }
                )
            }
        }
        if (cameraUiState.captureState is CaptureState.CapturePreview) {
            val page = cameraUiState.captureState.capturedPage.pageImage.toBitmap()
            CapturedImage(page.asImageBitmap(), thumbnailCoords)
        }
        if (showCameraSettings) {
            ModalBottomSheet(
                onDismissRequest = { showCameraSettings = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                CameraSettingsSheet(
                    state = cameraUiState,
                    onAutoCropChanged = onAutoCropChanged,
                    onResolutionChanged = onResolutionChanged,
                    onFlashModeChanged = onFlashModeChanged,
                    onMultiFrameChanged = onMultiFrameChanged,
                    onTorchChanged = onTorchChanged,
                )
            }
        }
    }
}

@Composable
fun ImportInProgress(state: ImportState.Importing, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = LocalResources.current.getQuantityString(
                    R.plurals.importing_photos,
                    state.total,
                    state.total
                ),
                color = Color.White
            )

            Spacer(Modifier.height(16.dp))

            if (state.total > 1) {
                LinearProgressIndicator(
                    progress = { state.processed.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CameraPreviewBox(
    cameraPreview: @Composable (() -> Unit),
    cameraUiState: CameraUiState,
    focusPoint: Offset?,
    onCapture: () -> Unit,
    onOpenSettings: () -> Unit,
    onCertificateTemplateChanged: (CertificateScanTemplate) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
    ) {
        CameraPreviewWithOverlay(
            cameraPreview,
            cameraUiState,
            onCertificateTemplateChanged,
            Modifier.align(Alignment.BottomCenter)
        )
        if (cameraUiState.isDebugMode) {
            MessageBox(cameraUiState.liveAnalysisState.inferenceTime)
        }
        FocusOverlay(focusPoint)
        CaptureButton(
            onClick = onCapture,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.camera_settings), tint = Color.White)
            }
        }
    }
}

@Composable
private fun CameraSettingsSheet(
    state: CameraUiState,
    onAutoCropChanged: (Boolean) -> Unit,
    onResolutionChanged: (ScanResolution) -> Unit,
    onFlashModeChanged: (Int) -> Unit,
    onMultiFrameChanged: (Boolean) -> Unit,
    onTorchChanged: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(stringResource(R.string.camera_settings), style = MaterialTheme.typography.titleLarge)
        SettingSwitchRow(
            title = stringResource(R.string.auto_crop),
            summary = stringResource(if (state.autoCropEnabled) R.string.setting_on else R.string.setting_off),
            checked = state.autoCropEnabled,
            onCheckedChange = onAutoCropChanged,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.scan_resolution), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanResolution.entries.forEach { resolution ->
                    SettingChoice(
                        label = stringResource(resolution.labelResource),
                        selected = state.resolutionMode == resolution,
                        onClick = { onResolutionChanged(resolution) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.flash_mode), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.flash_auto to 0, R.string.flash_on to 1, R.string.flash_off to 2).forEach { (label, mode) ->
                    SettingChoice(
                        label = stringResource(label),
                        selected = state.flashMode == mode,
                        onClick = { onFlashModeChanged(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        SettingSwitchRow(
            title = stringResource(R.string.multi_frame_enabled),
            summary = stringResource(if (state.multiFrameEnabled) R.string.setting_on else R.string.setting_off),
            checked = state.multiFrameEnabled,
            onCheckedChange = onMultiFrameChanged,
        )
        SettingSwitchRow(
            title = stringResource(R.string.torch),
            summary = stringResource(if (state.isTorchEnabled) R.string.setting_on else R.string.setting_off),
            checked = state.isTorchEnabled,
            onCheckedChange = onTorchChanged,
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingChoice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CapturedImage(image: ImageBitmap, thumbnailCoords: MutableState<Offset>) {
    Surface(
        color = Color.Black.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxSize(),
    ) {}

    var isAnimating by remember { mutableStateOf(false) }
    LaunchedEffect(image) {
        delay(CAPTURED_IMAGE_DISPLAY_DURATION - ANIMATION_DURATION)
        isAnimating = true
    }
    var targetOffsetX by remember { mutableFloatStateOf(0f) }
    var targetOffsetY by remember { mutableFloatStateOf(0f) }

    val transition = updateTransition(targetState = isAnimating, label = "captureAnimation")
    val tween = tween<Float>(durationMillis = ANIMATION_DURATION)
    val offsetX by transition.animateFloat({ tween }, "offsetX") { if (it) targetOffsetX else 0f }
    val offsetY by transition.animateFloat({ tween }, "offsetY") { if (it) targetOffsetY else 0f }
    val scale by transition.animateFloat({ tween }, "scale") { if (it) 0.3f else 1f }

    val density = LocalDensity.current
    Box (contentAlignment = Alignment.BottomStart,
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                val centerX = bounds.left + bounds.width / 2
                val centerY = bounds.top + bounds.height / 2
                with(density) {
                    targetOffsetX = thumbnailCoords.value.x - centerX
                    targetOffsetY = thumbnailCoords.value.y - centerY
                }
            }
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .graphicsLayer {
                    translationX = offsetX
                    translationY = offsetY
                    scaleX = scale
                    scaleY = scale
                }
        )
    }
}

@Composable
fun CaptureButton(onClick: () -> Unit, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(80.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .border(
                    width = 4.dp,
                    color = color,
                    shape = CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(color = color, shape = CircleShape)
        )
    }
}

@Composable
private fun CameraPreviewWithOverlay(
    cameraPreview: @Composable () -> Unit,
    cameraUiState: CameraUiState,
    onCertificateTemplateChanged: (CertificateScanTemplate) -> Unit,
    modifier: Modifier,
) {
    val captureState = cameraUiState.captureState

    var showShutter by remember { mutableStateOf(false) }
    LaunchedEffect(captureState.frozenImage) {
        if (captureState.frozenImage != null) {
            showShutter = true
            delay(200)
            showShutter = false
        }
    }

    Box(
        modifier = if (cameraUiState.isLandscape) {
            modifier.fillMaxHeight().aspectRatio(4f / 3f)
        } else {
            modifier.fillMaxWidth().aspectRatio(3f / 4f)
        }
    ) {
        cameraPreview()
        AnalysisOverlay(cameraUiState.liveAnalysisState, cameraUiState.isDebugMode)
        if (cameraUiState.isCertificateScan) {
            CertificateScanGuide(
                pageCount = cameraUiState.pageCount,
                template = cameraUiState.certificateTemplate,
                autoCropEnabled = cameraUiState.autoCropEnabled,
                onTemplateSelected = onCertificateTemplateChanged,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        captureState.frozenImage?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
            )

        }
        if (showShutter) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.6f))
            )
        }
        if (cameraUiState.showCaptureError) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.error_occurred),
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun FocusOverlay(focusPoint: Offset?) {
    if (focusPoint == null) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val size = 80f
        drawRect(
            color = Color.White,
            topLeft = Offset(
                focusPoint.x - size / 2,
                focusPoint.y - size / 2
            ),
            size = Size(size, size),
            style = Stroke(width = 3f)
        )
    }
}

@Composable
fun MessageBox(inferenceTime: Long) {
    Text(
        text = if(inferenceTime == 0L) "" else "Segmentation time: $inferenceTime ms",
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        color = Color.Gray,
    )
}

@Composable
private fun Bar(
    pageCount: Int,
    onFinalizePressed: () -> Unit,
    onImportClicked: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(
            onClick = onImportClicked,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.import_photos),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        MainActionButton(
            onClick = onFinalizePressed,
            enabled = pageCount > 0,
            text = pageCountText(pageCount),
            icon = Icons.Default.Done,
        )
    }
}

@Composable
private fun CameraPermissionRationale(onRequestCameraPermission: () -> Unit, modifier:Modifier) {
    Box (modifier = modifier, contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.camera_permission_rationale),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onRequestCameraPermission,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(stringResource(R.string.grant_permission))
                }
            }
        }
    }
}

@Preview(name="Pixel 1", showSystemUi = true, device = Devices.PIXEL)
@Preview(name="Pixel 4", showSystemUi = true, device = Devices.PIXEL_4)
@Preview(name="Pixel 9 pro XL", showSystemUi = true, device = Devices.PIXEL_9_PRO_XL)
@Composable
fun CameraScreenPreview() {
    ScreenPreview(CaptureState.Idle)
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun CameraScreenPreviewWithProcessedImage() {
    val p = Point(0 , 0)
    val quad = Quad(p, p, p, p)
    ScreenPreview(CaptureState.CapturePreview(
        debugImage("uncropped/img01.jpg").toBitmap(),
        CapturedPage(
            debugImage("gallica.bnf.fr-bpt6k5530456s-1.jpg"),
            CompletableDeferred(Jpeg(ByteArray(0))),
            PageMetadata(quad, R0, ColorMode.COLOR, null, null),
            ColorMode.COLOR)))
}

@Preview(showBackground = true, widthDp = 640, heightDp = 320)
@Composable
fun CameraScreenPreviewInLandscapeMode() {
    ScreenPreview(CaptureState.Idle, rotationDegrees = 90f)
}

@Preview
@Composable
fun CameraScreenPreviewWithNoPermission() {
    ScreenPreview(CaptureState.Idle, isCameraPermissionGranted = false)
}

@Preview
@Composable
fun CameraScreenPreviewImporting() {
    ScreenPreview(CaptureState.Idle, ImportState.Importing(2, 3))
}

@Composable
private fun ScreenPreview(
    captureState: CaptureState,
    importState: ImportState = ImportState.Idle,
    rotationDegrees: Float = 0f,
    isCameraPermissionGranted: Boolean = true,
) {
    FreeScanTheme {
        val thumbnailCoords = remember { mutableStateOf(Offset.Zero) }
        CameraScreenScaffold(
            cameraPreview = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.DarkGray),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Image(
                        debugImage("uncropped/img01.jpg").toBitmap().asImageBitmap(),
                        modifier=Modifier.rotate(rotationDegrees),
                        contentDescription = null
                    )
                }
            },
            pageListState =
                CommonPageListState(
                    document = fakeDocument(
                        listOf(1, 2)
                            .map { "gallica.bnf.fr-bpt6k5530456s-$it" }
                            .toImmutableList(),
                        LocalContext.current),
                    onPageClick = {},
                    onPageReorder = { _, _ -> },
                    listState = LazyListState(),
                    showPageNumbers = false,
                ),
            cameraUiState = CameraUiState(4, LiveAnalysisState(), captureState,
                importState, false, rotationDegrees > 0, false, false, 0, ScanResolution.FOUR_K),
            onCapture = {},
            onFinalizePressed = {},
            onDebugModeSwitched = {},
            onTorchChanged = {},
            onFlashModeChanged = {},
            onResolutionChanged = {},
            onMultiFrameChanged = {},
            onAutoCropChanged = {},
            onCertificateTemplateChanged = {},
            thumbnailCoords = thumbnailCoords,
            navigation = dummyNavigation(),
            captureController = CameraCaptureController(),
            isCameraPermissionGranted = isCameraPermissionGranted,
            onRequestCameraPermission = {},
            onImportClicked = {},
        )
    }
}

@Composable
private fun debugImage(imgName: String): Jpeg {
    val context = LocalContext.current
    return Jpeg(context.assets.open(imgName).readBytes())
}
