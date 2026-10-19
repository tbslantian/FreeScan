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
package org.freescan.app.data

import org.freescan.app.domain.EncodedImage
import org.freescan.app.domain.Jpeg
import org.freescan.app.domain.PageMetadata
import org.freescan.imageprocessing.ColorMode

interface ImageTransformations {

    fun rotate(input: EncodedImage, rotationDegrees: Int): EncodedImage

    fun resizeToThumbnail(input: EncodedImage): EncodedImage

    fun process(
        source: Jpeg,
        metadata: PageMetadata,
        colorMode: ColorMode
    ): EncodedImage

    fun process(
        source: Jpeg,
        metadata: PageMetadata,
        colorMode: ColorMode,
        removeShadows: Boolean,
    ): EncodedImage = process(source, metadata, colorMode)

}
