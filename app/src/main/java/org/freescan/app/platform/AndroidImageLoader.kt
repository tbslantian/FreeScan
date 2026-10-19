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

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.util.component1
import androidx.core.util.component2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.freescan.app.domain.ImageLoader
import java.io.IOException

class AndroidImageLoader(
    private val contentResolver: ContentResolver
) : ImageLoader {

    override suspend fun load(uri: Uri): Bitmap {
        val bitmap = loadBitmapFromUri(contentResolver, uri)
        return ensureArgb8888(bitmap)
    }
}

suspend fun loadBitmapFromUri(
    contentResolver: ContentResolver,
    uri: Uri,
    maxPixels: Int = 12_000_000,
): Bitmap = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= 28) {
        decodeWithImageDecoder(contentResolver, uri, maxPixels)
    } else {
        decodeWithBitmapFactory(contentResolver, uri)
    }
}

@RequiresApi(28)
private fun decodeWithImageDecoder(
    contentResolver: ContentResolver,
    uri: Uri,
    maxPixels: Int
): Bitmap {
    val source = ImageDecoder.createSource(contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val (width, height) = info.size
        val scale = computeScale(width, height, maxPixels)
        decoder.setTargetSize((width * scale).toInt(), (height * scale).toInt())
    }
}

private fun decodeWithBitmapFactory(contentResolver: ContentResolver, uri: Uri, ): Bitmap {
    val options = BitmapFactory.Options()
    val orientation = contentResolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } ?: ExifInterface.ORIENTATION_NORMAL
    val inputStream = contentResolver.openInputStream(uri)
        ?: throw IOException("Cannot open input stream for uri: $uri")
    val bitmap = inputStream.use {
        BitmapFactory.decodeStream(it, null, options)
            ?: throw IOException("Failed to decode bitmap for uri: $uri")
    }
    val transform = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            transform.setRotate(90f)
            transform.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            transform.setRotate(-90f)
            transform.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(270f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true).also {
        if (it !== bitmap) bitmap.recycle()
    }
}

private fun computeScale(width: Int, height: Int, maxPixels: Int): Float {
    val pixels = width * height
    return if (pixels > maxPixels) {
        maxPixels.toFloat() / pixels
    } else {
        1f
    }
}

private fun ensureArgb8888(bitmap: Bitmap): Bitmap {
    return if (bitmap.config != Bitmap.Config.ARGB_8888) {
        bitmap.copy(Bitmap.Config.ARGB_8888, true)
    } else {
        bitmap
    }
}
