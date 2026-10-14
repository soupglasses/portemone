// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.finnes.portemone.diagnostics.AppDiagnostics
import dev.finnes.portemone.location.CardProximity
import dev.finnes.portemone.location.GeofenceCoordinator
import dev.finnes.portemone.location.LocationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home and notifications project the same persisted store presence. */
internal data class WalletHomeState(
    val restoring: Boolean = true,
    val loading: Boolean = false,
    val entries: List<CardProximity.Entry> = emptyList(),
    val automaticEnabled: Boolean = false,
    val selectedId: String? = null,
    val error: String? = null,
)

internal class WalletHomeModel(application: Application) : AndroidViewModel(application) {
    private val settings = LocationSettings(application)
    private val locations = GeofenceCoordinator(application)
    private val cache = HomeSnapshotCache(application)
    private val mutableState = MutableStateFlow(WalletHomeState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var publicationGeneration = 0L

    private val stopObserving = settings.observe { reconcileEdits() }

    override fun onCleared() {
        stopObserving()
        super.onCleared()
    }

    init {
        viewModelScope.launch {
            try {
                val cards = readCards()
                val restored =
                    withContext(Dispatchers.IO) { cache.restore(cards, settings.enabled) }
                if (restored != null) {
                    val entries = CardProximity.reconcile(restored.entries, cards, activeStoreIds())
                    mutableState.value = restored.copy(
                        entries = entries,
                        selectedId = restored.selectedId?.takeIf { id -> entries.any { it.nearby && it.card.id == id } }
                            ?: entries.firstOrNull { it.nearby }?.card?.id
                    )
                    AppDiagnostics.event("Home", "Restored saved card view")
                    onForeground()
                } else if (settings.enabled && !settings.hasPendingCardChanges()) {
                    mutableState.value = WalletHomeState(
                        restoring = false,
                        loading = true,
                        entries = CardProximity.order(cards, emptySet(), null),
                        automaticEnabled = true
                    )
                    refresh(initial = true)
                } else {
                    publish(
                        WalletHomeState(
                            restoring = false,
                            entries = CardProximity.order(cards, activeStoreIds(), null),
                            automaticEnabled = settings.enabled
                        )
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = WalletHomeState(
                    restoring = false, automaticEnabled = settings.enabled,
                    error = getApplication<Application>().getString(R.string.could_not_open_your_saved_cards_try_again)
                )
                AppDiagnostics.event("Home", "Could not load saved cards", error)
            }
        }
    }

    fun onForeground() {
        if (mutableState.value.restoring) return
        val age = System.currentTimeMillis() - settings.lastNearbyUpdateAtMillis
        if (settings.enabled &&
            (!settings.hasCurrentDeviceRegistration() ||
                    (!settings.hasPendingCardChanges() &&
                            (!locations.hasCurrentRegistration() || age !in 0..5 * 60_000L)))
        ) {
            refresh(initial = true)
        } else reconcileEdits()
    }

    fun refresh(initial: Boolean = false) {
        if (refreshJob?.isActive == true || mutableState.value.restoring) return
        val generation = ++publicationGeneration
        val previous = mutableState.value.copy(loading = false)
        mutableState.value = previous.copy(loading = settings.enabled, error = null)
        refreshJob = viewModelScope.launch {
            try {
                val fix = if (settings.enabled) locations.refreshNearby(force = !initial) else null
                val cards = readCards()
                // Settings can change while a provider is acquiring its fix.
                val enabled = settings.enabled
                if (generation != publicationGeneration) return@launch
                val entries =
                    CardProximity.order(cards, if (enabled) settings.inside else emptySet(), fix)
                publish(
                    WalletHomeState(
                        restoring = false,
                        entries = entries,
                        automaticEnabled = enabled,
                        selectedId = previous.selectedId?.takeIf { id -> initial && entries.any { it.nearby && it.card.id == id } }
                            ?: entries.firstOrNull { it.nearby }?.card?.id
                    )
                )
                AppDiagnostics.event(
                    "Home",
                    "Published completed card view; nearby=${entries.count { it.nearby }}"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A failed check must end the loading screen without publishing a
                // partial location result or moving the previous checkout selection.
                if (generation == publicationGeneration) {
                    val entries =
                        CardProximity.reconcile(previous.entries, readCards(), activeStoreIds())
                    publish(
                        previous.copy(
                            entries = entries, automaticEnabled = settings.enabled,
                            selectedId = previous.selectedId?.takeIf { id -> entries.any { it.nearby && it.card.id == id } }
                                ?: entries.firstOrNull { it.nearby }?.card?.id,
                            error = GeofenceCoordinator.errorMessage(error, getApplication<Application>().resources)
                        )
                    )
                }
                AppDiagnostics.event(
                    "Home",
                    "Location refresh failed; kept previous card view",
                    error
                )
            }
        }
    }

    fun select(id: String) {
        val current = mutableState.value
        if (current.loading || current.entries.none { it.nearby && it.card.id == id }) return
        mutableState.value = current.copy(selectedId = id)
        cache.select(id)
    }

    /** Store events and edits update presence without interrupting the selected card. */
    fun reconcileEdits() {
        if (mutableState.value.restoring || mutableState.value.loading || refreshJob?.isActive == true) return
        if (settings.enabled && !settings.hasPendingCardChanges() && !locations.hasCurrentRegistration()) {
            refresh(initial = true)
            return
        }
        // A slower pre-edit query must never replace a later refresh or resurrect
        // a membership that a newer catalog read has already removed.
        val generation = ++publicationGeneration
        viewModelScope.launch {
            val cards = try {
                readCards()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppDiagnostics.event("Home", "Could not reconcile card edits", error)
                return@launch
            }
            val current = mutableState.value
            if (generation != publicationGeneration || current.restoring || current.loading) return@launch
            val nearbyStores = activeStoreIds()
            if (cards.associateBy { it.id } == current.entries.associate { it.card.id to it.card } &&
                settings.enabled == current.automaticEnabled &&
                current.entries.all { it.nearby == (it.card.isUsable() && it.card.stores.any { store -> store.id in nearbyStores }) }) return@launch
            val enabled = settings.enabled
            val entries = if (enabled != current.automaticEnabled) {
                CardProximity.order(cards, nearbyStores, null)
            } else CardProximity.reconcile(current.entries, cards, nearbyStores)
            publish(
                current.copy(
                    entries = entries, automaticEnabled = enabled,
                    selectedId = current.selectedId?.takeIf { id -> entries.any { it.nearby && it.card.id == id } }
                        ?: entries.firstOrNull { it.nearby }?.card?.id
                )
            )
        }
    }

    private fun activeStoreIds(): Set<String> =
        settings.activeStoreIds()

    private suspend fun readCards(): List<Card> =
        withContext(Dispatchers.IO) { settings.cards() }

    private fun publish(value: WalletHomeState) {
        mutableState.value = value
        cache.save(value)
    }
}
