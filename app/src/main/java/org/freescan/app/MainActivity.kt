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
package org.freescan.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.os.CancellationSignal
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.Q
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.net.toFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freescan.app.data.FileLogger
import org.freescan.app.data.ImageRepository
import org.freescan.app.data.LibraryPdf
import org.freescan.app.data.QuarkWatermarkProcessor
import org.freescan.app.ui.Navigation
import org.freescan.app.ui.Screen
import org.freescan.app.ui.components.rememberCameraPermissionState
import org.freescan.app.ui.screens.LibrariesScreen
import org.freescan.app.ui.screens.ResumeScanScreen
import org.freescan.app.ui.screens.about.AboutEvent
import org.freescan.app.ui.screens.about.AboutScreen
import org.freescan.app.ui.screens.about.AboutViewModel
import org.freescan.app.ui.screens.about.createEmailWithImageIntent
import org.freescan.app.ui.screens.camera.CameraEvent
import org.freescan.app.ui.screens.camera.CameraScreen
import org.freescan.app.ui.screens.camera.CameraViewModel
import org.freescan.app.ui.screens.crop.CropScreen
import org.freescan.app.ui.screens.document.DocumentScreen
import org.freescan.app.ui.screens.export.ExportActions
import org.freescan.app.ui.screens.export.ExportEvent
import org.freescan.app.ui.screens.export.ExportResult
import org.freescan.app.ui.screens.export.ExportScreenWrapper
import org.freescan.app.ui.screens.export.ExportViewModel
import org.freescan.app.ui.screens.library.HomeScreen
import org.freescan.app.ui.screens.library.PdfLibraryScreen
import org.freescan.app.ui.screens.library.PdfLibraryViewModel
import org.freescan.app.ui.screens.library.WatermarkDialogs
import org.freescan.app.ui.screens.library.WatermarkReviewRequest
import org.freescan.app.platform.WatermarkRegion
import org.freescan.app.ui.screens.settings.OcrLanguagesScreen
import org.freescan.app.ui.screens.settings.SettingsScreen
import org.freescan.app.ui.screens.settings.SettingsUiState
import org.freescan.app.ui.screens.settings.SettingsViewModel
import org.freescan.app.ui.theme.FreeScanTheme
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.ArrayDeque

class MainActivity : ComponentActivity() {

    private lateinit var cameraViewModel: CameraViewModel
    private lateinit var viewModel: MainViewModel
    private data class WatermarkTask(val uri: Uri, val review: Boolean = false)
    private var pendingQuarkShareUris: List<WatermarkTask> = emptyList()
    private val quarkWatermarkQueue = ArrayDeque<WatermarkTask>()
    private var quarkWatermarkJob: Job? = null
    private var watermarkProgress by mutableStateOf<QuarkWatermarkProcessor.Progress?>(null)
    private var watermarkReview by mutableStateOf<WatermarkReviewRequest?>(null)
    private var quarkPermissionRequestActive = false
    private var pdfImportProgress by mutableStateOf<Pair<Int, Int>?>(null)
    private var pendingPdfImport by mutableStateOf<LibraryPdf?>(null)
    private val quarkStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        quarkPermissionRequestActive = false
        val uris = pendingQuarkShareUris
        pendingQuarkShareUris = emptyList()
        if (granted && uris.isNotEmpty()) queueQuarkWatermarkTasks(uris)
        else if (!granted) showToast(getString(R.string.error_occurred))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initLibraries()

        val appContainer = (application as FreeScanApp).appContainer
        val launchMode = resolveLaunchMode(intent)

        val sessionViewModel: SessionViewModel by viewModels {
            SessionViewModelFactory(
                application = application,
                launchMode = launchMode,
                appContainer = appContainer
            )
        }

        val imageRepository = sessionViewModel.imageRepository
        viewModel = viewModels<MainViewModel> {
            appContainer.viewModelFactory {
                MainViewModel(
                    imageRepository,
                    appContainer.logger,
                    launchMode == LaunchMode.NORMAL,
                )
            }
        }.value
        val exportViewModel: ExportViewModel by viewModels {
            appContainer.viewModelFactory {
                ExportViewModel(appContainer, imageRepository)
            }
        }
        val aboutViewModel: AboutViewModel by viewModels {
            appContainer.viewModelFactory {
                AboutViewModel(appContainer, imageRepository)
            }
        }
        cameraViewModel = viewModels<CameraViewModel> { appContainer.cameraViewModelFactory }.value

        val settingsViewModel: SettingsViewModel
            by viewModels { appContainer.settingsViewModelFactory }
        val pdfLibraryViewModel: PdfLibraryViewModel
            by viewModels { appContainer.pdfLibraryViewModelFactory }
        lifecycleScope.launch(Dispatchers.IO) {
            exportViewModel.cleanUpOldPreparedFiles(1000 * 3600)
        }
        enableEdgeToEdge()
        setContent {
            val logger = appContainer.logger
            val context = LocalContext.current
            val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
            val liveAnalysisState by cameraViewModel.liveAnalysisState.collectAsStateWithLifecycle()
            val importState by cameraViewModel.importState.collectAsStateWithLifecycle()
            val document by viewModel.documentUiModel.collectAsStateWithLifecycle()
            val documentUiState by viewModel.documentUiState.collectAsStateWithLifecycle()
            val cropInitialState by viewModel.cropInitState.collectAsStateWithLifecycle()
            val exportUiState by exportViewModel.uiState.collectAsStateWithLifecycle()
            val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            val pdfLibraryUiState by pdfLibraryViewModel.uiState.collectAsStateWithLifecycle()
            var reviewWatermarkSelection by rememberSaveable { mutableStateOf(false) }
            val watermarkPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    queueQuarkWatermarkTasks(listOf(WatermarkTask(uri, reviewWatermarkSelection)))
                }
            }
            val chooseQuarkFile: () -> Unit = {
                reviewWatermarkSelection = false
                watermarkPicker.launch(arrayOf("application/pdf", "image/*"))
            }
            val chooseQuarkFileWithReview: () -> Unit = {
                reviewWatermarkSelection = true
                watermarkPicker.launch(arrayOf("application/pdf", "image/*"))
            }
            val cameraPermission = rememberCameraPermissionState()
            CollectCameraEvents(cameraViewModel, viewModel)
            CollectExportEvents(context, exportViewModel)
            CollectAboutEvents(context, aboutViewModel, imageRepository)

            FreeScanTheme {
                val navigation = navigation(viewModel, launchMode)
                val onExportClick = if (launchMode == LaunchMode.EXTERNAL_SCAN_TO_PDF) {
                    {
                        lifecycleScope.launch {
                            try {
                                val result = exportViewModel.generatePdfForExternalCall()
                                sendActivityResult(result)
                            } catch (e: Exception) {
                                val message = "Export failed"
                                showToast(message)
                                appContainer.logger.e("MainActivity", message, e)
                                return@launch
                            }
                            viewModel.startNewDocument()
                            finish()
                        }
                        Unit
                    }
                } else {
                    navigation.toExportScreen
                }

                when (currentScreen) {
                    null -> {
                        // waiting to load pages to get an initial screen
                    }
                    is Screen.Main.Home -> {
                        HomeScreen(
                            state = pdfLibraryUiState,
                            pendingPageCount = document.pageCount(),
                            navigation = navigation,
                            onRefresh = pdfLibraryViewModel::refresh,
                            onStartScan = {
                                cameraViewModel.beginDocumentScan()
                                if (!document.isEmpty()) viewModel.startNewDocument()
                                navigation.toCameraScreen()
                            },
                            onContinueScan = {
                                cameraViewModel.beginDocumentScan()
                                navigation.toCameraScreen()
                            },
                            onStartCertificateScan = {
                                if (!document.isEmpty()) viewModel.startNewDocument()
                                cameraViewModel.beginCertificateScan()
                                navigation.toCameraScreen()
                            },
                            onQuarkWatermarkClick = chooseQuarkFile,
                            onQuarkWatermarkReviewClick = chooseQuarkFileWithReview,
                            onEditPdf = { pdf ->
                                if (document.pageCount() > 0) pendingPdfImport = pdf
                                else editPdf(pdf, logger)
                            },
                            onViewPdf = { pdf -> openUri(pdf.uri, "application/pdf", logger) },
                            onSharePdf = ::sharePdf,
                            onDeletePdf = { pdf ->
                                pdfLibraryViewModel.deleteOrForget(pdf) { deleted ->
                                    val message = when {
                                        !deleted -> getString(R.string.documents_delete_failed)
                                        pdf.isImportedReference -> getString(R.string.documents_remove_success)
                                        else -> getString(R.string.documents_delete_success)
                                    }
                                    showToast(message)
                                }
                            },
                        )
                    }
                    is Screen.Main.Library -> {
                        PdfLibraryScreen(
                            state = pdfLibraryUiState,
                            navigation = navigation,
                            onRefresh = pdfLibraryViewModel::refresh,
                            onEditPdf = { pdf ->
                                if (document.pageCount() > 0) pendingPdfImport = pdf
                                else editPdf(pdf, logger)
                            },
                            onViewPdf = { pdf -> openUri(pdf.uri, "application/pdf", logger) },
                            onSharePdf = ::sharePdf,
                            onDeletePdf = { pdf ->
                                pdfLibraryViewModel.deleteOrForget(pdf) { deleted ->
                                    val message = when {
                                        !deleted -> getString(R.string.documents_delete_failed)
                                        pdf.isImportedReference -> getString(R.string.documents_remove_success)
                                        else -> getString(R.string.documents_delete_success)
                                    }
                                    showToast(message)
                                }
                            },
                        )
                    }
                    is Screen.Main.ResumeScan -> {
                        ResumeScanScreen(
                            currentDocument = documentUiState,
                            onResumeScan = {
                                cameraViewModel.beginDocumentScan()
                                navigation.toCameraScreen()
                            },
                            onStartNewScan = {
                                viewModel.startNewDocument()
                                cameraViewModel.beginDocumentScan()
                                navigation.toCameraScreen()
                            }
                        )
                    }
                    is Screen.Main.Camera -> {
                        BackHandler { navigation.back() }
                        val pickMultiple = rememberLauncherForActivityResult(
                            ActivityResultContracts.GetMultipleContents()) {
                                uris -> cameraViewModel.importPhotos(uris)
                            }
                        CameraScreen(
                            viewModel,
                            cameraViewModel,
                            navigation,
                            liveAnalysisState,
                            importState,
                            onImageAnalyzed = { image -> cameraViewModel.liveAnalysis(image) },
                            onFinalizePressed = onExportClick,
                            cameraPermission = cameraPermission,
                            onImportClicked = {
                                cameraViewModel.onImportClicked()
                                pickMultiple.launch("image/*")
                            },
                        )
                    }
                    is Screen.Main.EditImage -> {
                        CropScreen(
                            pageId = documentUiState.currentPage?.key?.pageId ?: "",
                            initState = cropInitialState,
                            navigation = navigation,
                            onUpdatePageQuad = { quad -> viewModel.setCurrentPageUserQuad(quad) },
                        )
                    }
                    is Screen.Main.Document -> {
                        DocumentScreen (
                            uiState = documentUiState,
                            navigation = navigation,
                            onExportClick = onExportClick,
                            onDeleteImage =  { viewModel.deleteCurrentPage() },
                            onRotateImage = { clockwise -> viewModel.rotateCurrentPage(clockwise) },
                            onColorModeSelected = { viewModel.setCurrentPageColorMode(it) },
                            onRemoveShadowsChanged = viewModel::setCurrentPageRemoveShadows,
                            onSaveManualEdit = viewModel::saveManualEditedImage,
                            onCropClick = { viewModel.onClickOnCropButton() },
                            onPageReorder = { id, newIndex -> viewModel.movePage(id, newIndex) },
                            onPageSelected = viewModel::onPageSelected
                        )
                    }
                    is Screen.Main.Export -> {
                        ExportScreenWrapper(
                            navigation = navigation,
                            uiState = exportUiState,
                            currentDocument = document,
                            exportActions = ExportActions(
                                prepareExportIfNeeded = exportViewModel::prepareExportIfNeeded,
                                setFilename = exportViewModel::setFilename,
                                share = { exportViewModel.onShareClicked() },
                                print = exportViewModel::onPrintClicked,
                                save = { exportViewModel.onSaveClicked() },
                                open = { item -> openUri(item.uri, item.format.mimeType, logger) },
                                cancelPreparationJob = exportViewModel::cancelPreparationJob,
                            ),
                            onCloseScan = {
                                exportViewModel.resetFilename()
                                viewModel.startNewDocument()
                                viewModel.navigateTo(Screen.Main.Home)
                            }
                        )
                    }
                    is Screen.Overlay.About -> {
                        LaunchedEffect(Unit) {
                            aboutViewModel.refreshLastCapturedImageState()
                        }
                        val aboutUiState by aboutViewModel.uiState.collectAsStateWithLifecycle()
                        AboutScreen(
                            aboutUiState = aboutUiState,
                            onBack = navigation.back,
                            onCopyLogs = { aboutViewModel.onCopyLogsClicked() },
                            onContactWithLastImageClicked =
                                { aboutViewModel.onContactWithLastImageClicked() },
                            onStartActivity = { intent ->
                                try {
                                    startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    showToast(getString(R.string.error_occurred))
                                }
                            },
                            onViewLibraries = navigation.toLibrariesScreen)
                    }
                    is Screen.Overlay.Libraries -> {
                        LibrariesScreen(onBack = navigation.back)
                    }
                    is Screen.Overlay.Settings -> {
                        SettingsScreenWrapper(settingsUiState, settingsViewModel, navigation, logger)
                    }
                    is Screen.Overlay.OcrLanguages -> {
                        OcrLanguagesScreen(
                            uiState = settingsUiState,
                            onBack = navigation.back,
                            onLanguageClick = settingsViewModel::onLanguageClick,
                            onRemoveLanguage = settingsViewModel::onRemoveLanguage,
                            onCancelOcrDownload = settingsViewModel::cancelOcrDownload,
                        )
                    }
                }
                WatermarkDialogs(watermarkProgress, watermarkReview, ::cancelWatermarkProcessing)
                pendingPdfImport?.let { pdf ->
                    AlertDialog(
                        onDismissRequest = { pendingPdfImport = null },
                        title = { Text(stringResource(R.string.documents_replace_draft_title)) },
                        text = { Text(stringResource(R.string.documents_replace_draft_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                pendingPdfImport = null
                                editPdf(pdf, logger)
                            }) { Text(stringResource(R.string.documents_edit)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingPdfImport = null }) {
                                Text(stringResource(R.string.cancel))
                            }
                        },
                    )
                }
                pdfImportProgress?.let { (done, total) ->
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(stringResource(R.string.documents_importing_title)) },
                        text = { Text(stringResource(R.string.documents_importing_progress, done, total)) },
                        confirmButton = {},
                    )
                }
            }
        }
        if (savedInstanceState == null) acceptQuarkShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptQuarkShareIntent(intent)
    }

    private fun acceptQuarkShareIntent(incoming: Intent?) {
        val action = incoming?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        @Suppress("DEPRECATION")
        val streamUris = when (action) {
            Intent.ACTION_SEND_MULTIPLE -> if (SDK_INT >= 33) {
                incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            }
            else -> listOfNotNull(if (SDK_INT >= 33) {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            })
        }
        val clipUris = (0 until (incoming.clipData?.itemCount ?: 0))
            .mapNotNull { incoming.clipData?.getItemAt(it)?.uri }
        val uris = (streamUris + clipUris + listOfNotNull(incoming.data)).distinct()
        if (uris.isNotEmpty()) queueQuarkWatermarkFiles(uris)
    }

    private fun queueQuarkWatermarkFiles(uris: List<Uri>) {
        queueQuarkWatermarkTasks(uris.map { WatermarkTask(it) })
    }

    private fun queueQuarkWatermarkTasks(uris: List<WatermarkTask>) {
        if (uris.isEmpty()) return
        if (SDK_INT < Q && checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PERMISSION_GRANTED
        ) {
            pendingQuarkShareUris = (pendingQuarkShareUris + uris).distinct()
            if (pendingQuarkShareUris.isNotEmpty() && !quarkPermissionRequestActive) {
                quarkPermissionRequestActive = true
                quarkStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            return
        }
        quarkWatermarkQueue.addAll(uris.distinct().filterNot { it in quarkWatermarkQueue })
        processNextQuarkWatermarkFile()
    }

    private fun processNextQuarkWatermarkFile() {
        if (quarkWatermarkJob?.isActive == true || quarkWatermarkQueue.isEmpty()) return
        quarkWatermarkJob = lifecycleScope.launch {
            try {
                while (quarkWatermarkQueue.isNotEmpty()) {
                    val task = quarkWatermarkQueue.removeFirst()
                    val uri = task.uri
                    watermarkProgress = QuarkWatermarkProcessor.Progress(1, 1, QuarkWatermarkProcessor.Phase.DETECT)
                    try {
                        val reviewer: (suspend (QuarkWatermarkProcessor.ReviewPage) -> List<WatermarkRegion>)? =
                            if (task.review) ::reviewWatermarkPage else null
                        val result = withContext(Dispatchers.IO) {
                            QuarkWatermarkProcessor(applicationContext).process(
                                uri,
                                onProgress = { progress ->
                                    withContext(Dispatchers.Main.immediate) { watermarkProgress = progress }
                                },
                                review = reviewer,
                            )
                        }
                        showToast(getString(
                            R.string.quark_watermark_success,
                            result.fileName,
                            result.marksRemoved,
                            result.modifiedPages,
                            result.pageCount,
                        ))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e("QuarkWatermark", "Could not process $uri", error)
                        showToast(getString(
                            R.string.quark_watermark_failed,
                            error.message ?: getString(R.string.error_occurred),
                        ))
                    }
                }
            } catch (cancelled: CancellationException) {
                quarkWatermarkQueue.clear()
                throw cancelled
            } finally {
                watermarkProgress = null
                watermarkReview = null
                quarkWatermarkJob = null
                processNextQuarkWatermarkFile()
            }
        }
    }

    private suspend fun reviewWatermarkPage(page: QuarkWatermarkProcessor.ReviewPage): List<WatermarkRegion> =
        withContext(Dispatchers.Main.immediate) {
            val request = WatermarkReviewRequest(page)
            watermarkReview = request
            try {
                request.answer.await()
            } finally {
                if (watermarkReview === request) watermarkReview = null
                request.answer.cancel()
            }
        }

    private fun cancelWatermarkProcessing() {
        quarkWatermarkQueue.clear()
        watermarkReview?.answer?.cancel()
        quarkWatermarkJob?.cancel()
    }

    private fun resolveLaunchMode(intent: Intent?): LaunchMode {
        return when (intent?.action) {
            "org.freescan.app.action.SCAN_TO_PDF" -> LaunchMode.EXTERNAL_SCAN_TO_PDF
            "android.intent.action.GET_CONTENT" -> LaunchMode.EXTERNAL_SCAN_TO_PDF
            else -> LaunchMode.NORMAL
        }
    }

    private fun showToast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun SettingsScreenWrapper(
        settingsUiState: SettingsUiState,
        settingsViewModel: SettingsViewModel,
        nav: Navigation,
        logger: FileLogger,
    ) {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    contentResolver.takePersistableUriPermission(uri, flags)
                    settingsViewModel.setExportDirUri(uri.toString())
                } catch (e: Exception) {
                    logger.e("Settings", "Failed to set export dir to $uri", e)
                    showToast(this.getString(R.string.error_file_picker_result))
                }
            }
        }
        LaunchedEffect(Unit) {
            settingsViewModel.refreshExportDirName()
        }
        SettingsScreen(
            settingsUiState,
            onDefaultColorModeChanged = { mode -> settingsViewModel.setDefaultColorMode(mode) },
            onChooseDirectoryClick = {
                try {
                    launcher.launch(null)
                } catch (e: Exception) {
                    val message = getString(R.string.error_file_picker_launch)
                    logger.e("Settings", message, e)
                    showToast(message)
                }
            },
            onResetExportDirClick = { settingsViewModel.setExportDirUri(null) },
            onExportFormatChanged = { format -> settingsViewModel.setExportFormat(format) },
            onExportQualityChanged = { quality -> settingsViewModel.setExportQuality(quality) },
            navigation = nav,
        )
    }

    @Composable
    private fun CollectAboutEvents(
        context: Context,
        aboutViewModel: AboutViewModel,
        imageRepository: ImageRepository,
    ) {
        val clipboard = LocalClipboard.current
        val msgCopiedLogs = stringResource(R.string.copied_logs)
        LaunchedEffect(Unit) {
            aboutViewModel.events.collect { event ->
                when (event) {
                    is AboutEvent.CopyLogs -> {
                        clipboard.setClipEntry(
                            ClipData.newPlainText("Freescan logs", event.logs).toClipEntry()
                        )
                        showToast(msgCopiedLogs)
                    }
                    is AboutEvent.PrepareEmailWithLastImage -> {
                        val file = imageRepository.lastAddedSourceFile()
                        if (file != null) {
                            startActivity(createEmailWithImageIntent(context, file))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CollectExportEvents(
        context: Context,
        exportViewModel: ExportViewModel,
    ) {
        val storagePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                exportViewModel.onSaveClicked()
            } else {
                showToast(this.getString(R.string.storage_permission_denied))
            }
        }
        LaunchedEffect(Unit) {
            exportViewModel.events.collect { event ->
                when (event) {
                    ExportEvent.RequestSave -> {
                        checkPermissionThen(storagePermissionLauncher) {
                            exportViewModel.onRequestSave(context)
                        }
                    }
                    is ExportEvent.Share -> {
                        share(event.result)
                    }
                    is ExportEvent.PrintPdf -> {
                        printPdf(event.file, event.pageCount, event.jobName)
                    }
                }
            }
        }
    }

    @Composable
    private fun CollectCameraEvents(
        cameraViewModel: CameraViewModel,
        viewModel: MainViewModel,
    ) {
        LaunchedEffect(Unit) {
            cameraViewModel.events.collect { event ->
                when (event) {
                    is CameraEvent.ImageCaptured -> viewModel.handleImageCaptured(event.page)
                }
            }
        }
    }

    private fun share(result: ExportResult) {
        if (result.files.isEmpty()) return

        val uris = result.files.map(::uriForFile)
        val intent = Intent().apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            type = result.format.mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        val chooser = Intent.createChooser(intent, getString(R.string.share_document))

        val resolveInfos = packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            for (uri in uris) {
                grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        startActivity(chooser)
    }

    private fun printPdf(file: File, pageCount: Int, jobName: String) {
        val printManager = getSystemService(PrintManager::class.java)
        if (printManager == null) {
            file.delete()
            showToast(getString(R.string.error_occurred))
            return
        }

        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal,
                callback: LayoutResultCallback,
                extras: Bundle?,
            ) {
                if (cancellationSignal.isCanceled) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(jobName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageCount)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal,
                callback: WriteResultCallback,
            ) {
                lifecycleScope.launch {
                    try {
                        val completed = withContext(Dispatchers.IO) {
                            ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                                val selectedPages = pages.flatMap { range ->
                                    val end = minOf(range.end, pageCount - 1)
                                    if (range.start <= end) (range.start..end).toList()
                                    else emptyList()
                                }.distinct()
                                if (cancellationSignal.isCanceled) false
                                else writePdfPages(
                                    file,
                                    selectedPages,
                                    pageCount,
                                    cancellationSignal,
                                    output,
                                )
                            }
                        }
                        if (completed) callback.onWriteFinished(pages)
                        else callback.onWriteCancelled()
                    } catch (e: CancellationException) {
                        callback.onWriteCancelled()
                    } catch (e: Exception) {
                        if (cancellationSignal.isCanceled) {
                            callback.onWriteCancelled()
                        } else {
                            Log.e("Print", "Failed to write print document", e)
                            callback.onWriteFailed(e.message ?: getString(R.string.error_occurred))
                        }
                    }
                }
            }

            override fun onFinish() {
                if (file.exists() && !file.delete()) {
                    Log.w("Print", "Unable to delete temporary print PDF: ${file.absolutePath}")
                }
            }
        }

        try {
            printManager.print(jobName, adapter, null)
        } catch (e: Exception) {
            file.delete()
            Log.e("Print", "Unable to start print job", e)
            showToast(getString(R.string.error_occurred))
        }
    }

    private fun writePdfPages(
        file: File,
        selectedPages: List<Int>,
        pageCount: Int,
        cancellationSignal: CancellationSignal,
        output: OutputStream,
    ): Boolean {
        if (selectedPages == (0 until pageCount).toList()) {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (cancellationSignal.isCanceled) return false
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            return !cancellationSignal.isCanceled
        }

        if (cancellationSignal.isCanceled) return false
        PDDocument.load(file).use { source ->
            PDDocument().use { selectedDocument ->
                selectedPages.forEach { pageIndex ->
                    if (cancellationSignal.isCanceled) return false
                    selectedDocument.importPage(source.getPage(pageIndex))
                }
                selectedDocument.save(output)
            }
        }
        return !cancellationSignal.isCanceled
    }

    private fun sendActivityResult(result: ExportResult?) {
        val pdf = result as? ExportResult.Pdf ?: return

        val uri = uriForFile(pdf.file)
        val resultIntent = Intent().apply {
            data = uri
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        setResult(RESULT_OK, resultIntent)
    }

    private fun uriForFile(file: File): Uri {
        return org.freescan.app.ui.uriForFile(this, file)
    }

    private fun checkPermissionThen(
        requestPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
        action: () -> Unit
    ) {
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (SDK_INT < Q && checkSelfPermission(this, permission) != PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(permission)
        } else {
            action()
        }
    }

    private fun editPdf(pdf: LibraryPdf, logger: FileLogger) {
        if (pdfImportProgress != null) return
        pdfImportProgress = 0 to 0
        lifecycleScope.launch {
            try {
                viewModel.importPdfForEditing(this@MainActivity, pdf.uri) { done, total ->
                    pdfImportProgress = done to total
                }
            } catch (e: Exception) {
                logger.e("PdfImport", "Could not import ${pdf.uri} for editing", e)
                showToast(getString(R.string.documents_import_failed))
            } finally {
                pdfImportProgress = null
            }
        }
    }

    private fun openUri(fileUri: Uri?, mimeType: String, logger: FileLogger) {
        if (fileUri == null) return
        val uriToOpen: Uri =
            if (fileUri.scheme == ContentResolver.SCHEME_CONTENT) {
                fileUri
            } else {
                uriForFile(fileUri.toFile())
            }
        try {
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriToOpen, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "file", uriToOpen)
            }
            val chooser = Intent.createChooser(openIntent, getString(R.string.open_file)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            showToast(getString(R.string.error_no_app))
        } catch (e: Exception) {
            val errorMessage =
                "Failed to open URI, scheme=${uriToOpen.scheme}, authority=${uriToOpen.authority}"
            logger.e("OpenUri", errorMessage, e)
            showToast(getString(R.string.error_occurred))
        }
    }

    private fun sharePdf(pdf: LibraryPdf) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, pdf.uri)
            clipData = ClipData.newUri(contentResolver, pdf.name, pdf.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(sendIntent, getString(R.string.share_document)))
        } catch (e: ActivityNotFoundException) {
            showToast(getString(R.string.error_no_app))
        }
    }
    private fun initLibraries() {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)

        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "Initialization failed")
        } else {
            Log.d("OpenCV", "Initialization successful")
        }
    }

    private fun navigation(viewModel: MainViewModel, launchMode: LaunchMode): Navigation = Navigation(
        toCameraScreen = { viewModel.navigateTo(Screen.Main.Camera) },
        toEditImageScreen = { viewModel.navigateTo(Screen.Main.EditImage) },
        toDocumentScreen = { viewModel.navigateTo(Screen.Main.Document()) },
        toExportScreen = { viewModel.navigateTo(Screen.Main.Export) },
        toAboutScreen = { viewModel.navigateTo(Screen.Overlay.About) },
        toLibrariesScreen = { viewModel.navigateTo(Screen.Overlay.Libraries) },
        toSettingsScreen = if (launchMode == LaunchMode.EXTERNAL_SCAN_TO_PDF) null else {
            {
                viewModel.navigateTo(Screen.Overlay.Settings)
            }
        },
        toOcrLanguagesScreen = { viewModel.navigateTo(Screen.Overlay.OcrLanguages) },
        back = {
            val origin = viewModel.currentScreen.value
            viewModel.navigateBack()
            val destination = viewModel.currentScreen.value
            if (destination == origin && launchMode == LaunchMode.EXTERNAL_SCAN_TO_PDF) {
                setResult(RESULT_CANCELED)
                finish()
            }
        },
        shouldDisplayBackButton = {
            viewModel.currentScreen.value !is Screen.Main.Camera
                    || launchMode == LaunchMode.EXTERNAL_SCAN_TO_PDF
                    || launchMode == LaunchMode.NORMAL
        },
        toHomeScreen = { viewModel.navigateTo(Screen.Main.Home) },
        toLibraryScreen = { viewModel.navigateTo(Screen.Main.Library) },
    )

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (viewModel.currentScreen.value is Screen.Main.Camera) {
                cameraViewModel.onVolumeKeyPressed()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
