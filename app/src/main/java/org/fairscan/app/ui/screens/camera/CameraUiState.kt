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
package org.fairscan.app.ui.screens.camera

import androidx.compose.runtime.Stable

@Stable
data class CameraUiState(
    val pageCount: Int,
    val liveAnalysisState: LiveAnalysisState,
    val captureState: CaptureState,
    val importState: ImportState,
    val showCaptureError: Boolean,
    val isLandscape: Boolean,
    val isDebugMode: Boolean,
    val isTorchEnabled: Boolean,
    val flashMode: Int = 0,
    val resolutionMode: Int = 2,
)
