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
package org.freescan.app.ui.screens.camera

import androidx.annotation.StringRes
import org.freescan.app.R

/** The nine document templates exposed by the certificate scanner. */
enum class CertificateScanTemplate(
    @StringRes val label: Int,
    /** Width divided by height, matching the live guide window in portrait preview. */
    val guideAspectRatio: Float,
) {
    SINGLE_CARD(R.string.certificate_template_single_card, 1.586f),
    ID_CARD(R.string.certificate_template_id_card, 1.586f),
    DOUBLE_CARD(R.string.certificate_template_double_card, 3.172f),
    HOUSEHOLD_REGISTER(R.string.certificate_template_household_register, 1.28f),
    HOUSEHOLD_REGISTER_DOUBLE(R.string.certificate_template_household_register_double, 1.82f),
    BUSINESS_LICENSE(R.string.certificate_template_business_license, 0.707f),
    PASSPORT(R.string.certificate_template_passport, 1.42f),
    DRIVER_LICENSE(R.string.certificate_template_driver_license, 1.586f),
    VEHICLE_LICENSE(R.string.certificate_template_vehicle_license, 1.586f),
}
