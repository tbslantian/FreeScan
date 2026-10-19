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
package org.freescan.app.domain

import org.freescan.app.R
import org.freescan.imageprocessing.ColorMode

enum class ExportQuality(
    val jpegQuality: Int,
    private val maxPixels: Long,
    val labelResource: Int
) {
    LOW(
        jpegQuality = 60,
        maxPixels = 1_000_000,
        R.string.export_quality_low,
    ),
    BALANCED(
        jpegQuality = 75,
        maxPixels = 2_000_000,
        R.string.export_quality_balanced,
    ),
    HIGH(
        jpegQuality = 93,
        maxPixels = 12_000_000,
        R.string.export_quality_high,
    );
    fun maxPixels(colorMode: ColorMode) =
        if (colorMode == ColorMode.BLACK_AND_WHITE)
            maxPixels * 4
        else
            maxPixels

}
