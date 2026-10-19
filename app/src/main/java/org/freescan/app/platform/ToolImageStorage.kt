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

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Saves a locally processed image to Pictures/Freescan using Android's scoped storage. */
fun saveToolImage(context: Context, bitmap: Bitmap, displayName: String): Uri {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: throw IOException("The app's picture folder is unavailable.")
        val directory = File(pictures, "Freescan")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create the local image folder.")
        }
        val file = File(directory, displayName)
        FileOutputStream(file).use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                throw IOException("Could not write the processed image.")
            }
        }
        return Uri.fromFile(file)
    }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Freescan")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Could not create an image in Pictures/Freescan.")
    try {
        val wrote = resolver.openOutputStream(uri, "w")?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        } ?: false
        if (!wrote) throw IOException("Could not write the processed image.")
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        return uri
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
}
