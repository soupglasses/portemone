// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** A finite track of real card faces, with tappable neighbours peeking into its margins. */
@Composable
fun NearbyCardDeck(
    cards: List<Card>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onOpen: (Card) -> Unit,
    modifier: Modifier = Modifier,
    proximityLabel: (Card) -> String? = { null },
) {
    if (cards.isEmpty()) return
    // Preserve the selected identity when nearby membership changes, and retain
    // the actual native faces throughout ordinary swipes.
    key(cards.map { it.id }) {
        CardCarousel(cards, selectedId, onSelect, onOpen, modifier, proximityLabel)
    }
}

@Composable
private fun CardCarousel(cards: List<Card>, selectedId: String, onSelect: (String) -> Unit,
    onOpen: (Card) -> Unit, modifier: Modifier, proximityLabel: (Card) -> String?) {
    val resources = LocalResources.current
    val pager = rememberPagerState(
        initialPage = cards.indexOfFirst { it.id == selectedId }.coerceAtLeast(0),
        pageCount = { cards.size })
    var gestureStartPage by remember { mutableIntStateOf(pager.currentPage) }
    val nativeFling = PagerDefaults.flingBehavior(pager)
    val cardFling = remember(pager, nativeFling) {
        object : TargetedFlingBehavior {
            override suspend fun ScrollScope.performFling(
                initialVelocity: Float, onRemainingDistanceUpdated: (Float) -> Unit,
            ): Float = with(nativeFling) {
                // The viewport is wider than a card. A full-width drag can already
                // pass a page: don't fling past another, but allow deliberate reversal.
                val travelledPages = pager.currentPage - gestureStartPage
                performFling(if (travelledPages * initialVelocity > 0f) 0f else initialVelocity,
                    onRemainingDistanceUpdated)
            }
        }
    }
    val scope = rememberCoroutineScope()
    var userTurn by remember { mutableStateOf(false) }
    val select by rememberUpdatedState(onSelect)
    val selectedIdentity by rememberUpdatedState(selectedId)
    val card = cards[pager.settledPage]
    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                gestureStartPage = pager.currentPage
                userTurn = true
                select(cards[pager.settledPage].id)
            }
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.isScrollInProgress to pager.settledPage }.collect { (moving, page) ->
            // Accessibility paging has no drag interaction, but must also keep
            // the proximity label and checkout selection tied to the visible code.
            if (!moving && (userTurn || cards[page].id != selectedIdentity)) {
                userTurn = false
                select(cards[page].id)
            }
        }
    }
    LaunchedEffect(selectedId) {
        val index = cards.indexOfFirst { it.id == selectedId }
        if (index >= 0 && !userTurn && !pager.isScrollInProgress && pager.settledPage != index) {
            pager.scrollToPage(index)
        }
    }
    fun turnTo(page: Int): Boolean {
        if (page !in cards.indices || page == pager.settledPage || userTurn || pager.isScrollInProgress) return false
        userTurn = true
        select(card.id)
        scope.launch { pager.animateScrollToPage(page) }
        return true
    }
    Column(modifier.testTag("nearby_card_deck"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth()) {
            HorizontalPager(state = pager,
                modifier = Modifier.fillMaxWidth().wrapContentHeight()
                    .testTag("nearby_card_pager").semantics {
                        customActions = buildList {
                            cards.getOrNull(pager.settledPage - 1)?.let { previous ->
                                add(CustomAccessibilityAction(resources.getString(R.string.previous_card, previous.accessibleLabel(resources))) {
                                    turnTo(pager.settledPage - 1)
                                })
                            }
                            cards.getOrNull(pager.settledPage + 1)?.let { next ->
                                add(CustomAccessibilityAction(resources.getString(R.string.next_card, next.accessibleLabel(resources))) {
                                    turnTo(pager.settledPage + 1)
                                })
                            }
                        }
                    },
                contentPadding = PaddingValues(horizontal = CardCarouselSidePadding), pageSpacing = 8.dp,
                flingBehavior = cardFling,
                beyondViewportPageCount = if (cards.size > 1) 1 else 0,
                userScrollEnabled = cards.size > 1,
                key = { page -> cards[page].id },
            ) { page ->
                val pageCard = cards[page]
                val active = !pager.isScrollInProgress && page == pager.settledPage
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardFace(pageCard, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .testTag(if (active) "nearby_card_${pageCard.id}" else "deck_preview_${pageCard.id}")
                        .semantics {
                            contentDescription = if (active) resources.getString(R.string.open, pageCard.accessibleLabel(resources)) else resources.getString(R.string.show, pageCard.accessibleLabel(resources))
                        }
                        // A ripple over the scan surface changes its contrast while
                        // the neighbouring card is being brought into view.
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            enabled = !pager.isScrollInProgress && !userTurn, role = Role.Button,
                            onClickLabel = if (active) resources.getString(R.string.open_card) else resources.getString(R.string.show_card)) {
                            if (active) onOpen(pageCard) else turnTo(page)
                        }, selected = active)
                    // Keep both text lines in the page, even when distance is unknown,
                    // so the card and caption travel together without changing height.
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("nearby_caption_${pageCard.id}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(pageCard.label(), style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(proximityLabel(pageCard).orEmpty(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 1,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun Card.accessibleLabel(resources: android.content.res.Resources): String {
    val ending = rawValue.filterNot { it.isWhitespace() }.takeLast(4)
    return resources.getString(R.string.ending, label(), ending)
}
private fun Card.label() = "$brandName $cardName".trim()
