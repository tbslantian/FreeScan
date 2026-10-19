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

/*
 * Local comparison adapter for the native document detection and rectification pipeline.
 *
 * The scanner models and native payload are packaged in both debug and release builds.
 */
package org.freescan.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.createScaledBitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import com.wibo.doc.jni.DocDetect
import com.wibo.doc.jni.DocPoint
import org.freescan.app.data.Logger
import org.freescan.imageprocessing.ImageSize
import org.freescan.imageprocessing.Point
import org.freescan.imageprocessing.Quad
import org.freescan.imageprocessing.createQuad
import org.freescan.imageprocessing.trimDarkFrame
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File
import kotlin.math.abs

class LocalDocumentScanner(
    context: Context,
    private val logger: Logger,
) {
    private val appContext = context.applicationContext
    private val initializationLock = Any()
    private val processingLock = Any()

    @Volatile
    private var initialized = false
    @Volatile
    private var initializationFailed = false

    data class PreviewDetection(val quad: Quad, val imageSize: ImageSize)

    fun detectPreview(source: Bitmap, rotationDegrees: Int): PreviewDetection? {
        val oriented = rotateForDetection(source, rotationDegrees)
        try {
            if (!ensureInitialized()) return null
            return synchronized(processingLock) {
                val detectorInput = downsampleForDetection(oriented)
                try {
                    val seedPoints = arrayOf(
                        DocPoint(0f, 0f), DocPoint(detectorInput.width.toFloat(), 0f),
                        DocPoint(detectorInput.width.toFloat(), detectorInput.height.toFloat()),
                        DocPoint(0f, detectorInput.height.toFloat()),
                    )
                    val detected = DocDetect.runDocDetect(detectorInput, seedPoints, PREVIEW_MODEL_ID)
                    val points = detected?.mapNotNull { it as? DocPoint }
                        ?.takeIf { it.size == 4 }
                        ?.map { point ->
                            DocPoint(
                                point.getX() * oriented.width / detectorInput.width,
                                point.getY() * oriented.height / detectorInput.height,
                            )
                        }?.toTypedArray() ?: return@synchronized null
                    if (!isUsableQuad(points, oriented.width, oriented.height)) return@synchronized null
                    PreviewDetection(
                        createQuad(points.map { Point(it.getX().toDouble(), it.getY().toDouble()) }),
                        ImageSize(oriented.width, oriented.height),
                    )
                } finally {
                    if (detectorInput !== oriented && !detectorInput.isRecycled) detectorInput.recycle()
                }
            }
        } catch (error: Exception) {
            logger.e(TAG, "Native preview detection failed", error)
            return null
        } catch (error: LinkageError) {
            logger.e(TAG, "Native preview detector unavailable", error)
            return null
        } finally {
            if (oriented !== source && !oriented.isRecycled) oriented.recycle()
        }
    }

    fun rectify(source: Bitmap, rotationDegrees: Int): Bitmap? {
        val oriented = rotateForDetection(source, rotationDegrees)
        try {
            if (!ensureInitialized()) return null

            return synchronized(processingLock) {
                val startedAt = SystemClock.elapsedRealtime()
                val detectorInput = downsampleForDetection(oriented)
                try {
                    val scaleX = oriented.width.toFloat() / detectorInput.width
                    val scaleY = oriented.height.toFloat() / detectorInput.height
                    val seedPoints = arrayOf(
                        DocPoint(0f, 0f),
                        DocPoint(detectorInput.width.toFloat(), 0f),
                        DocPoint(detectorInput.width.toFloat(), detectorInput.height.toFloat()),
                        DocPoint(0f, detectorInput.height.toFloat()),
                    )
                    val detected = DocDetect.runDocDetect(detectorInput, seedPoints, STILL_IMAGE_MODEL_ID)
                    val points = detected
                        ?.mapNotNull { it as? DocPoint }
                        ?.takeIf { it.size == 4 }
                        ?.map { point -> DocPoint(point.getX() * scaleX, point.getY() * scaleY) }
                        ?.toTypedArray()

                    if (points == null || !isUsableQuad(points, oriented.width, oriented.height)) {
                        Log.w(TAG, "Native document detector did not return four usable corners")
                        return@synchronized null
                    }

                    val rectified = DocDetect.runDocRectify(
                        oriented,
                        points,
                        Bitmap.Config.ARGB_8888,
                    ) as? Bitmap
                    if (rectified == null || rectified.isRecycled || rectified.width < 2 || rectified.height < 2) {
                        Log.w(TAG, "Native perspective rectifier returned no bitmap")
                        return@synchronized null
                    }

                    Log.i(
                        TAG,
                        "Local document path succeeded in ${SystemClock.elapsedRealtime() - startedAt}ms: " +
                            "input=${oriented.width}x${oriented.height}, detection=${detectorInput.width}x${detectorInput.height}, " +
                            "output=${rectified.width}x${rectified.height}, " +
                            "corners=${points.joinToString { "(${it.getX().toInt()},${it.getY().toInt()})" }}",
                    )
                    val trimmed = try {
                        trimRectifiedFrame(rectified)
                    } catch (error: Exception) {
                        rectified.recycle()
                        throw error
                    }
                    if (trimmed !== rectified && !rectified.isRecycled) rectified.recycle()
                    trimmed
                } finally {
                    if (detectorInput !== oriented && !detectorInput.isRecycled) detectorInput.recycle()
                }
            }
        } catch (error: Exception) {
            logger.e(TAG, "Local native document processing failed; FreeScan fallback will be used", error)
            return null
        } catch (error: LinkageError) {
            logger.e(TAG, "Local native scanner library could not be loaded; FreeScan fallback will be used", error)
            return null
        } finally {
            if (oriented !== source && !oriented.isRecycled) oriented.recycle()
        }
    }

    private fun trimRectifiedFrame(rectified: Bitmap): Bitmap {
        val rgba = Mat()
        var trimmed: Mat? = null
        try {
            Utils.bitmapToMat(rectified, rgba)
            val cropped = trimDarkFrame(rgba)
            trimmed = cropped
            if (cropped.cols() == rectified.width && cropped.rows() == rectified.height) return rectified
            val result = Bitmap.createBitmap(cropped.cols(), cropped.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(cropped, result)
            return result
        } finally {
            trimmed?.release()
            rgba.release()
        }
    }

    private fun ensureInitialized(): Boolean {
        if (initialized) return true
        if (initializationFailed) return false
        return synchronized(initializationLock) {
            if (initialized) return@synchronized true
            if (initializationFailed) return@synchronized false

            try {
                val edgeModel = appContext.assets.open(EDGE_MODEL_ASSET).use { it.readBytes() }
                val detectorModel = appContext.assets.open(DETECTOR_MODEL_ASSET).use { it.readBytes() }
                val cacheDirectory = File(appContext.cacheDir, "local-document-scanner")
                if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
                    throw IllegalStateException("Could not create native model cache directory")
                }
                val preprocessCache = File(cacheDirectory, "predodetect.bin").absolutePath
                val detectorCache = File(cacheDirectory, "docdetect.bin").absolutePath

                val previewReady = DocDetect.initDocDetectModel(
                    edgeModel, ENGINE_MODE, false, preprocessCache, detectorCache,
                    PREVIEW_MODEL_ID, detectorModel,
                )
                val stillReady = DocDetect.initDocDetectModel(
                    edgeModel, ENGINE_MODE, false, preprocessCache, detectorCache,
                    STILL_IMAGE_MODEL_ID, detectorModel,
                )
                if (!previewReady || !stillReady) {
                    releaseModels()
                    initializationFailed = true
                    Log.e(TAG, "Native model initialization failed (preview=$previewReady, still=$stillReady)")
                    return@synchronized false
                }

                initialized = true
                Log.i(TAG, "Native detector models initialized")
                true
            } catch (error: Exception) {
                logger.e(TAG, "Could not initialize the local native scanner models", error)
                releaseModels()
                initializationFailed = true
                false
            } catch (error: LinkageError) {
                logger.e(TAG, "Could not load the local native scanner libraries", error)
                initializationFailed = true
                false
            }
        }
    }

    private fun releaseModels() {
        try {
            DocDetect.releaseDocModel(PREVIEW_MODEL_ID)
            DocDetect.releaseDocModel(STILL_IMAGE_MODEL_ID)
        } catch (_: Exception) {
            // A partially initialized native engine may not have a releasable model.
        } catch (_: LinkageError) {
            // Keep the fallback path available when a device ABI has no bundled library.
        }
    }

    private fun rotateForDetection(source: Bitmap, degrees: Int): Bitmap {
        val rotation = ((degrees % 360) + 360) % 360
        if (rotation == 0) return source
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun downsampleForDetection(source: Bitmap): Bitmap {
        val largestSide = maxOf(source.width, source.height)
        if (largestSide <= MAX_DETECTION_SIDE) return source
        val scale = MAX_DETECTION_SIDE.toFloat() / largestSide
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return createScaledBitmap(source, width, height, true)
    }

    private fun isUsableQuad(points: Array<DocPoint>, width: Int, height: Int): Boolean {
        if (points.size != 4 || width < 2 || height < 2) return false

        val maxX = (width - 1).toFloat()
        val maxY = (height - 1).toFloat()
        for (point in points) {
            if (!point.getX().isFinite() || !point.getY().isFinite()) return false
            if (point.getX() < -width * 0.05f || point.getX() > width * 1.05f ||
                point.getY() < -height * 0.05f || point.getY() > height * 1.05f
            ) return false
            point.setX(point.getX().coerceIn(0f, maxX))
            point.setY(point.getY().coerceIn(0f, maxY))
        }

        var twiceArea = 0.0
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            twiceArea += current.getX() * next.getY() - next.getX() * current.getY()
        }
        val areaRatio = abs(twiceArea) / 2.0 / (width.toDouble() * height.toDouble())
        if (areaRatio < MIN_DOCUMENT_AREA_RATIO || areaRatio > 1.05) return false

        var orientation = 0.0
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            val c = points[(index + 2) % points.size]
            val cross = (b.getX() - a.getX()) * (c.getY() - b.getY()) -
                (b.getY() - a.getY()) * (c.getX() - b.getX())
            if (abs(cross) < 1.0) return false
            if (orientation == 0.0) orientation = kotlin.math.sign(cross.toDouble())
            else if (kotlin.math.sign(cross.toDouble()) != orientation) return false
        }
        return true
    }

    private companion object {
        const val TAG = "LocalDocumentScanner"
        const val EDGE_MODEL_ASSET = "scanner_models/v5.vaimlite"
        const val DETECTOR_MODEL_ASSET = "scanner_models/yolo_fp16.vaimlite"
        const val ENGINE_MODE = 1
        const val PREVIEW_MODEL_ID = 0
        const val STILL_IMAGE_MODEL_ID = 1
        const val MAX_DETECTION_SIDE = 1600
        const val MIN_DOCUMENT_AREA_RATIO = 0.03
    }
}
