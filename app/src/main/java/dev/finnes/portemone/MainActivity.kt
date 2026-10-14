// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.finnes.portemone.diagnostics.AppDiagnostics
import dev.finnes.portemone.location.CardProximity
import dev.finnes.portemone.location.LocationSettings
import dev.finnes.portemone.notifications.CardNotificationManager
import dev.finnes.portemone.ui.theme.WalletTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val home = ViewModelProvider(this)[WalletHomeModel::class.java]
        // Hold the launch frame only for local disk restoration. A GPS request
        // releases it immediately into the dedicated location-search screen.
        val decor = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(object :
            android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (home.state.value.restoring) return false
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        setContent {
            WalletTheme {
                val state by home.state.collectAsStateWithLifecycle()
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                var internalNavigation by remember { mutableStateOf(false) }
                DisposableEffect(lifecycle) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            internalNavigation = false
                            home.onForeground()
                            val settings = LocationSettings(this@MainActivity)
                            if (settings.enabled) CardNotificationManager(this@MainActivity)
                                .syncNearby(settings.activeCards(), onlyMissing = true)
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                val deckCards = state.entries.filter { it.nearby }
                val otherCards = state.entries.filterNot { it.nearby }
                val selectedNearby =
                    deckCards.find { it.card.id == state.selectedId } ?: deckCards.firstOrNull()
                val refreshError = state.error
                DisposableEffect(selectedNearby != null && !state.loading) {
                    if (selectedNearby != null && !state.loading) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                fun openCard(card: Card) {
                    if (state.loading || internalNavigation || !lifecycle.currentState.isAtLeast(
                            Lifecycle.State.RESUMED
                        )
                    ) return
                    home.select(card.id)
                    internalNavigation = true
                    startActivity(CardActivity.intent(this@MainActivity, card))
                }

                fun refresh() {
                    home.refresh()
                    scope.launch { listState.scrollToItem(0) }
                }

                fun openSettings() {
                    if (state.loading) return
                    internalNavigation = true
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    // Match the search screen's exit fade when a refresh starts. The
                    // old cards are non-interactive and leave composition after 240ms.
                    AnimatedVisibility(
                        visible = !state.loading,
                        enter = EnterTransition.None, exit = fadeOut(tween(240)),
                        modifier = if (state.loading) Modifier.clearAndSetSemantics {} else Modifier) {
                        Scaffold(topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        stringResource(R.string.app_name),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                actions = {
                                    IconButton(onClick = ::openSettings) {
                                        Icon(
                                            painterResource(R.drawable.ic_settings),
                                            contentDescription = getString(R.string.settings)
                                        )
                                    }
                                })
                        }) { padding ->
                            PullToRefreshBox(
                                isRefreshing = false,
                                onRefresh = ::refresh,
                                modifier = Modifier.fillMaxSize().padding(padding)
                            ) {
                                LazyColumn(
                                    Modifier.fillMaxSize().testTag("wallet_cards"),
                                    state = listState,
                                    contentPadding = PaddingValues(vertical = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    fun androidx.compose.foundation.lazy.LazyListScope.section(
                                        key: String,
                                        title: String,
                                        entries: List<CardProximity.Entry>
                                    ) {
                                        item("heading_$key") {
                                            Row(
                                                Modifier.walletTextColumn()
                                                    .padding(vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Text(
                                                    title,
                                                    modifier = Modifier.weight(1f),
                                                    style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                                var adding by remember { mutableStateOf(false) }
                                                Box {
                                                    IconButton(
                                                        onClick = { adding = true },
                                                        modifier = Modifier.testTag("add_card")
                                                    ) {
                                                        Text(
                                                            "+",
                                                            style = MaterialTheme.typography.headlineSmall,
                                                            modifier = Modifier.semantics {
                                                                contentDescription = getString(R.string.add_card)
                                                            })
                                                    }
                                                    DropdownMenu(
                                                        expanded = adding,
                                                        onDismissRequest = { adding = false }) {
                                                        fun launch(mode: String) {
                                                            adding = false
                                                            startActivity(
                                                                Intent(
                                                                    this@MainActivity,
                                                                    CardManagementActivity::class.java
                                                                ).putExtra("card_action", mode)
                                                            )
                                                        }
                                                        DropdownMenuItem(
                                                            text = { Text(stringResource(R.string.add_a_card)) },
                                                            onClick = { launch("add") })
                                                        DropdownMenuItem(
                                                            text = { Text(stringResource(R.string.import_pkpass)) },
                                                            onClick = { launch("import") })
                                                    }
                                                }
                                            }
                                        }
                                        items(entries, key = { it.card.id }) { entry ->
                                            MembershipCardRow(
                                                entry.card,
                                                nearby = entry.nearby,
                                                proximityLabel = entry.proximityLabel,
                                                onClick = { openCard(entry.card) },
                                                modifier = Modifier.walletTextColumn()
                                                    .testTag("wallet_card_${entry.card.id}")
                                            )
                                        }
                                    }
                                    if (selectedNearby != null) item("nearby_deck") {
                                        Column(
                                            Modifier.widthIn(max = CardCarouselMaxWidth)
                                                .fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            Text(
                                                stringResource(R.string.nearby),
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(
                                                    horizontal = 20.dp,
                                                    vertical = 8.dp
                                                )
                                            )
                                            NearbyCardDeck(
                                                cards = deckCards.map { it.card },
                                                selectedId = selectedNearby.card.id,
                                                onSelect = { id ->
                                                    home.select(id)
                                                    AppDiagnostics.event(
                                                        "UI",
                                                        "Nearby card selected: $id"
                                                    )
                                                },
                                                onOpen = ::openCard,
                                                proximityLabel = { card ->
                                                    deckCards.find { it.card.id == card.id }
                                                        ?.let { entry ->
                                                            entry.proximityLabel
                                                        }
                                                })
                                        }
                                    }
                                    if (state.entries.isEmpty() && !state.restoring) item("empty_cards") {
                                        Column(
                                            Modifier.walletTextColumn(),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Text(
                                                stringResource(R.string.add_your_first_card),
                                                style = MaterialTheme.typography.titleLarge
                                            )
                                            Text(stringResource(R.string.add_a_membership_or_import_an_apple_wallet_pass))
                                        }
                                    }
                                    section(
                                        "other",
                                        if (deckCards.isEmpty()) getString(R.string.cards) else getString(R.string.other_cards),
                                        otherCards
                                    )
                                    if (refreshError != null) item {
                                        Text(
                                            refreshError.orEmpty(),
                                            modifier = Modifier.walletTextColumn(),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = state.loading,
                        modifier = Modifier.fillMaxSize(),
                        enter = fadeIn(tween(240)),
                        exit = fadeOut(tween(240)),
                    ) {
                        LocationSearchScreen()
                    }
                }
            }
        }
    }
}
