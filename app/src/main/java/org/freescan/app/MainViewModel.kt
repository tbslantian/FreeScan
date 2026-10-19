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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freescan.app.data.ImageRepository
import org.freescan.app.data.Logger
import org.freescan.app.domain.CapturedPage
import org.freescan.app.domain.PageViewKey
import org.freescan.app.domain.Png
import org.freescan.app.domain.Jpeg
import org.freescan.app.domain.PageMetadata
import org.freescan.app.domain.Rotation
import org.freescan.app.domain.ScanPage
import org.freescan.app.ui.NavigationState
import org.freescan.app.ui.Screen
import org.freescan.app.ui.screens.crop.CropInitState
import org.freescan.app.ui.screens.document.CurrentPageUiState
import org.freescan.app.ui.screens.document.DocumentUiState
import org.freescan.app.ui.state.DocumentUiModel
import org.freescan.app.ui.state.PageThumbnail
import org.freescan.imageprocessing.ColorMode
import org.freescan.imageprocessing.ImageSize
import org.freescan.imageprocessing.Quad
import org.freescan.imageprocessing.Point
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    val imageRepository: ImageRepository,
    logger: Logger,
    private val startAtHome: Boolean = true,
): ViewModel() {

    private val _navigationState = MutableStateFlow<NavigationState?>(null)
    val currentScreen: StateFlow<Screen?> = _navigationState.map { it?.current }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _pages = MutableStateFlow<List<ScanPage>>(emptyList())


    init {
        viewModelScope.launch {
            val pages = imageRepository.pages()

            _pages.value = pages

            // Keep unfinished pages available from the home screen instead of
            // forcing every normal launch straight into the camera/resume flow.
            _navigationState.value = NavigationState.initial(startAtHome)
        }
    }

    val documentUiModel: StateFlow<DocumentUiModel> =
        _pages.map { pages ->
            pages.map {
                val jpeg = imageRepository.getThumbnail(it.key())
                PageThumbnail(it.key(), jpeg)
            }.toImmutableList()
        }
        .flowOn(Dispatchers.IO)
        .map { DocumentUiModel(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = DocumentUiModel()
        )

    private val _currentPageIndex = MutableStateFlow(0)
    private val _loadingPageId = MutableStateFlow<String?>(null)
    private val currentPageUiState: Flow<CurrentPageUiState?> =
        combine(_currentPageIndex, _pages, _loadingPageId) { index, pages, loadingId ->
            val page = pages.getOrNull(index)
            Pair(page, loadingId)
        }
            .mapLatest { (page,loadingId) ->
                page?.let {
                    val isLoading = (it.id == loadingId)
                    val canBeCropped = page.metadata != null && !page.hasManualEdits
                    val bitmap = try {
                         imageRepository.image(it.key())?.toBitmap()
                    } catch (e: Exception) {
                        logger.e("MainViewModel", "Failed to load image for ${it.id}", e)
                        null
                    }
                    CurrentPageUiState(
                        it.key(),
                        bitmap,
                        it.colorMode,
                        canBeCropped,
                        isLoading,
                        hasManualEdits = page.hasManualEdits,
                        removeShadows = page.removeShadows,
                    )
                }
            }
            .flowOn(Dispatchers.IO)

    val documentUiState: StateFlow<DocumentUiState> =
        combine(_currentPageIndex, currentPageUiState, documentUiModel) { index, page, document ->
            DocumentUiState(index, page, document)
        }
            .stateIn(
                viewModelScope, SharingStarted.Eagerly,
                DocumentUiState(0, null, DocumentUiModel())
            )

    fun onPageSelected(index: Int) {
        _currentPageIndex.value = index
    }

    fun navigateTo(destination: Screen) {
        if (destination is Screen.Main.Document) {
            require(_pages.value.isNotEmpty()) {
                "Cannot navigate to DocumentScreen with zero pages"
            }
            _currentPageIndex.value = min(_pages.value.size - 1, destination.initialPage)
        }
        _navigationState.update { it?.navigateTo(destination) }
    }

    fun navigateBack() {
        _navigationState.update { stack -> stack?.navigateBack() }
    }

    fun rotateCurrentPage(clockwise: Boolean) {
        viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                imageRepository.rotate(currentPage().id, clockwise)
                imageRepository.pages()
            }
            _pages.value = pages
        }
    }

    fun movePage(id: String, newIndex: Int) {
        viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                imageRepository.movePage(id, newIndex)
                imageRepository.pages()
            }
            _pages.value = pages
        }
    }

    fun deleteCurrentPage() {
        viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                imageRepository.delete(currentPage().id)
                imageRepository.pages()
            }

            if (pages.isEmpty()) {
                navigateTo(Screen.Main.Camera)
                _currentPageIndex.value = 0
            } else if (_currentPageIndex.value >= pages.size) {
                _currentPageIndex.value = pages.size - 1
            }
            _pages.value = pages
        }
    }

    fun setCurrentPageColorMode(colorMode: ColorMode) {
        viewModelScope.launch {
            val currentPage = currentPage()
            if (currentPage.colorMode != colorMode) {
                _loadingPageId.value = currentPage.id
                val pages = withContext(Dispatchers.IO) {
                    imageRepository.setColorMode(currentPage.id, colorMode)
                    imageRepository.pages()
                }
                _pages.value = pages
                _loadingPageId.value = null
            }
        }
    }

    fun setCurrentPageRemoveShadows(enabled: Boolean) {
        viewModelScope.launch {
            val currentPage = currentPage()
            if (currentPage.removeShadows != enabled) {
                _loadingPageId.value = currentPage.id
                try {
                    val pages = withContext(Dispatchers.IO) {
                        imageRepository.setRemoveShadows(currentPage.id, enabled)
                        imageRepository.pages()
                    }
                    _pages.value = pages
                } finally {
                    _loadingPageId.value = null
                }
            }
        }
    }

    fun setCurrentPageUserQuad(userQuad: Quad) {
        viewModelScope.launch {
            val currentPage = currentPage()
            val totalRotation = currentPage.totalRotation()
            val rotateIterations = (4 - totalRotation.degrees / 90) % 4
            val newQuad = userQuad.rotate90(rotateIterations, ImageSize(1, 1))
            _loadingPageId.value = currentPage.id
            val pages = withContext(Dispatchers.IO) {
                imageRepository.setUserQuad(currentPage.id, newQuad)
                imageRepository.pages()
            }
            _pages.value = pages
            _loadingPageId.value = null
        }
    }

    suspend fun saveManualEditedImage(key: PageViewKey, bitmap: Bitmap) {
        val png = withContext(Dispatchers.IO) {
            val unrotated = if (key.rotation == Rotation.R0) {
                bitmap
            } else {
                val matrix = Matrix().apply { postRotate(-key.rotation.degrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            try {
                val output = java.io.ByteArrayOutputStream()
                check(unrotated.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not encode the repaired scan page."
                }
                Png(output.toByteArray())
            } finally {
                if (unrotated !== bitmap) unrotated.recycle()
            }
        }
        val pages = withContext(Dispatchers.IO) {
            imageRepository.setManualEditedImage(key.pageId, png)
            imageRepository.pages()
        }
        _pages.value = pages
    }

    private fun currentPage(): ScanPage {
        val index = _currentPageIndex.value
        val pages = _pages.value
        return pages.getOrNull(index) ?: throw IllegalStateException(
            "No current page for index $index (${pages.size} pages)")
    }

    private var clearDocumentJob: Job? = null

    fun startNewDocument() {
        _pages.value = persistentListOf()
        clearDocumentJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                imageRepository.clear()
            }
        }
    }

    /** Open a saved PDF as an editable scan draft without changing the original file. */
    suspend fun importPdfForEditing(context: Context, uri: Uri, onProgress: (Int, Int) -> Unit) {
        clearDocumentJob?.join()
        val stagingDir = File(context.cacheDir, "pdf-edit-${UUID.randomUUID()}")
        check(stagingDir.mkdirs()) { "Could not prepare PDF import" }
        try {
            val renderedPages = withContext(Dispatchers.IO) {
                val staged = mutableListOf<Pair<File, ImageSize>>()
                val sourceFile = File(stagingDir, "source.pdf")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    sourceFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Could not open PDF")
                ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    PdfRenderer(it).use { renderer ->
                        require(renderer.pageCount > 0) { "PDF has no pages" }
                        for (index in 0 until renderer.pageCount) {
                            currentCoroutineContext().ensureActive()
                            renderer.openPage(index).use { page ->
                                val scale = min(3.0, 3000.0 / max(page.width, page.height))
                                val width = max(1, (page.width * scale).toInt())
                                val height = max(1, (page.height * scale).toInt())
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    val transform = Matrix().apply { setScale(scale.toFloat(), scale.toFloat()) }
                                    page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    val file = File(stagingDir, "$index.jpg")
                                    file.outputStream().use { out ->
                                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                                            "Could not render PDF page ${index + 1}"
                                        }
                                    }
                                    staged += file to ImageSize(width, height)
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                            withContext(Dispatchers.Main) { onProgress(index + 1, renderer.pageCount) }
                        }
                    }
                }
                staged
            }
            withContext(Dispatchers.IO) {
                imageRepository.clear()
                val wholePage = Quad(
                    Point(0.0, 0.0), Point(1.0, 0.0),
                    Point(1.0, 1.0), Point(0.0, 1.0),
                )
                renderedPages.forEach { (file, size) ->
                    currentCoroutineContext().ensureActive()
                    val jpeg = Jpeg(file.readBytes())
                    imageRepository.add(
                        jpeg, jpeg,
                        PageMetadata(wholePage, Rotation.R0, ColorMode.COLOR, size, null),
                        ColorMode.COLOR,
                        removeShadows = false,
                    )
                }
                imageRepository.pages()
            }.also { pages ->
                _pages.value = pages
                navigateTo(Screen.Main.Document())
            }
        } catch (e: Exception) {
            _pages.value = withContext(Dispatchers.IO) { imageRepository.pages() }
            throw e
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { stagingDir.deleteRecursively() }
        }
    }

    fun handleImageCaptured(capturedPage: CapturedPage) {
        viewModelScope.launch {
            clearDocumentJob?.join()
            val pages = withContext(Dispatchers.IO) {
                val sourceJpeg = capturedPage.sourceJpeg.await()
                imageRepository.add(
                    capturedPage.pageImage,
                    sourceJpeg,
                    capturedPage.metadata,
                    capturedPage.colorMode,
                    capturedPage.removeShadows,
                )
                imageRepository.pages()
            }
            _pages.value = pages
        }
    }

    private val _cropInitState = MutableStateFlow<CropInitState>(CropInitState.Loading)
    val cropInitState: StateFlow<CropInitState> = _cropInitState

    private var cropInitialStateJob: Job? = null
    fun onClickOnCropButton() {
        cropInitialStateJob?.cancel()
        cropInitialStateJob = viewModelScope.launch {
            _cropInitState.value = CropInitState.Loading

            val page = currentPage()

            val metadata = page.metadata
            val rotation = page.totalRotation()

            val bitmap = withContext(Dispatchers.IO) {
                val source = imageRepository.source(page.id)
                val bytes = source?.bytes ?: return@withContext null

                val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (original != null && rotation != Rotation.R0) {
                    val matrix = Matrix().apply { postRotate(rotation.degrees.toFloat()) }
                    Bitmap.createBitmap(
                        original, 0, 0, original.width, original.height, matrix, true
                    )
                } else {
                    original
                }
            }

            val quad = metadata?.normalizedQuad?.rotate90(
                rotation.degrees / 90,
                ImageSize(1, 1)
            )

            _cropInitState.value = if (bitmap == null || quad == null)
                CropInitState.Error
            else
                CropInitState.Ready(page.id, bitmap, quad)
            navigateTo(Screen.Main.EditImage)
        }

    }
}
