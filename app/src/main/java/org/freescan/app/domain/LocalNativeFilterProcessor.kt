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
 * Local adapter for the native document filters used by the scanner comparison build.
 * Native libraries are packaged in both debug and release builds.
 */
package org.freescan.app.domain

import android.graphics.Bitmap
import android.util.Log
import com.wibo.doc.jni.DocFilters
import org.freescan.imageprocessing.ColorMode
import java.io.ByteArrayOutputStream

internal object LocalNativeFilterProcessor {
    fun apply(bitmap: Bitmap, mode: ColorMode) {
        require(mode.isLocalNativeFilter()) { "Unsupported local native filter: $mode" }
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) {
            "The local filter requires a mutable ARGB_8888 bitmap."
        }

        when (mode) {
            ColorMode.LOCAL_BRIGHTNESS -> DocFilters.EnhanceBrightness(bitmap)
            ColorMode.LOCAL_ENHANCED -> DocFilters.EnhanceImg(bitmap, 0.0f)
            ColorMode.LOCAL_GRAYSCALE -> DocFilters.EnhanceGray(bitmap)
            ColorMode.LOCAL_SOFT -> DocFilters.EnhanceImg(bitmap, 0.3f)
            else -> error("Unsupported local native filter: $mode")
        }
        Log.i(TAG, "Applied local native filter $mode to ${bitmap.width}x${bitmap.height}")
    }

    fun encode(bitmap: Bitmap, quality: Int = 94): Jpeg {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            "Could not encode the filtered scan page."
        }
        return Jpeg(output.toByteArray())
    }

    private const val TAG = "LocalNativeFilter"
}

internal fun ColorMode.isLocalNativeFilter(): Boolean = when (this) {
    ColorMode.LOCAL_BRIGHTNESS,
    ColorMode.LOCAL_ENHANCED,
    ColorMode.LOCAL_GRAYSCALE,
    ColorMode.LOCAL_SOFT -> true
    else -> false
}

