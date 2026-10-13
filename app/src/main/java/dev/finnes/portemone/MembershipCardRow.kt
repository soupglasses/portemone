// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Identify memberships by name and number; only the selected card exposes a scannable code. */
@Composable
fun MembershipCardRow(
    card: Card,
    nearby: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    proximityLabel: String? = null
) {
    val resources = LocalResources.current
    val identifier = remember(card.rawValue) {
        val text = card.rawValue
        val digits = text.filterNot { it.isWhitespace() }
        // Group from the end so the final four digits stay recognizable at any length.
        if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) {
            digits.reversed().chunked(4).joinToString(" ").reversed()
        } else text
    }
    val foreground = if (androidx.core.graphics.ColorUtils.calculateContrast(
            android.graphics.Color.WHITE, card.brandColor
        ) >= 4.5
    ) Color.White else Color.Black
    val status = when {
        card.statusLabelRes()?.let { resources.getString(it) } != null -> card.statusLabelRes()?.let { resources.getString(it) }!!
        nearby -> resources.getString(R.string.nearby_card_available)
        card.stores.isEmpty() -> resources.getString(R.string.no_store_locations)
        else -> resources.getQuantityString(R.plurals.saved_stores, card.stores.size, card.stores.size)
    }
    Surface(
        onClick = onClick, modifier = modifier.semantics { stateDescription = status },
        shape = RoundedCornerShape(16.dp), color = Color(card.brandColor),
        contentColor = foreground
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 88.dp)
                .then(
                    if (card.shaded) Modifier.background(
                        Brush.linearGradient(
                            listOf(
                                Color(card.brandColor),
                                Color(CardAppearance.shade(card.brandColor))
                            )
                        )
                    ) else Modifier
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${card.brandName} ${card.cardName}".trim(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    identifier,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 0.sp,
                        textDirection = TextDirection.ContentOrLtr
                    ),
                    color = foreground.copy(alpha = .9f),
                    maxLines = 1, overflow = TextOverflow.StartEllipsis
                )
                if (proximityLabel != null) Text(
                    proximityLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = foreground.copy(alpha = .85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                Modifier.width(76.dp), horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val importedLogo = remember(card.logoImage) {
                    card.logoImage?.let(PassImages::decodeLogo)?.let { it.asImageBitmap() }
                }
                if (importedLogo != null) Image(
                    importedLogo,
                    contentDescription = null,
                    modifier = Modifier.width(76.dp).height(48.dp),
                    alignment = Alignment.CenterEnd
                )
                else Image(
                    painterResource(card.logo), contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
                if (card.statusLabelRes()?.let { resources.getString(it) } != null || nearby) Text(
                    card.statusLabelRes()?.let { resources.getString(it) } ?: resources.getString(R.string.nearby),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
