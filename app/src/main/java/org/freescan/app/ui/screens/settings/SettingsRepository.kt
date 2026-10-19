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
package org.freescan.app.ui.screens.settings

import android.content.Context
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import org.freescan.app.R
import org.freescan.app.domain.ExportQuality
import org.freescan.imageprocessing.ColorMode

class SettingsRepository(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
) {

    private val DEFAULT_COLOR_MODE = stringPreferencesKey("default_color_mode")
    private val EXPORT_DIR_URI = stringPreferencesKey("export_dir_uri")
    private val EXPORT_FORMAT = stringPreferencesKey("export_format")
    private val EXPORT_QUALITY = stringPreferencesKey("export_quality")
    val defaultColorMode: Flow<DefaultColorMode> =
        dataStore.data.map { prefs ->
            runCatching { DefaultColorMode.valueOf(prefs[DEFAULT_COLOR_MODE].orEmpty()) }
                .getOrDefault(DefaultColorMode.AUTO)
        }

    val exportDirUri: Flow<String?> =
        dataStore.data.map { prefs ->
            prefs[EXPORT_DIR_URI]
        }

    fun resolveExportDirName(uri: String): String? {
        return DocumentFile.fromTreeUri(context, uri.toUri())?.name
    }

    val exportFormat: Flow<ExportFormat> =
        dataStore.data.map { prefs ->
            when (prefs[EXPORT_FORMAT]) {
                "JPEG" -> ExportFormat.JPEG
                "PDF", null -> ExportFormat.PDF
                else -> ExportFormat.PDF
            }
        }

    val exportQuality: Flow<ExportQuality> =
        dataStore.data.map { prefs ->
            when (prefs[EXPORT_QUALITY]) {
                "LOW" -> ExportQuality.LOW
                "HIGH" -> ExportQuality.HIGH
                "BALANCED", null -> ExportQuality.BALANCED
                else -> ExportQuality.BALANCED
            }
        }

    suspend fun setDefaultColorMode(mode: DefaultColorMode) {
        dataStore.edit { prefs ->
            prefs[DEFAULT_COLOR_MODE] = mode.name
        }
    }

    suspend fun setExportDirUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(EXPORT_DIR_URI)
            } else {
                prefs[EXPORT_DIR_URI] = uri
            }
        }
    }

    suspend fun setExportFormat(format: ExportFormat) {
        dataStore.edit { prefs ->
            prefs[EXPORT_FORMAT] = format.name
        }
    }

    suspend fun setExportQuality(quality: ExportQuality) {
        dataStore.edit { prefs ->
            prefs[EXPORT_QUALITY] = quality.name
        }
    }

    suspend fun clearRemovedCloudAiSettings() {
        dataStore.edit { prefs ->
            prefs.remove(booleanPreferencesKey("cloud_ai_enabled"))
            prefs.remove(stringPreferencesKey("cloud_ai_provider"))
            prefs.remove(stringPreferencesKey("cloud_ai_model"))
            prefs.remove(stringPreferencesKey("cloud_ai_image_edit_model"))
            prefs.remove(stringPreferencesKey("cloud_ai_token_encrypted"))
        }
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .takeIf { it.containsAlias(CLOUD_AI_KEY_ALIAS) }
                ?.deleteEntry(CLOUD_AI_KEY_ALIAS)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CLOUD_AI_KEY_ALIAS = "freescan_cloud_ai_token"
    }
}

enum class DefaultColorMode(val colorMode: ColorMode?, val labelResource: Int) {
    AUTO(null, R.string.color_mode_auto),
    ORIGINAL(ColorMode.ORIGINAL, R.string.color_mode_original),
    COLOR(ColorMode.COLOR, R.string.color_mode_color),
    GRAYSCALE(ColorMode.GRAYSCALE, R.string.color_mode_grayscale),
    BLACK_AND_WHITE(ColorMode.BLACK_AND_WHITE, R.string.color_mode_black_and_white),
    LOCAL_BRIGHTNESS(ColorMode.LOCAL_BRIGHTNESS, R.string.color_mode_local_brightness),
    LOCAL_ENHANCED(ColorMode.LOCAL_ENHANCED, R.string.color_mode_local_enhanced),
    LOCAL_GRAYSCALE(ColorMode.LOCAL_GRAYSCALE, R.string.color_mode_local_grayscale),
    LOCAL_SOFT(ColorMode.LOCAL_SOFT, R.string.color_mode_local_soft),
}

enum class ExportFormat(val mimeType: String) {
    PDF("application/pdf"),
    JPEG("image/jpeg"),
}
