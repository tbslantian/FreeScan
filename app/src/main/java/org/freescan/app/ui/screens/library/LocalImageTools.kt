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
package org.freescan.app.ui.screens.library

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freescan.app.R
import org.freescan.app.platform.InpaintStroke
import org.freescan.app.platform.inpaintBitmap
import org.freescan.app.platform.loadBitmapFromUri
import org.freescan.app.platform.removeColoredInkBitmap
import org.freescan.app.platform.saveToolImage
import org.freescan.app.ui.screens.document.InpaintingCanvas

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PaperErasureScreen(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var source by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var repaired by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var strokes by remember(uri) { mutableStateOf(emptyList<InpaintStroke>()) }
    var redoStrokes by remember(uri) { mutableStateOf(emptyList<InpaintStroke>()) }
    var brushWidth by rememberSaveable(uri.toString()) { mutableStateOf(28f) }
    var loading by remember(uri) { mutableStateOf(true) }
    var processing by remember { mutableStateOf(false) }
    var compareOriginal by remember(uri) { mutableStateOf(false) }
    val loadError = stringResource(R.string.local_tool_load_failed)
    val repairError = stringResource(R.string.paper_eraser_repair_failed)
    val autoNoInk = stringResource(R.string.paper_eraser_auto_no_ink)
    val savedMessage = stringResource(R.string.local_tool_saved)
    val saveError = stringResource(R.string.local_tool_save_failed)

    LaunchedEffect(uri) {
        loading = true
        runCatching { loadBitmapFromUri(context.contentResolver, uri) }
            .onSuccess { source = it }
            .onFailure { snackbar.showSnackbar(loadError) }
        loading = false
    }
    BackHandler(onBack = onBack)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.paper_eraser_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (repaired != null) {
                        IconButton(onClick = {
                            val output = repaired ?: return@IconButton
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        saveToolImage(context, output, "exam-erased-${System.currentTimeMillis()}.jpg")
                                    }
                                }.onSuccess { snackbar.showSnackbar(savedMessage) }
                                    .onFailure { snackbar.showSnackbar(saveError) }
                            }
                        }) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.local_tool_save))
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (repaired == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            enabled = source != null && !processing,
                            onClick = {
                                val image = source ?: return@OutlinedButton
                                processing = true
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.Default) { removeColoredInkBitmap(image) }
                                    }.onSuccess { result ->
                                        if (result == null) snackbar.showSnackbar(autoNoInk)
                                        else {
                                            repaired = result
                                            strokes = emptyList()
                                            redoStrokes = emptyList()
                                        }
                                    }.onFailure { snackbar.showSnackbar(repairError) }
                                    processing = false
                                }
                            },
                        ) {
                            Text(stringResource(R.string.paper_eraser_auto_color))
                        }
                        Text(
                            stringResource(R.string.paper_eraser_auto_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                    }
                    Text(stringResource(R.string.paper_eraser_brush, brushWidth.toInt()))
                    Slider(
                        value = brushWidth,
                        onValueChange = { brushWidth = it },
                        valueRange = 8f..64f,
                        enabled = !processing,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                val removed = strokes.lastOrNull() ?: return@TextButton
                                strokes = strokes.dropLast(1)
                                redoStrokes = redoStrokes + removed
                            },
                            enabled = strokes.isNotEmpty() && !processing,
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = null)
                            Text(stringResource(R.string.eraser_undo))
                        }
                        TextButton(
                            onClick = {
                                val restored = redoStrokes.lastOrNull() ?: return@TextButton
                                strokes = strokes + restored
                                redoStrokes = redoStrokes.dropLast(1)
                            },
                            enabled = redoStrokes.isNotEmpty() && !processing,
                        ) {
                            Text(stringResource(R.string.eraser_redo))
                        }
                        TextButton(
                            onClick = { strokes = emptyList(); redoStrokes = emptyList() },
                            enabled = strokes.isNotEmpty() && !processing,
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                            Text(stringResource(R.string.eraser_clear))
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled = strokes.isNotEmpty() && !processing && source != null,
                            onClick = {
                                val image = source ?: return@Button
                                val marks = strokes
                                processing = true
                                scope.launch {
                                    runCatching { withContext(Dispatchers.Default) { inpaintBitmap(image, marks) } }
                                        .onSuccess {
                                            repaired = it
                                            strokes = emptyList()
                                            redoStrokes = emptyList()
                                        }
                                        .onFailure { snackbar.showSnackbar(repairError) }
                                    processing = false
                                }
                            },
                        ) {
                            if (processing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.eraser_repair))
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                source = repaired
                                repaired = null
                                strokes = emptyList()
                                redoStrokes = emptyList()
                                compareOriginal = false
                            },
                        ) {
                            Text(stringResource(R.string.paper_eraser_continue))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(
                    if (repaired != null) R.string.paper_eraser_compare_hint else R.string.paper_eraser_hint,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(
                        if (repaired != null) Modifier.pointerInput(source, repaired) {
                            detectTapGestures(onPress = {
                                compareOriginal = true
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    compareOriginal = false
                                }
                            })
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loading || processing -> CircularProgressIndicator()
                    repaired != null -> {
                        val displayImage = if (compareOriginal) source else repaired
                        Image(
                            bitmap = displayImage!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Text(
                            stringResource(if (compareOriginal) R.string.paper_eraser_before else R.string.paper_eraser_after),
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    source != null -> InpaintingCanvas(
                        bitmap = source!!,
                        strokes = strokes,
                        brushWidthDp = brushWidth,
                        enabled = !processing,
                        onStrokeCompleted = { stroke ->
                            strokes = strokes + stroke
                            redoStrokes = emptyList()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> Text(stringResource(R.string.local_tool_load_failed))
                }
            }
        }
    }
}

