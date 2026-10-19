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

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.freescan.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freescan.app.AppContainer
import org.freescan.app.domain.CapturedPage
import org.freescan.app.domain.ScanResolution
import org.freescan.app.platform.extractDocumentFromBitmap
import org.freescan.imageprocessing.ImageSize
import org.freescan.imageprocessing.Mode
import org.freescan.imageprocessing.OpticalMeasures
import org.freescan.imageprocessing.detectDocumentQuad
import java.util.concurrent.CancellationException

sealed interface CameraEvent {
    data class ImageCaptured(val page: CapturedPage) : CameraEvent
}

class CameraViewModel(appContainer: AppContainer): ViewModel() {

    private val imageSegmentationService = appContainer.imageSegmentationService
    private val localDocumentScanner = appContainer.localDocumentScanner
    private val settingsRepository = appContainer.settingsRepository
    private val imageLoader = appContainer.imageLoader
    private val logger = appContainer.logger

    private val _events = MutableSharedFlow<CameraEvent>()
    val events = _events.asSharedFlow()

    private var _liveAnalysisState = MutableStateFlow(LiveAnalysisState())
    val liveAnalysisState: StateFlow<LiveAnalysisState> = _liveAnalysisState.asStateFlow()
    private var quadStabilizer = QuadStabilizer()
    @Volatile
    private var segmentationDisabled = false
    private var lastPreviewWasNative = false
    private var lastAnalysisStartedAt = 0L

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState
    private var importJob: Job? = null

    private val _isTorchEnabled = MutableStateFlow(false)
    val isTorchEnabled: StateFlow<Boolean> = _isTorchEnabled

    // 0: Auto, 1: On, 2: Off
    private val _flashMode = MutableStateFlow(0)
    val flashMode: StateFlow<Int> = _flashMode

    private val _resolutionMode = MutableStateFlow(ScanResolution.FOUR_K)
    val resolutionMode: StateFlow<ScanResolution> = _resolutionMode

    private val _multiFrameEnabled = MutableStateFlow(false)
    val multiFrameEnabled: StateFlow<Boolean> = _multiFrameEnabled

    private val _autoCropEnabled = MutableStateFlow(true)
    val autoCropEnabled: StateFlow<Boolean> = _autoCropEnabled

    private val _isCertificateScan = MutableStateFlow(false)
    val isCertificateScan: StateFlow<Boolean> = _isCertificateScan
    private var autoCropBeforeCertificateScan: Boolean? = null

    private val _certificateTemplate = MutableStateFlow(CertificateScanTemplate.ID_CARD)
    val certificateTemplate: StateFlow<CertificateScanTemplate> = _certificateTemplate.asStateFlow()

    private val _volumeKeyEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val volumeKeyEvent = _volumeKeyEvent.asSharedFlow()

    fun onVolumeKeyPressed() {
        _volumeKeyEvent.tryEmit(Unit)
    }

    fun beginDocumentScan() {
        endCertificateScan()
    }

    fun beginCertificateScan() {
        if (!_isCertificateScan.value) {
            autoCropBeforeCertificateScan = _autoCropEnabled.value
        }
        _isCertificateScan.value = true
        _autoCropEnabled.value = true
        _captureState.value = CaptureState.Idle
        cancelImport()
        resetLiveAnalysis()
    }

    fun endCertificateScan() {
        if (!_isCertificateScan.value) return
        _isCertificateScan.value = false
        autoCropBeforeCertificateScan?.let { _autoCropEnabled.value = it }
        autoCropBeforeCertificateScan = null
        resetLiveAnalysis()
    }

    fun resetLiveAnalysis() {
        quadStabilizer = QuadStabilizer()
        lastPreviewWasNative = false
        _liveAnalysisState.value = LiveAnalysisState()
    }

    fun onCapturePressed(frozenImage: Bitmap) {
        _captureState.value = CaptureState.Capturing(frozenImage)
        resetLiveAnalysis()
    }

    private fun onCaptureProcessed(captured: CapturedPage?) {
        val current = _captureState.value
        _captureState.value = when {
            current is CaptureState.Capturing && captured != null ->
                CaptureState.CapturePreview(current.frozenImage, captured)
            current is CaptureState.Capturing ->
                CaptureState.CaptureError(current.frozenImage)
            else -> CaptureState.Idle
        }
    }

    fun liveAnalysis(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!_autoCropEnabled.value || _captureState.value !is CaptureState.Idle ||
            _importState.value !is ImportState.Idle || now - lastAnalysisStartedAt < 120L) {
            imageProxy.close()
            return
        }
        lastAnalysisStartedAt = now

        viewModelScope.launch {
            var frame: Bitmap? = null
            try {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val currentFrame = withContext(Dispatchers.IO) { imageProxy.toBitmap() }
                frame = currentFrame
                val nativeDetection = if (BuildConfig.SCANLAB_ENABLED) {
                    withContext(Dispatchers.IO) { localDocumentScanner.detectPreview(currentFrame, rotationDegrees) }
                } else null
                if (nativeDetection != null) {
                    if (!lastPreviewWasNative) quadStabilizer = QuadStabilizer()
                    lastPreviewWasNative = true
                    val stableQuad = quadStabilizer.update(nativeDetection.quad)
                    if (_autoCropEnabled.value) {
                        _liveAnalysisState.value = LiveAnalysisState(
                            maskSize = nativeDetection.imageSize,
                            stableQuad = stableQuad,
                        )
                    }
                    return@launch
                }
                if (segmentationDisabled) {
                    resetLiveAnalysis()
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    imageSegmentationService.runSegmentationAndReturn(currentFrame)
                }
                result?.let {
                    val segmentation = result.segmentation
                    val maskSize = segmentation.maskSize()
                    val orientedMaskSize = if (rotationDegrees % 180 == 0) maskSize
                        else ImageSize(maskSize.height, maskSize.width)
                    val originalSize = ImageSize(imageProxy.width, imageProxy.height)
                    val rawQuad = withContext(Dispatchers.Default) {
                        detectDocumentQuad(segmentation, originalSize, Mode.LIVE_ANALYSIS)
                            ?.rotate90(rotationDegrees / 90, maskSize)
                    }
                    if (lastPreviewWasNative) quadStabilizer = QuadStabilizer()
                    lastPreviewWasNative = false
                    val binaryMaskProvider = { ->
                        var binaryMask: Bitmap = segmentation.toBinaryMask()
                        if (rotationDegrees != 0) {
                            binaryMask = rotateBitmap(binaryMask, rotationDegrees.toFloat())
                        }
                        binaryMask
                    }
                    val stableQuad = quadStabilizer.update(rawQuad)
                    if (_autoCropEnabled.value) {
                        _liveAnalysisState.value = LiveAnalysisState(
                            inferenceTime = result.inferenceTime,
                            binaryMaskProvider = binaryMaskProvider,
                            maskSize = orientedMaskSize,
                            stableQuad = stableQuad,
                        )
                    }
                }
                if (result == null) resetLiveAnalysis()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                segmentationDisabled = true
                _liveAnalysisState.value = LiveAnalysisState()
                logger.e("Camera", "Live segmentation failed; native preview detection remains available", error)
            } catch (error: LinkageError) {
                segmentationDisabled = true
                _liveAnalysisState.value = LiveAnalysisState()
                logger.e("Camera", "Live segmentation library is unavailable; native preview detection remains available", error)
            } finally {
                frame?.let { if (!it.isRecycled) it.recycle() }
                imageProxy.close()
            }
        }
    }

    fun onImageCaptured(
        imageProxy: ImageProxy?,
        opticalMeasures: OpticalMeasures?,
        scanResolution: ScanResolution = _resolutionMode.value,
        autoCropEnabled: Boolean = _autoCropEnabled.value,
    ) {
        if (imageProxy != null) {
            viewModelScope.launch {
                var source: Bitmap? = null
                var proxyClosed = false
                try {
                    val capturedBitmap = imageProxy.toBitmap()
                    source = capturedBitmap
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    imageProxy.close()
                    proxyClosed = true
                    val page =
                        processCapturedImage(capturedBitmap, rotationDegrees, opticalMeasures, scanResolution, autoCropEnabled)
                    page.sourceJpeg.invokeOnCompletion {
                        if (!capturedBitmap.isRecycled) capturedBitmap.recycle()
                    }
                    source = null
                    onCaptureProcessed(page)
                } catch (e: RuntimeException) {
                    logger.e("Camera", "Failed to process captured image", e)
                    source?.let { if (!it.isRecycled) it.recycle() }
                    if (!proxyClosed) imageProxy.close()
                    onCaptureProcessed(null)
                }
            }
        } else {
            onCaptureProcessed(null)
        }
    }

    private suspend fun processCapturedImage(
        source: Bitmap,
        rotationDegrees: Int,
        opticalMeasures: OpticalMeasures?,
        scanResolution: ScanResolution? = null,
        autoCropEnabled: Boolean = true,
    ): CapturedPage = withContext(Dispatchers.IO) {
        // Camera captures and imported photos share the same local detection and
        // rectification path whenever automatic cropping is enabled.
        if (BuildConfig.SCANLAB_ENABLED && autoCropEnabled) {
            val rectified = localDocumentScanner.rectify(source, rotationDegrees)
            if (rectified != null) {
                var sourceCompressionOwnsBitmap = false
                try {
                    val defaultColorMode = settingsRepository.defaultColorMode.first()
                    val capturedPage = extractDocumentFromBitmap(
                        rectified,
                        quadInMask = null,
                        rotationDegrees = 0,
                        mask = null,
                        viewModelScope = viewModelScope,
                        defaultColorMode = defaultColorMode,
                        opticalMeasures = opticalMeasures,
                        maxDimension = scanResolution?.maxDimension,
                        jpegQualityOverride = scanResolution?.jpegQuality,
                        alreadyRectified = true,
                    )
                    capturedPage.sourceJpeg.invokeOnCompletion {
                        if (!rectified.isRecycled) rectified.recycle()
                    }
                    sourceCompressionOwnsBitmap = true
                    return@withContext capturedPage
                } finally {
                    if (!sourceCompressionOwnsBitmap && !rectified.isRecycled) rectified.recycle()
                }
            }
        }

        val segmentation = if (autoCropEnabled) {
            imageSegmentationService.runSegmentationAndReturn(source)
        } else {
            null
        }
        val mask = segmentation?.segmentation
        val originalSize = ImageSize(source.width, source.height)
        val quad = mask?.let { detectDocumentQuad(mask, originalSize, Mode.CAPTURE) }
        val defaultColorMode = settingsRepository.defaultColorMode.first()
        val result = extractDocumentFromBitmap(
            source, quad, rotationDegrees, mask, viewModelScope, defaultColorMode, opticalMeasures,
            scanResolution?.maxDimension, scanResolution?.jpegQuality,
            deskewText = autoCropEnabled)
        return@withContext result
    }

    fun addProcessedImage() {
        val current = _captureState.value
        if (current is CaptureState.CapturePreview) {
            viewModelScope.launch {
                _events.emit(CameraEvent.ImageCaptured(current.capturedPage))
            }
        }
        _captureState.value = CaptureState.Idle
    }

    fun afterCaptureError() {
        _captureState.value = CaptureState.Idle
    }

    fun logError(message:String, throwable: Throwable) {
        viewModelScope.launch {
            logger.e("Camera", message, throwable)
        }
    }

    fun setTorchEnabled(enabled: Boolean) {
        _isTorchEnabled.value = enabled
    }

    fun cycleFlashMode() {
        _flashMode.value = (_flashMode.value + 1) % 3
    }

    fun setFlashMode(mode: Int) {
        _flashMode.value = mode.coerceIn(0, 2)
    }

    fun cycleResolutionMode() {
        _resolutionMode.value = _resolutionMode.value.next()
    }

    fun setResolutionMode(mode: ScanResolution) {
        _resolutionMode.value = mode
    }

    fun toggleMultiFrame() {
        _multiFrameEnabled.value = !_multiFrameEnabled.value
    }

    fun setMultiFrameEnabled(enabled: Boolean) {
        _multiFrameEnabled.value = enabled
    }

    fun setAutoCropEnabled(enabled: Boolean) {
        _autoCropEnabled.value = enabled
        if (!enabled) resetLiveAnalysis()
    }

    fun setCertificateTemplate(template: CertificateScanTemplate) {
        _certificateTemplate.value = template
    }

    fun importPhotos(uris: List<Uri>) {
        importJob?.cancel()
        if (uris.isEmpty()) {
            _importState.value = ImportState.Idle
            return
        }
        importJob = viewModelScope.launch {
            _importState.value = ImportState.Importing(0, uris.size)
            uris.forEachIndexed { index, uri ->
                ensureActive()
                var photoToImport: Bitmap? = null
                var sourceCompressionOwnsBitmap = false
                try {
                    val importedBitmap = imageLoader.load(uri)
                    photoToImport = importedBitmap
                    ensureActive()
                    // Photo imports always use the camera's automatic-crop processing path.
                    val page = processCapturedImage(
                        importedBitmap,
                        rotationDegrees = 0,
                        opticalMeasures = null,
                        autoCropEnabled = true,
                    )
                    page.sourceJpeg.invokeOnCompletion {
                        if (!importedBitmap.isRecycled) importedBitmap.recycle()
                    }
                    sourceCompressionOwnsBitmap = true
                    ensureActive()
                    _events.emit(CameraEvent.ImageCaptured(page))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e("Import", "Failed to import image: $uri", e)
                } finally {
                    if (!sourceCompressionOwnsBitmap) {
                        photoToImport?.let { if (!it.isRecycled) it.recycle() }
                    }
                }
                _importState.value = ImportState.Importing(index + 1, uris.size)
            }
            _importState.value = ImportState.Idle
        }
    }

    fun onImportClicked() {
        _importState.value = ImportState.Selecting
        resetLiveAnalysis()
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _importState.value = ImportState.Idle
    }
}

sealed class CaptureState {
    open val frozenImage: Bitmap? = null

    object Idle : CaptureState()
    data class Capturing(override val frozenImage: Bitmap) : CaptureState()
    data class CaptureError(override val frozenImage: Bitmap) : CaptureState()
    data class CapturePreview(
        override val frozenImage: Bitmap,
        val capturedPage: CapturedPage,
    ) : CaptureState()
}

fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(angle)
    return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true)
}
