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
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Size
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.scale
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.freescan.app.R
import org.freescan.app.domain.ScanResolution
import org.freescan.app.ui.components.CameraPermissionState
import org.freescan.imageprocessing.CameraIntrinsics
import org.freescan.imageprocessing.OpticalMeasures
import org.freescan.imageprocessing.Point
import org.freescan.imageprocessing.Quad
import org.freescan.imageprocessing.cameraIntrinsics
import org.freescan.imageprocessing.scaledTo
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onImageAnalyzed: (ImageProxy) -> Unit,
    captureController: CameraCaptureController,
    onPreviewViewReady: (PreviewView) -> Unit,
    onCameraBound: () -> Unit,
    scanResolution: ScanResolution,
    cameraPermission: CameraPermissionState,
    onError: (String, Throwable) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (!cameraPermission.isGranted) {
            cameraPermission.request()
        }
    }

    val cameraProviderFuture by remember {
        mutableStateOf(ProcessCameraProvider.getInstance(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember(lifecycleOwner) { Executors.newSingleThreadExecutor() }
    var cameraProvider: ProcessCameraProvider? by remember { mutableStateOf(null) }

    DisposableEffect(lifecycleOwner, analysisExecutor) {
        onDispose {
            cameraProvider?.unbindAll()
            captureController.cameraControl = null
            captureController.imageCapture = null
            analysisExecutor.shutdown()
        }
    }

    var bindState by remember { mutableStateOf<CameraBindState>(CameraBindState.Idle) }
    var retryKey by remember { mutableStateOf(0) }
    var previewView: PreviewView? by remember { mutableStateOf(null) }

    when (bindState) {
        is CameraBindState.Error -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.camera_unavailable))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.camera_unavailable_hint))
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    previewView = null
                    bindState = CameraBindState.Idle
                    retryKey++
                }) {
                    Text(stringResource(R.string.retry_camera))
                }
            }
        }

        else -> {
            AndroidView(
                modifier = modifier,
                factory = {
                    PreviewView(it).apply {
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        onPreviewViewReady(this)
                        previewView = this
                    }
                }
            )

            LaunchedEffect(previewView, retryKey, scanResolution) {
                val view = previewView ?: return@LaunchedEffect

                try {
                    val provider = withContext(Dispatchers.IO) { cameraProviderFuture.get() }
                    cameraProvider = provider
                    bindCameraUseCases(
                        lifecycleOwner,
                        provider,
                        analysisExecutor,
                        view,
                        onImageAnalyzed,
                        captureController,
                        scanResolution,
                    )
                    bindState = CameraBindState.Bound
                    runCatching { onCameraBound() }.onFailure {
                        Log.w("CameraPreview", "Could not restore camera controls", it)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError("Camera unavailable", e)
                    bindState = CameraBindState.Error(e)
                }
            }
        }
    }

}

@OptIn(ExperimentalCamera2Interop::class)
fun bindCameraUseCases(
    lifecycleOwner: LifecycleOwner,
    cameraProvider: ProcessCameraProvider,
    executor: ExecutorService,
    previewView: PreviewView,
    onImageAnalyzed: (ImageProxy) -> Unit,
    captureController: CameraCaptureController,
    scanResolution: ScanResolution,
) {
    captureController.cameraControl = null
    captureController.imageCapture = null
    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    var lastFailure: Exception? = null

    // Some devices cannot bind preview, analysis and the preferred still stream together.
    for (mode in CameraBindingMode.entries) {
        cameraProvider.unbindAll()
        val preview = Preview.Builder().apply {
            if (mode == CameraBindingMode.PREFERRED) {
                setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build()
                )
            }
        }.build().also { it.surfaceProvider = previewView.surfaceProvider }

        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        if (mode == CameraBindingMode.PREFERRED) {
            // The camera stream follows the selected mode; processing still caps the final
            // image to maxDimension. Request 12 MP for 4K to allow a full 3840 px result.
            val targetWidth = if (scanResolution == ScanResolution.FOUR_K) 4000 else scanResolution.maxDimension
            imageCaptureBuilder.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(targetWidth, targetWidth * 3 / 4),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            Camera2Interop.Extender(imageCaptureBuilder)
                .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let {
                            captureController.lastFocusDistanceDiopters = it
                        }
                    }
                })
        }
        val imageCapture = imageCaptureBuilder.build()
        val imageAnalysis = if (mode == CameraBindingMode.CAPTURE_ONLY) null else {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .apply {
                    if (mode == CameraBindingMode.PREFERRED) {
                        setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .build()
                        )
                    }
                }
                .build()
                .also { it.setAnalyzer(executor, onImageAnalyzed) }
        }

        try {
            val camera = if (imageAnalysis == null) {
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
            } else {
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis)
            }
            captureController.imageCapture = imageCapture
            captureController.cameraControl = camera.cameraControl
            Log.i(
                "CameraPreview",
                "Scan mode=$scanResolution, binding=$mode, capture=${imageCapture.resolutionInfo?.resolution}",
            )
            if (mode != CameraBindingMode.PREFERRED) {
                Log.w("CameraPreview", "Using compatible camera configuration: $mode")
            }
            runCatching {
                captureController.setCameraCharacteristics(Camera2CameraInfo.from(camera.cameraInfo))
            }.onFailure {
                Log.w("CameraPreview", "Camera metadata unavailable; continuing without optical measurements", it)
            }
            return
        } catch (e: Exception) {
            lastFailure = e
            Log.w("CameraPreview", "Camera configuration $mode failed", e)
        }
    }

    cameraProvider.unbindAll()
    throw lastFailure ?: IllegalStateException("No camera configuration could be bound")
}

private enum class CameraBindingMode { PREFERRED, DEFAULT, CAPTURE_ONLY }

@Composable
fun AnalysisOverlay(liveAnalysisState: LiveAnalysisState, debugMode: Boolean) {
    val maskSize = liveAnalysisState.maskSize ?: return
    val targetQuad = liveAnalysisState.stableQuad
    var displayedQuad by remember { mutableStateOf<Quad?>(null) }
    val quadColor = MaterialTheme.colorScheme.primary

    LaunchedEffect(targetQuad) {
        if (targetQuad == null) {
            displayedQuad = null
            return@LaunchedEffect
        }

        while (true) {
            displayedQuad = displayedQuad?.let { current ->
                lerpQuad(current, targetQuad, 0.15f)
            } ?: targetQuad

            withFrameNanos { }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        if (debugMode) {
            val binaryMask = liveAnalysisState.binaryMaskProvider.invoke()
            binaryMask?.let { drawMask(this, it) }
        }
        displayedQuad?.let { quad ->
            val scale = minOf(size.width / maskSize.width, size.height / maskSize.height)
            val offsetX = (size.width - maskSize.width * scale) / 2.0
            val offsetY = (size.height - maskSize.height * scale) / 2.0
            fun Point.onPreview() = Offset((x * scale + offsetX).toFloat(), (y * scale + offsetY).toFloat())
            quad.edges().forEach {
                drawLine(quadColor, it.from.onPreview(), it.to.onPreview(), 3.dp.toPx())
            }
            listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft).forEach {
                drawCircle(quadColor, 7.dp.toPx(), it.onPreview())
                drawCircle(Color.White, 3.dp.toPx(), it.onPreview())
            }
        }
    }
}

private fun drawMask(drawScope: DrawScope, binaryMask: Bitmap) {
    val maskOverlay = replaceColor(binaryMask, Color.Black, Color.Transparent)
    val size = drawScope.size
    drawScope.drawImage(
        maskOverlay.scale(size.width.toInt(), size.height.toInt()).asImageBitmap(),
        colorFilter = ColorFilter.tint(Color(0x8000FF00), BlendMode.SrcIn)
    )
}

fun replaceColor(bitmap: Bitmap, toReplace: Color, replacement: Color): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

    val pixels = IntArray(width * height)
    result.getPixels(pixels, 0, width, 0, 0, width, height)

    val target = toReplace.toArgb()
    val newColor = replacement.toArgb()

    for (i in pixels.indices) {
        if (pixels[i] == target) {
            pixels[i] = newColor
        }
    }

    result.setPixels(pixels, 0, width, 0, 0, width, height)
    return result
}

fun Point.toOffset() = Offset(x.toFloat(), y.toFloat())

class CameraCaptureController {
    var cameraControl: CameraControl? = null
    var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()
    var previewView: PreviewView? = null
    var cameraIntrinsics: CameraIntrinsics? = null
    var canUseFocusDistance = false

    @Volatile
    var lastFocusDistanceDiopters: Float? = null

    fun shutdown() {
        executor.shutdown()
    }

    fun setFlashMode(mode: Int) {
        imageCapture?.flashMode = when (mode) {
            1 -> ImageCapture.FLASH_MODE_ON
            2 -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }
    }

    @Volatile
    private var captureInProgress = false

    fun takePicture(
        frameCount: Int = 1,
        onImageCaptured: (ImageProxy?, OpticalMeasures?) -> Unit,
    ) {
        if (captureInProgress) return
        val capture = imageCapture ?: run {
            onImageCaptured(null, null)
            return
        }
        captureInProgress = true
        val requestedFrames = frameCount.coerceIn(1, 5)
        var completedFrames = 0
        var bestProxy: ImageProxy? = null
        var bestScore = Double.NEGATIVE_INFINITY

        fun finish() {
            captureInProgress = false
            val selected = bestProxy
            bestProxy = null
            val diopters = lastFocusDistanceDiopters
            val subjectDistanceInMm =
                if (canUseFocusDistance && diopters != null && diopters != 0.0f) 1000 / diopters else null
            onImageCaptured(
                selected,
                if (selected == null) null else cameraIntrinsics?.let {
                    OpticalMeasures(it, subjectDistanceInMm)
                }
            )
        }

        fun captureNext() {
            capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val score = sharpnessScore(imageProxy)
                    if (score > bestScore) {
                        bestProxy?.close()
                        bestProxy = imageProxy
                        bestScore = score
                    } else {
                        imageProxy.close()
                    }
                    completedFrames++
                    if (completedFrames >= requestedFrames) finish() else captureNext()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraCapture", "Image capture failed: ${exception.message}", exception)
                    completedFrames++
                    if (completedFrames >= requestedFrames) finish() else captureNext()
                }
            })
        }

        captureNext()
    }

    /** Scores focus from a sparse sample of the luminance plane without allocating bitmaps. */
    private fun sharpnessScore(image: ImageProxy): Double {
        val plane = image.planes.firstOrNull() ?: return 0.0
        val buffer = plane.buffer.duplicate()
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val step = maxOf(4, maxOf(width, height) / 500)
        var total = 0L
        var samples = 0
        for (y in height / 10 until height * 9 / 10 step step) {
            for (x in width / 10 until width * 9 / 10 step step) {
                val index = y * rowStride + x * pixelStride
                val center = buffer.get(index).toInt() and 0xff
                val left = buffer.get(index - pixelStride).toInt() and 0xff
                val right = buffer.get(index + pixelStride).toInt() and 0xff
                val up = buffer.get(index - rowStride).toInt() and 0xff
                val down = buffer.get(index + rowStride).toInt() and 0xff
                total += kotlin.math.abs(4 * center - left - right - up - down)
                samples++
            }
        }
        return if (samples == 0) 0.0 else total.toDouble() / samples
    }

    fun tapToFocus(tapOffset: Offset) {
        val view = previewView ?: return
        val control = cameraControl ?: return

        val factory = view.meteringPointFactory
        val point = factory.createPoint(tapOffset.x, tapOffset.y)

        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()

        control.startFocusAndMetering(action)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun setCameraCharacteristics(cameraInfo: Camera2CameraInfo) {
        val focalLengths = cameraInfo.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
        )
        val sensorSize = cameraInfo.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
        )
        cameraIntrinsics =
            if (focalLengths == null || focalLengths.size != 1 || sensorSize == null) {
                null
            } else {
                cameraIntrinsics(focalLengths[0], max(sensorSize.width, sensorSize.height))
            }
        val calibration = cameraInfo.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION
        )
        canUseFocusDistance =
               calibration == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED
            || calibration == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE
    }
}

sealed interface CameraBindState {
    object Idle : CameraBindState
    object Bound : CameraBindState
    data class Error(val throwable: Throwable) : CameraBindState
}
