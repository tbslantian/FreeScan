/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */
package org.freescan.app.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freescan.app.data.LibraryPdf
import org.freescan.app.data.PdfLibraryRepository

data class PdfLibraryUiState(
    val isLoading: Boolean = false,
    val pdfs: List<LibraryPdf> = emptyList(),
)

class PdfLibraryViewModel(private val repository: PdfLibraryRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(PdfLibraryUiState(isLoading = true))
    val uiState: StateFlow<PdfLibraryUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val pdfs = withContext(Dispatchers.IO) { repository.listPdfs() }
            _uiState.value = PdfLibraryUiState(pdfs = pdfs)
        }
    }

    fun deleteOrForget(pdf: LibraryPdf, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val deleted = repository.deleteOrForget(pdf)
            refresh()
            onResult(deleted)
        }
    }
}
