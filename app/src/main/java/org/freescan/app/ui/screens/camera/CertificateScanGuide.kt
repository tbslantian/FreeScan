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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.freescan.app.R

@Composable
internal fun CertificateScanGuide(
    pageCount: Int,
    template: CertificateScanTemplate,
    autoCropEnabled: Boolean,
    onTemplateSelected: (CertificateScanTemplate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isFrontSide = pageCount % 2 == 0
    val cardNumber = pageCount / 2 + 1
    val title = stringResource(
        if (isFrontSide) R.string.certificate_scan_front else R.string.certificate_scan_back,
        cardNumber,
    )
    val labelShape = RoundedCornerShape(50)

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.68f), labelShape)
                .padding(horizontal = 16.dp, vertical = 7.dp),
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CertificateScanTemplate.entries.forEach { option ->
                FilterChip(
                    selected = option == template,
                    onClick = { onTemplateSelected(option) },
                    label = {
                        Text(
                            text = stringResource(option.label),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Keep portrait documents readable while fitting the live guide into the camera preview.
            val availableFrameHeight = (maxHeight - 150.dp).coerceAtLeast(100.dp)
            val frameWidth = minOf(
                maxWidth * 0.92f,
                300.dp,
                220.dp * template.guideAspectRatio,
                availableFrameHeight * template.guideAspectRatio,
            )
            CertificateDocumentPreview(
                template = template,
                modifier = Modifier.width(frameWidth).aspectRatio(template.guideAspectRatio),
            )
        }

        Text(
            text = stringResource(
                if (autoCropEnabled) R.string.certificate_scan_guide_auto
                else R.string.certificate_scan_guide_manual,
            ),
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CertificateDocumentPreview(template: CertificateScanTemplate, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawCertificateTemplate(template)
    }
}

private fun DrawScope.drawCertificateTemplate(template: CertificateScanTemplate) {
    val width = size.width
    val height = size.height
    val shortSide = minOf(width, height)
    val borderWidth = (shortSide * 0.012f).coerceAtLeast(2f)
    val detailWidth = (shortSide * 0.006f).coerceAtLeast(1.4f)
    val white = Color.White
    val muted = white.copy(alpha = 0.72f)
    val cardRadius = shortSide * 0.055f

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color = muted) {
        drawLine(color, Offset(x1 * width, y1 * height), Offset(x2 * width, y2 * height), detailWidth)
    }

    fun outline(left: Float, top: Float, right: Float, bottom: Float, radius: Float = 0f) {
        drawRoundRect(
            color = muted,
            topLeft = Offset(left * width, top * height),
            size = Size((right - left) * width, (bottom - top) * height),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(detailWidth),
        )
    }

    fun rows(left: Float, top: Float, right: Float, count: Int, bottom: Float = 0.82f) {
        if (count <= 0) return
        val gap = (bottom - top) / count
        repeat(count) { index ->
            val y = top + gap * (index + 0.5f)
            line(left, y, right, y)
        }
    }

    val outerRadius = when (template) {
        CertificateScanTemplate.SINGLE_CARD,
        CertificateScanTemplate.ID_CARD,
        CertificateScanTemplate.DOUBLE_CARD,
        CertificateScanTemplate.DRIVER_LICENSE,
        CertificateScanTemplate.VEHICLE_LICENSE -> cardRadius
        else -> shortSide * 0.012f
    }
    drawRoundRect(
        color = white,
        topLeft = Offset(borderWidth / 2, borderWidth / 2),
        size = Size(width - borderWidth, height - borderWidth),
        cornerRadius = CornerRadius(outerRadius, outerRadius),
        style = Stroke(borderWidth),
    )

    when (template) {
        CertificateScanTemplate.SINGLE_CARD -> {
            outline(0.07f, 0.19f, 0.30f, 0.81f, cardRadius * 0.3f)
            line(0.39f, 0.29f, 0.91f, 0.29f)
            rows(0.39f, 0.37f, 0.86f, 4, 0.76f)
        }
        CertificateScanTemplate.ID_CARD -> {
            outline(0.09f, 0.31f, 0.25f, 0.54f, shortSide * 0.012f)
            outline(0.75f, 0.20f, 0.91f, 0.80f, shortSide * 0.012f)
            line(0.32f, 0.27f, 0.68f, 0.27f)
            rows(0.32f, 0.34f, 0.68f, 4, 0.75f)
        }
        CertificateScanTemplate.DOUBLE_CARD -> {
            line(0.5f, 0.06f, 0.5f, 0.94f)
            outline(0.025f, 0.10f, 0.475f, 0.90f, cardRadius * 0.55f)
            outline(0.525f, 0.10f, 0.975f, 0.90f, cardRadius * 0.55f)
            outline(0.06f, 0.27f, 0.17f, 0.73f, shortSide * 0.01f)
            outline(0.56f, 0.27f, 0.67f, 0.73f, shortSide * 0.01f)
            rows(0.21f, 0.30f, 0.44f, 4, 0.72f)
            rows(0.71f, 0.30f, 0.94f, 4, 0.72f)
        }
        CertificateScanTemplate.HOUSEHOLD_REGISTER -> {
            line(0.12f, 0.19f, 0.88f, 0.19f, white)
            rows(0.12f, 0.25f, 0.88f, 6, 0.84f)
            line(0.38f, 0.35f, 0.38f, 0.84f)
            line(0.67f, 0.35f, 0.67f, 0.84f)
        }
        CertificateScanTemplate.HOUSEHOLD_REGISTER_DOUBLE -> {
            line(0.5f, 0.07f, 0.5f, 0.93f, white)
            line(0.08f, 0.20f, 0.92f, 0.20f, white)
            rows(0.08f, 0.26f, 0.46f, 5, 0.88f)
            rows(0.54f, 0.26f, 0.92f, 5, 0.88f)
            line(0.26f, 0.36f, 0.26f, 0.88f)
            line(0.74f, 0.36f, 0.74f, 0.88f)
        }
        CertificateScanTemplate.BUSINESS_LICENSE -> {
            line(0.17f, 0.13f, 0.83f, 0.13f, white)
            line(0.25f, 0.20f, 0.75f, 0.20f)
            rows(0.15f, 0.27f, 0.84f, 6, 0.73f)
            outline(0.60f, 0.75f, 0.83f, 0.92f, shortSide * 0.06f)
        }
        CertificateScanTemplate.PASSPORT -> {
            line(0.5f, 0.08f, 0.5f, 0.92f, white)
            outline(0.08f, 0.18f, 0.38f, 0.72f, shortSide * 0.015f)
            rows(0.58f, 0.20f, 0.91f, 5, 0.63f)
            line(0.08f, 0.78f, 0.92f, 0.78f)
            line(0.08f, 0.84f, 0.92f, 0.84f)
            line(0.08f, 0.90f, 0.82f, 0.90f)
        }
        CertificateScanTemplate.DRIVER_LICENSE -> {
            outline(0.08f, 0.20f, 0.30f, 0.80f, shortSide * 0.015f)
            line(0.38f, 0.24f, 0.89f, 0.24f, white)
            rows(0.38f, 0.31f, 0.88f, 5, 0.77f)
            line(0.61f, 0.43f, 0.61f, 0.77f)
        }
        CertificateScanTemplate.VEHICLE_LICENSE -> {
            line(0.09f, 0.20f, 0.91f, 0.20f, white)
            line(0.09f, 0.36f, 0.91f, 0.36f)
            line(0.09f, 0.56f, 0.91f, 0.56f)
            line(0.09f, 0.77f, 0.91f, 0.77f)
            line(0.34f, 0.20f, 0.34f, 0.77f)
            line(0.66f, 0.20f, 0.66f, 0.77f)
        }
    }
}
