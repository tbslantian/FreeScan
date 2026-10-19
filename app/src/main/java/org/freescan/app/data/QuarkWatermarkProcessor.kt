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

package org.freescan.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.util.Log
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.freescan.app.platform.QrRegionRepair
import org.freescan.app.platform.QrWatermarkDetector
import org.freescan.app.platform.WatermarkRegion
import org.freescan.app.platform.WatermarkKind
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/** Keeps the automatic Quark workflow and additionally removes QR codes anywhere on a page. */
class QuarkWatermarkProcessor(private val context: Context) {
    data class Result(
        val uri: Uri, val fileName: String, val marksRemoved: Int,
        val pageCount: Int, val modifiedPages: Int,
    )
    enum class Phase { DETECT, REPAIR, SAVE }
    data class Progress(val pageNumber: Int, val pageCount: Int, val phase: Phase)
    data class ReviewPage(
        val preview: Bitmap, val regions: List<WatermarkRegion>,
        val pageNumber: Int, val pageCount: Int, val isPdf: Boolean,
    )
    private val detector = QrWatermarkDetector()

    suspend fun process(
        uri: Uri,
        onProgress: suspend (Progress) -> Unit = {},
        review: (suspend (ReviewPage) -> List<WatermarkRegion>)? = null,
    ): Result {
        currentCoroutineContext().ensureActive()
        val name = displayName(uri)
        val mime = context.contentResolver.getType(uri)
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()
        val isPdf = mime == "application/pdf" ||
            mime == "application/x-pdf" ||
            mime.endsWith("+pdf") ||
            name.endsWith(".pdf", ignoreCase = true) ||
            hasPdfSignature(uri)

        return if (isPdf) {
            processPdf(uri, name, onProgress, review)
        } else if (mime.startsWith("image/") || name.hasImageExtension()) {
            processImage(uri, name, onProgress, review)
        } else {
            error("仅支持 PDF、JPG 和 PNG 文件")
        }
    }

    /** Some share providers expose PDFs as application/octet-stream or omit the file name. */
    private fun hasPdfSignature(uri: Uri): Boolean {
        val header = ByteArray(PDF_HEADER_SCAN_BYTES)
        val bytesRead = context.contentResolver.openInputStream(uri)?.use { input ->
            var total = 0
            while (total < header.size) {
                val count = input.read(header, total, header.size - total)
                if (count <= 0) break
                total += count
            }
            total
        } ?: return false

        if (bytesRead < PDF_SIGNATURE.length) return false
        val prefix = String(header, 0, bytesRead, Charsets.ISO_8859_1)
        return prefix.contains(PDF_SIGNATURE)
    }

    private suspend fun processImage(
        uri: Uri, sourceName: String, onProgress: suspend (Progress) -> Unit,
        review: (suspend (ReviewPage) -> List<WatermarkRegion>)?,
    ): Result {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            // Bounds-only decoding returns null even for a valid image.
            BitmapFactory.decodeStream(it, null, bounds)
            Unit
        } ?: error("无法读取所选图片")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别图片格式" }
        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_EDGE, MAX_IMAGE_PIXELS)
        val decoded = decodeImage(uri, sample) ?: error("无法解码所选图片")
        val bitmap = try {
            decoded.copy(Bitmap.Config.ARGB_8888, true) ?: error("无法准备图片")
        } finally { decoded.recycle() }
        try {
            val regions = selectRegions(bitmap, 1, 1, false, onProgress, review)
            require(regions.isNotEmpty()) { "没有可处理的二维码或夸克标识，可使用“选择区域”手动补框；原文件未修改" }
            onProgress(Progress(1, 1, Phase.REPAIR))
            QrRegionRepair.erase(bitmap, regions)
            currentCoroutineContext().ensureActive()
            onProgress(Progress(1, 1, Phase.SAVE))
            val outputName = cleanedName(sourceName, ".png")
            val outputUri = writeToDownloads(outputName, "image/png") { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "图片保存失败" }
            }
            return Result(outputUri, displayName(outputUri), regions.size, 1, 1)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun processPdf(
        uri: Uri, sourceName: String, onProgress: suspend (Progress) -> Unit,
        review: (suspend (ReviewPage) -> List<WatermarkRegion>)?,
    ): Result {
        val outputName = cleanedName(sourceName, ".pdf")
        val stagedPdf = File.createTempFile("quark-input-", ".pdf", context.cacheDir)
        var markedPages = 0
        var marksRemoved = 0
        var pageCount = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                stagedPdf.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("无法读取所选 PDF")
            val outputUri = writeToDownloads(outputName, "application/pdf") { out ->
                ParcelFileDescriptor.open(stagedPdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        pageCount = renderer.pageCount
                        require(pageCount > 0) { "PDF 没有可处理的页面" }
                        PDDocument.load(stagedPdf, MemoryUsageSetting.setupMixed(16L * 1024 * 1024)
                            .setTempDir(context.cacheDir)).use { document ->
                            require(document.numberOfPages == pageCount) { "PDF 页面数量不一致，无法处理" }
                            for (index in 0 until pageCount) {
                                currentCoroutineContext().ensureActive()
                                renderer.openPage(index).use { page ->
                                    val scale = min(3.0, MAX_PDF_EDGE.toDouble() / max(page.width, page.height))
                                    val width = max(1, (page.width * scale).toInt())
                                    val height = max(1, (page.height * scale).toInt())
                                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    try {
                                        bitmap.eraseColor(Color.WHITE)
                                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                        val regions = selectRegions(bitmap, index + 1, pageCount, true, onProgress, review)
                                        if (regions.isNotEmpty()) {
                                            onProgress(Progress(index + 1, pageCount, Phase.REPAIR))
                                            QrRegionRepair.erase(bitmap, regions)
                                            currentCoroutineContext().ensureActive()
                                            replacePageImage(document, document.getPage(index), bitmap)
                                            markedPages++
                                            marksRemoved += regions.size
                                        }
                                        // Unchanged pages retain their original content streams and text.
                                    } finally { bitmap.recycle() }
                                }
                            }
                            require(markedPages > 0) { "没有可处理的二维码或夸克标识，可使用“选择区域”手动补框；未生成修改文件" }
                            currentCoroutineContext().ensureActive()
                            onProgress(Progress(pageCount, pageCount, Phase.SAVE))
                            document.save(out)
                        }
                    }
                }
            }
            return Result(outputUri, displayName(outputUri), marksRemoved, pageCount, markedPages)
        } finally {
            stagedPdf.delete()
        }
    }

    private suspend fun selectRegions(
        bitmap: Bitmap, pageNumber: Int, pageCount: Int, isPdf: Boolean,
        onProgress: suspend (Progress) -> Unit,
        review: (suspend (ReviewPage) -> List<WatermarkRegion>)?,
    ): List<WatermarkRegion> {
        onProgress(Progress(pageNumber, pageCount, Phase.DETECT))
        val regions = detector.detect(bitmap)
        Log.i("FreeScanWatermark", "Page $pageNumber/$pageCount: " + regions.joinToString { region ->
            val box = region.bounds
            "${region.kind} [${"%.3f".format(java.util.Locale.US, box.left)}," +
                "${"%.3f".format(java.util.Locale.US, box.top)}," +
                "${"%.3f".format(java.util.Locale.US, box.right)}," +
                "${"%.3f".format(java.util.Locale.US, box.bottom)}]"
        })
        if (review == null) return regions.filter { it.kind != WatermarkKind.QR_UNVERIFIED }
        currentCoroutineContext().ensureActive()
        val scale = min(1f, 1400f / max(bitmap.width, bitmap.height))
        // UI owns this small copy; it must never reference the processor's recycled page bitmap.
        val preview = if (scale < 1f) Bitmap.createScaledBitmap(
            bitmap, max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true,
        ) else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: error("无法生成预览")
        return review(ReviewPage(preview, regions, pageNumber, pageCount, isPdf))
    }

    private fun replacePageImage(document: PDDocument, page: PDPage, bitmap: Bitmap) {
        val crop = page.cropBox
        val x = crop.lowerLeftX
        val y = crop.lowerLeftY
        val w = crop.width
        val h = crop.height
        // PdfRenderer already applies /Rotate. Map its bitmap back into the original crop box,
        // preserving all page boxes, /Rotate and /UserUnit instead of using pixel sizes as points.
        val matrix = when (((page.rotation % 360) + 360) % 360) {
            90 -> com.tom_roush.pdfbox.util.Matrix(0f, h, -w, 0f, x + w, y)
            180 -> com.tom_roush.pdfbox.util.Matrix(-w, 0f, 0f, -h, x + w, y + h)
            270 -> com.tom_roush.pdfbox.util.Matrix(0f, -h, w, 0f, x, y + h)
            else -> com.tom_roush.pdfbox.util.Matrix(w, 0f, 0f, h, x, y)
        }
        val image = LosslessFactory.createFromImage(document, bitmap)
        page.resources = PDResources()
        page.cosObject.removeItem(COSName.THUMB)
        // Keep PDF form fields and annotations. Clearing the whole /Annots array can remove
        // unrelated filled-in names, signatures and printed content on the same page.
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE, true).use {
            it.drawImage(image, matrix)
        }
    }

    private suspend fun writeToDownloads(
        name: String, mimeType: String, write: suspend (java.io.OutputStream) -> Unit,
    ): Uri {
        currentCoroutineContext().ensureActive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Freescan")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建下载文件")
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { write(it) } ?: error("无法写入下载文件")
                currentCoroutineContext().ensureActive()
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                return uri
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        }
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Freescan",
        )
        check(directory.exists() || directory.mkdirs()) { "无法创建输出目录" }
        var file = File(directory, name)
        var suffix = 1
        while (!file.createNewFile()) {
            file = File(directory, "${name.substringBeforeLast('.')} (${suffix++}).${name.substringAfterLast('.')}")
        }
        try {
            FileOutputStream(file).use { write(it) }
            currentCoroutineContext().ensureActive()
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType), null)
        return Uri.fromFile(file)
    }

    private fun displayName(uri: Uri): String {
        val providerName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return providerName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "scan"
    }

    private fun decodeImage(uri: Uri, sample: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: return null
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        } ?: 1
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
            }
        }
        if (matrix.isIdentity) return decoded
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } finally { decoded.recycle() }
    }

    private fun String.hasImageExtension(): Boolean =
        listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif").any { endsWith(it, true) }

    private fun cleanedName(sourceName: String, extension: String): String {
        val leaf = sourceName.substringAfterLast('/').substringAfterLast('\\')
        val base = leaf.substringBeforeLast('.', leaf).ifBlank { "scan" }
        return "${base}_cleaned$extension"
    }

    private fun calculateSampleSize(width: Int, height: Int, maxEdge: Int, maxPixels: Long): Int {
        var sample = 1
        while (
            max(width / sample, height / sample) > maxEdge ||
            (width.toLong() / sample) * (height.toLong() / sample) > maxPixels
        ) sample *= 2
        return sample
    }

    companion object {
        private const val PDF_HEADER_SCAN_BYTES = 1024
        private const val PDF_SIGNATURE = "%PDF-"
        private const val MAX_IMAGE_EDGE = 5000
        private const val MAX_IMAGE_PIXELS = 18_000_000L
        private const val MAX_PDF_EDGE = 3600
    }
}

