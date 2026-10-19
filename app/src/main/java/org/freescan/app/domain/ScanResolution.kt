/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */
package org.freescan.app.domain

import org.freescan.app.R

/** Maximum long-edge size of the processed scan image. */
enum class ScanResolution(val maxDimension: Int, val jpegQuality: Int, val labelResource: Int) {
    FULL_HD(1920, 84, R.string.scan_resolution_1080p),
    TWO_K(2560, 89, R.string.scan_resolution_2k),
    FOUR_K(3840, 93, R.string.scan_resolution_4k);

    fun next(): ScanResolution = when (this) {
        FOUR_K -> TWO_K
        TWO_K -> FULL_HD
        FULL_HD -> FOUR_K
    }
}
