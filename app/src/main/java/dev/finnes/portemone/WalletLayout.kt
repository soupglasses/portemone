// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Scan surfaces stay comfortably hand-sized when a foldable opens. Text and
// membership rows get their own reading column, with gutters on narrow screens.
internal val CardFaceMaxWidth = 420.dp
internal val CardCarouselSidePadding = 24.dp
internal val CardCarouselMaxWidth = CardFaceMaxWidth + CardCarouselSidePadding * 2
internal fun Modifier.walletTextColumn(): Modifier =
    widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 20.dp)
