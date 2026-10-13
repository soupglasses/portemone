// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A searching state, with no suggestion of measurable progress or a completion time. */
@Composable
internal fun LocationSearchScreen() {
    val resources = LocalResources.current
    val transition = rememberInfiniteTransition(label = "Location search")
    val pulse by transition.animateFloat(.12f, .24f,
        infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "Globe halo")
    val color = MaterialTheme.colorScheme.primary
    Surface(Modifier.fillMaxSize().testTag("wallet_loading"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Canvas(Modifier.size(112.dp)) {
                drawCircle(color.copy(alpha = pulse), radius = size.minDimension / 2)
                val diameter = size.minDimension * .55f
                val origin = Offset(center.x - diameter / 2, center.y - diameter / 2)
                val stroke = Stroke(2.dp.toPx())
                drawCircle(color, diameter / 2, style = stroke)
                drawOval(color, Offset(center.x - diameter * .21f, origin.y), Size(diameter * .42f, diameter), style = stroke)
                drawOval(color, Offset(origin.x, center.y - diameter * .17f), Size(diameter, diameter * .34f), style = stroke)
            }
            Spacer(Modifier.height(28.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.finding_nearby_stores), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.checking_your_location), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
