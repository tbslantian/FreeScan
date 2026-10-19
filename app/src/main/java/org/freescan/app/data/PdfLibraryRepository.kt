/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */
package org.freescan.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.freescan.app.ui.screens.settings.SettingsRepository
import org.json.JSONArray
import java.io.File

data class LibraryPdf(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val isImportedReference: Boolean = false,
)

class PdfLibraryRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val preferences = context.getSharedPreferences("pdf_library", Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    suspend fun listPdfs(): List<LibraryPdf> = withContext(Dispatchers.IO) {
        val byUri = linkedMapOf<String, LibraryPdf>()
        val exportTree = settingsRepository.exportDirUri.first()?.toUri()
        if (exportTree != null) addSafDirectory(byUri, exportTree)
        addMediaStoreDownloads(byUri)
        addLegacyDownloads(byUri)
        addImportedReferences(byUri)
        byUri.values.sortedByDescending { it.modifiedAtMillis }
    }

    suspend fun deleteOrForget(pdf: LibraryPdf): Boolean = withContext(Dispatchers.IO) {
        if (pdf.isImportedReference) {
            val current = importedUris().toMutableSet()
            current.remove(pdf.uri.toString())
            preferences.edit().putString(KEY_IMPORTED_URIS, JSONArray(current.toList()).toString()).apply()
            return@withContext true
        }
        runCatching {
            when {
                pdf.uri.scheme == "file" ->
                    File(pdf.uri.path ?: return@runCatching false).delete()
                pdf.uri.authority == MediaStore.AUTHORITY ->
                    resolver.delete(pdf.uri, null, null) > 0
                else ->
                    DocumentFile.fromSingleUri(context, pdf.uri)?.delete() ?: false
            }
        }.getOrDefault(false)
    }

    private fun addSafDirectory(target: MutableMap<String, LibraryPdf>, treeUri: Uri) {
        runCatching {
            DocumentFile.fromTreeUri(context, treeUri)?.listFiles()?.forEach { file ->
                val name = file.name ?: return@forEach
                if (!file.isFile || !name.endsWith(".pdf", ignoreCase = true)) return@forEach
                target.putIfAbsent(file.uri.toString(), LibraryPdf(
                    file.uri, name, file.length(), file.lastModified()
                ))
            }
        }
    }

    private fun addMediaStoreDownloads(target: MutableMap<String, LibraryPdf>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            val selection = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?"
            resolver.query(
                collection, projection, selection,
                arrayOf(context.packageName, "application/pdf"),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                    val name = cursor.getString(nameIndex) ?: continue
                    target.putIfAbsent(uri.toString(), LibraryPdf(
                        uri, name, cursor.getLong(sizeIndex), cursor.getLong(dateIndex) * 1000L
                    ))
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun addLegacyDownloads(target: MutableMap<String, LibraryPdf>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .listFiles { file -> file.isFile && file.extension.equals("pdf", true) }
                ?.forEach { file ->
                    val uri = Uri.fromFile(file)
                    target.putIfAbsent(uri.toString(), LibraryPdf(
                        uri, file.name, file.length(), file.lastModified()
                    ))
                }
        }
    }

    private fun addImportedReferences(target: MutableMap<String, LibraryPdf>) {
        importedUris().forEach { value ->
            val uri = value.toUri()
            if (target.containsKey(value)) return@forEach
            val details = queryDetails(uri)
            val name = details?.first ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
            if (!name.endsWith(".pdf", ignoreCase = true)) return@forEach
            target[value] = LibraryPdf(uri, name, details?.second ?: 0L, details?.third ?: 0L, true)
        }
    }

    private fun importedUris(): List<String> = runCatching {
        val json = JSONArray(preferences.getString(KEY_IMPORTED_URIS, "[]"))
        List(json.length()) { index -> json.getString(index) }
    }.getOrDefault(emptyList())

    private fun queryDetails(uri: Uri): Triple<String?, Long, Long>? = runCatching {
        resolver.query(uri, arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        ), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(0)
            val size = if (cursor.isNull(1)) 0L else cursor.getLong(1)
            val date = if (cursor.isNull(2)) 0L else cursor.getLong(2) * 1000L
            Triple(name, size, date)
        }
    }.getOrNull()

    companion object {
        private const val KEY_IMPORTED_URIS = "imported_pdf_uris"
    }
}
