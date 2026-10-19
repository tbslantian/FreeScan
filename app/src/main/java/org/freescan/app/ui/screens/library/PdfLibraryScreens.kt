/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */
package org.freescan.app.ui.screens.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.freescan.app.R
import org.freescan.app.data.LibraryPdf
import org.freescan.app.ui.Navigation
import android.net.Uri
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: PdfLibraryUiState,
    pendingPageCount: Int,
    navigation: Navigation,
    onRefresh: () -> Unit,
    onStartScan: () -> Unit,
    onContinueScan: () -> Unit,
    onStartCertificateScan: () -> Unit,
    onQuarkWatermarkClick: () -> Unit,
    onQuarkWatermarkReviewClick: () -> Unit,
    onEditPdf: (LibraryPdf) -> Unit,
    onViewPdf: (LibraryPdf) -> Unit,
    onSharePdf: (LibraryPdf) -> Unit,
    onDeletePdf: (LibraryPdf) -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    var paperErasureUri by remember { mutableStateOf<String?>(null) }
    val paperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        paperErasureUri = uri?.toString()
    }

    when {
        paperErasureUri != null -> PaperErasureScreen(Uri.parse(paperErasureUri.orEmpty())) {
            paperErasureUri = null
        }
        else -> Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                    actions = {
                        IconButton(onClick = navigation.toAboutScreen) {
                            Icon(Icons.Default.Info, contentDescription = stringResource(R.string.about))
                        }
                        IconButton(
                            onClick = { navigation.toSettingsScreen?.invoke() },
                            enabled = navigation.toSettingsScreen != null,
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    },
                )
            },
            bottomBar = { HomeLibraryNavigationBar(selectedHome = true, navigation = navigation) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                Column(
                    Modifier.widthIn(max = 680.dp).fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    ScanHeroCard(onStartScan = onStartScan)

                    if (pendingPageCount > 0) {
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onContinueScan),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.home_continue_scan),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        stringResource(R.string.home_pending_pages, pendingPageCount),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null)
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.home_tools),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        HomeActionCard(
                            title = stringResource(R.string.home_remove_quark_watermark),
                            hint = stringResource(R.string.home_watermark_hint),
                            icon = Icons.Default.AutoFixHigh,
                            accent = MaterialTheme.colorScheme.tertiary,
                            onClick = onQuarkWatermarkClick,
                            secondaryAction = onQuarkWatermarkReviewClick,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HomeActionCard(
                                title = stringResource(R.string.home_paper_eraser),
                                hint = stringResource(R.string.home_paper_eraser_hint),
                                icon = Icons.Default.Edit,
                                accent = MaterialTheme.colorScheme.primary,
                                onClick = { paperPicker.launch("image/*") },
                                modifier = Modifier.weight(1f),
                            )
                            HomeActionCard(
                                title = stringResource(R.string.home_certificate_scan),
                                hint = stringResource(R.string.home_certificate_scan_hint),
                                icon = Icons.Default.CreditCard,
                                accent = MaterialTheme.colorScheme.secondary,
                                onClick = onStartCertificateScan,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.home_recent),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            TextButton(onClick = navigation.toLibraryScreen) {
                                Text(stringResource(R.string.home_view_all))
                            }
                        }
                        if (state.pdfs.isEmpty() && !state.isLoading) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Icon(
                                        Icons.Default.PictureAsPdf,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        stringResource(R.string.home_empty_recent),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        state.pdfs.take(4).forEach { pdf ->
                            PdfRow(
                                pdf = pdf,
                                onEdit = { onEditPdf(pdf) },
                                onView = { onViewPdf(pdf) },
                                onShare = { onSharePdf(pdf) },
                                onDelete = { onDeletePdf(pdf) },
                                compact = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanHeroCard(onStartScan: () -> Unit) {
    val heroColor = Color(0xFF0B7045)
    val heroContentColor = Color.White
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = heroColor),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(52.dp).background(
                    heroContentColor.copy(alpha = 0.16f), RoundedCornerShape(16.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = heroContentColor)
            }
            Text(
                stringResource(R.string.home_welcome),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = heroContentColor,
            )
            Text(
                stringResource(R.string.home_description),
                style = MaterialTheme.typography.bodyMedium,
                color = heroContentColor.copy(alpha = 0.88f),
            )
            Button(
                onClick = onStartScan,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = heroContentColor,
                    contentColor = heroColor,
                ),
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.home_start_scan), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HomeActionCard(
    title: String,
    hint: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryAction: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.height(174.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    Modifier.size(44.dp).background(
                        accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = accent)
                }
                if (secondaryAction != null) {
                    TextButton(onClick = secondaryAction) {
                        Text(stringResource(R.string.watermark_select_regions))
                    }
                } else {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HomeLibraryNavigationBar(selectedHome: Boolean, navigation: Navigation) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        NavigationBarItem(
            selected = selectedHome,
            onClick = navigation.toHomeScreen,
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text(stringResource(R.string.home_title)) },
        )
        NavigationBarItem(
            selected = !selectedHome,
            onClick = navigation.toLibraryScreen,
            icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
            label = { Text(stringResource(R.string.documents_title)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfLibraryScreen(
    state: PdfLibraryUiState,
    navigation: Navigation,
    onRefresh: () -> Unit,
    onEditPdf: (LibraryPdf) -> Unit,
    onViewPdf: (LibraryPdf) -> Unit,
    onSharePdf: (LibraryPdf) -> Unit,
    onDeletePdf: (LibraryPdf) -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    BackHandler { navigation.back() }
    var query by rememberSaveable { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.documents_title)) },
                navigationIcon = {
                    IconButton(onClick = navigation.back) {
                        Icon(Icons.Default.Home, contentDescription = stringResource(R.string.home_title))
                    }
                },
            )
        },
        bottomBar = {
            HomeLibraryNavigationBar(selectedHome = false, navigation = navigation)
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.documents_search)) },
            )
            val filtered = state.pdfs.filter { it.name.contains(query, ignoreCase = true) }
            if (filtered.isEmpty() && !state.isLoading) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.documents_empty), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.documents_empty_hint), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn {
                    items(filtered, key = { it.uri.toString() }) { pdf ->
                        PdfRow(
                            pdf = pdf,
                            onEdit = { onEditPdf(pdf) },
                            onView = { onViewPdf(pdf) },
                            onShare = { onSharePdf(pdf) },
                            onDelete = { onDeletePdf(pdf) },
                            compact = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfRow(
    pdf: LibraryPdf,
    onEdit: () -> Unit,
    onView: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    compact: Boolean,
) {
    var menuExpanded by rememberSaveable(pdf.uri.toString()) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(pdf.uri.toString()) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ListItem(
                modifier = Modifier.weight(1f).clickable(onClick = onEdit),
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                leadingContent = {
                    Box(
                        Modifier.size(42.dp).background(
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                            RoundedCornerShape(13.dp),
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                },
                headlineContent = { Text(pdf.name, maxLines = 1) },
                supportingContent = {
                    Text(
                        listOf(
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(pdf.modifiedAtMillis))
                                .takeIf { pdf.modifiedAtMillis > 0 },
                            formatSize(pdf.sizeBytes),
                        ).filterNotNull().joinToString(" • ")
                    )
                },
            )
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.documents_actions))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.documents_edit)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.documents_view_original)) },
                        leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                        onClick = { menuExpanded = false; onView() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_document)) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = { menuExpanded = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(
                                if (pdf.isImportedReference) R.string.documents_remove
                                else R.string.documents_delete
                            ))
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuExpanded = false; confirmDelete = true },
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(stringResource(
                    if (pdf.isImportedReference) R.string.documents_remove
                    else R.string.documents_delete
                ))
            },
            text = {
                Text(stringResource(
                    if (pdf.isImportedReference) R.string.documents_confirm_remove
                    else R.string.documents_confirm_delete,
                    pdf.name
                ))
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(
                        if (pdf.isImportedReference) R.string.documents_remove
                        else R.string.documents_delete
                    ))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
