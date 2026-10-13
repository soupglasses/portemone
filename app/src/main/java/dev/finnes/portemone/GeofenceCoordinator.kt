// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import dev.finnes.portemone.location.*

class GeofenceCoordinator internal constructor(
    context: Context,
    private val backends: LocationBackends,
    private val scheduleCardChanges: () -> Unit,
) {
    constructor(context: Context) : this(
        context, LocationBackendFactory(context.applicationContext),
        { CardChangeWork.enqueue(context.applicationContext) }
    )

    private val context = context.applicationContext
    private val settings = LocationSettings(this.context)
    fun selectedEngine(): LocationEngine = backends.resolve(settings.locationEngine)
    private fun geofences() = backends.geofences(selectedEngine())
    private fun previousGeofences() = backends.geofences(settings.registeredEngine)
    private val notifications = CardNotificationManager(this.context)
    internal fun hasCurrentRegistration(): Boolean =
        hasPreciseLocation() && hasBackgroundLocation() && settings.hasCurrentRegistration() &&
                settings.registeredEngine == selectedEngine() && geofences().hasDelivery()

    fun hasPreciseLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasBackgroundLocation() = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun checkLocationServices() = geofences().checkAvailability()

    suspend fun currentLocation(maxAgeMillis: Long = 30_000): Location {
        check(hasPreciseLocation()) { context.getString(R.string.location_allow_precise_location_first) }
        val engine = selectedEngine()
        AppDiagnostics.event(
            "Location",
            "Requesting one location fix; engine=$engine, maxAge=${maxAgeMillis}ms"
        )
        return backends.currentLocation(engine).currentLocation(maxAgeMillis).also {
            AppDiagnostics.event("Location", "Location fix received; ${fixSummary(it)}")
        }
    }

    suspend fun changeEngine(engine: LocationEngine) {
        lock.withLock {
            if (settings.locationEngine == engine) return
            if (selectedEngine() == backends.resolve(engine)) {
                settings.locationEngine = engine
                return
            }
            settings.invalidateRegistration()
            settings.inside = emptySet()
            notifications.hide()
            CardWidgetProvider.updateAll(context)
            previousGeofences().removeAll()
            settings.locationEngine = engine
            settings.status = context.getString(R.string.location_location_engine, context.getString(selectedEngine().labelRes))
        }
        if (settings.enabled) refreshNearby(force = true)
    }

    suspend fun enable() {
        AppDiagnostics.event("Geofence", "Enable/refresh requested")
        check(hasPreciseLocation() && hasBackgroundLocation()) { context.getString(R.string.location_allow_precise_location_and_allow_all_the_time_in) }
        val engine = selectedEngine()
        checkLocationServices()
        val location = currentLocation()
        lock.withLock {
            check(engine == selectedEngine()) { context.getString(R.string.location_location_engine_changed_retry_enabling_automatic_cards) }
            settings.enabled = true
            register(location)
        }
    }

    /** A bounded foreground check; waiting for a fix must not block ENTER/EXIT delivery. */
    @SuppressLint("MissingPermission")
    suspend fun refreshNearby(force: Boolean = false): Location? = refreshLock.withLock {
        if (!settings.enabled) return@withLock null
        if (!hasPreciseLocation() || !hasBackgroundLocation()) {
            lock.withLock {
                settings.invalidateRegistration()
                settings.inside = emptySet()
                notifications.hide()
                CardWidgetProvider.updateAll(context)
            }
            error(context.getString(R.string.location_allow_precise_location_and_allow_all_the_time_in))
        }
        check(context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled) {
            context.getString(R.string.location_turn_on_device_location_to_check_nearby_stores)
        }
        AppDiagnostics.event("Location", "Nearby refresh; manual=$force")
        val engine = selectedEngine()
        val locationBackend = backends.currentLocation(engine)
        val location = if (force) {
            // A deliberate refresh means the previous fix is not trusted. Bypass
            // both our fast path and Play services' historical-location cache.
            AppDiagnostics.event(
                "Location",
                "Manual refresh bypassing cached location; requesting a new fix"
            )
            locationBackend.currentLocation(maxAgeMillis = 0)
        } else {
            val cached = locationBackend.cachedLocation()
            // Old or boundary-straddling cache entries cannot end the first arrival check.
            cached?.takeIf { cacheIsDecisive(it, settings.stores()) }
                ?: locationBackend.currentLocation(maxAgeMillis = 0)
        }
        check(usableFix(location)) { context.getString(R.string.location_waiting_for_an_accurate_location_try_again_outdoors_or) }
        lock.withLock applyFix@{
            if (!settings.enabled || engine != selectedEngine()) return@applyFix null
            check(hasPreciseLocation() && hasBackgroundLocation()) { context.getString(R.string.location_allow_precise_location_and_allow_all_the_time_in) }
            if (hasCurrentRegistration() && location.elapsedRealtimeNanos < lastStoreEventElapsedNanos) {
                AppDiagnostics.event(
                    "Location",
                    "Keeping newer store event received during foreground check"
                )
                publishPresence(onlyMissing = true)
                return@applyFix null
            }
            val anchor = settings.anchor()
            val movedRegion = anchor == null || NearbyStores.distanceMeters(
                anchor.latitude, anchor.longitude,
                location.latitude, location.longitude
            ) >= settings.regionRadius / 2
            val nearest =
                NearbyStores.nearest(settings.stores(), location.latitude, location.longitude)
            val changedSet = nearest.map { it.id }.toSet() != settings.registered
            if (!hasCurrentRegistration() || movedRegion || changedSet) {
                register(location)
            } else {
                settings.inside = reconcilePresence(
                    settings.stores().filter { it.id in settings.registered },
                    settings.inside,
                    location
                )
                // Preserve the expanded state and post time of cards already visible.
                publishPresence(onlyMissing = true)
            }
            settings.recordNearbyUpdate(if (force) "Manual nearby check" else "Opening Portemoné")
            AppDiagnostics.event("Location", "Nearby refresh completed; ${fixSummary(location)}")
            location
        }
    }

    suspend fun saveStore(cardId: String, store: StoreLocation) {
        persistCardChange(cardId) {
            if (cardId == TestCard.card.id) {
                val before = checkNotNull(settings.cards().find { it.id == cardId }) {
                    context.getString(R.string.location_this_test_card_has_been_deleted)
                }
                settings.saveTestStore(store)
                LocalCardRepository.get(context)
                    .recordTestStoreChange(before, before.copy(stores = listOf(store)))
            } else LocalCardRepository.get(context).saveStore(cardId, store)
        }
    }

    suspend fun removeStore(cardId: String, storeId: String) {
        if (cardId == TestCard.card.id) {
            require(storeId == "test")
            removeTestStore()
            return
        }
        persistCardChange(cardId) { LocalCardRepository.get(context).removeStore(cardId, storeId) }
    }

    suspend fun saveCard(card: Card, originalPackage: ByteArray? = null, expected: Card? = null) {
        persistCardChange(card.id) {
            LocalCardRepository.get(context).saveCard(card, originalPackage, expected)
        }
    }

    suspend fun deleteCard(card: Card) {
        persistCardChange(card.id) { LocalCardRepository.get(context).deleteCard(card.id) }
    }

    private suspend fun persistCardChange(cardId: String, mutation: () -> Unit) =
        withContext(Dispatchers.IO) {
            // Saving never acquires the location coordinator's lock: a slow registration or GPS
            // request must not delay committing a user's edit. The outbox commits with the card.
            mutation()
            runCatching { notifications.hide(cardId) }.onFailure {
                AppDiagnostics.event("Cards", "Saved card cancellation will retry", it)
            }
            runCatching { scheduleCardChanges() }.onFailure {
                // Application startup drains the outbox if scheduling was interrupted or failed.
                AppDiagnostics.event("Cards", "Saved card scheduling will retry on startup", it)
            }
        }

    internal suspend fun reconcileSavedCards() = lock.withLock {
        val repository = LocalCardRepository.get(context)
        val pending = repository.pendingChanges()
        if (pending.isEmpty()) return@withLock
        if (settings.enabled && !settings.hasCurrentDeviceRegistration()) {
            settings.inside = emptySet()
            notifications.hide()
            if (hasPreciseLocation() && hasBackgroundLocation()) RegionRefreshJob.schedule(context)
        }
        val changedIds = pending.flatMap { it.changedStoreIds }.toSet()
        val boundariesPending = changedIds.isNotEmpty() && !settings.hasCurrentRegistration()
        if (boundariesPending) settings.inside = settings.inside - changedIds
        pending.forEach { notifications.hide(it.cardId) }
        CardExpiration.schedule(context)
        if (settings.enabled && boundariesPending) {
            val stores = settings.stores()
            val changedStores = stores.filter { it.id in changedIds }
            val cached = if (hasPreciseLocation()) {
                try {
                    withTimeoutOrNull(1_000) {
                        backends.currentLocation(selectedEngine()).cachedLocation()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppDiagnostics.event(
                        "Location",
                        "Cached position unavailable after save",
                        error
                    )
                    null
                }
            } else null
            if (!hasCurrentRegistration()) {
                val anchor = cached?.takeIf { usableFix(it) }
                    ?: settings.anchor().takeIf {
                        selectedEngine() != LocationEngine.FRAMEWORK || settings.hasCurrentDeviceRegistration()
                    }
                checkNotNull(anchor) { context.getString(R.string.location_open_nearby_to_establish_a_monitored_region) }
                // An old anchor selects the bounded region; it is never evidence of presence.
                register(anchor, updatePresence = false)
            }
            // Provider registration can suspend; freshness is checked when applying the fix.
            if (cached != null) {
                val decisiveStores = changedStores.filter { cacheIsDecisive(cached, listOf(it)) }
                settings.inside =
                    settings.inside + reconcilePresence(decisiveStores, emptySet(), cached)
            }
        }
        notifications.syncNearby(settings.activeCards(), onlyMissing = true)
        CardWidgetProvider.updateAll(context)
        pending.forEach { repository.acknowledge(it) }
    }

    suspend fun reconcileCardValidity() = lock.withLock {
        if (settings.enabled && !hasCurrentRegistration() &&
            (!settings.hasPendingCardChanges() || !settings.hasCurrentDeviceRegistration())
        ) {
            // A saved anchor is not evidence of presence after a reboot or a boundary edit.
            settings.invalidateRegistration()
            settings.inside = emptySet()
            notifications.hide()
            if (hasPreciseLocation() && hasBackgroundLocation()) RegionRefreshJob.schedule(context)
        }
        settings.inside = settings.inside.intersect(settings.stores().map { it.id }.toSet())
        settings.cards().filter { !it.isUsable() }.forEach(notifications::hide)
        if (settings.enabled) notifications.syncNearby(settings.activeCards(), onlyMissing = true)
        CardWidgetProvider.updateAll(context)
        withContext(Dispatchers.IO) { CardExpiration.schedule(context) }
    }

    suspend fun disable() = lock.withLock {
        AppDiagnostics.event("Geofence", "Disable requested")
        // Invalidate queued broadcasts even if Play services cannot remove the fences immediately.
        settings.enabled = false
        RegionRefreshJob.cancel(context)
        settings.invalidateRegistration()
        settings.inside = emptySet()
        settings.registered = emptySet()
        notifications.hide()
        settings.status = context.getString(R.string.location_automatic_cards_are_off)
        CardWidgetProvider.updateAll(context)
        previousGeofences().removeAll()
    }

    suspend fun restore() = lock.withLock {
        AppDiagnostics.event("Geofence", "Restore requested; enabled=${settings.enabled}")
        if (!settings.enabled) return@withLock
        if (!hasPreciseLocation() || !hasBackgroundLocation()) {
            settings.invalidateRegistration()
            notifications.hide()
            settings.inside = emptySet()
            settings.status = context.getString(R.string.location_location_permission_is_missing_open_portemon_to_resume)
            CardWidgetProvider.updateAll(context)
            return@withLock
        }
        if (hasCurrentRegistration()) {
            AppDiagnostics.event("Geofence", "Keeping existing registration and nearby cards")
            return@withLock
        }
        // Never carry pre-reboot presence into a new registration. Its anchor is
        // only a region selection hint, not evidence that the user is still there.
        settings.invalidateRegistration()
        settings.inside = emptySet()
        notifications.hide()
        CardWidgetProvider.updateAll(context)
        val location = settings.anchor()
        if (selectedEngine() == LocationEngine.FRAMEWORK || location == null) {
            // Framework emits EXIT only after a recorded ENTER. Re-arming an old
            // anchor while already outside it would leave the rolling set stranded.
            settings.status = context.getString(R.string.location_resuming_store_monitoring_checking_current_location)
            RegionRefreshJob.schedule(context)
        } else {
            // Google supports INITIAL_TRIGGER_EXIT, so a saved anchor can re-arm
            // immediately and ask for region replacement when already outside.
            register(location)
        }
    }

    suspend fun addTestStore() {
        AppDiagnostics.event("Geofence", "Add test store requested")
        val location = currentLocation()
        lock.withLock {
            settings.saveTestStore(location)
            if (settings.enabled) register(location)
            else settings.status =
                context.getString(R.string.location_test_card_created_enable_automatic_cards_to_test_arrival)
        }
    }

    suspend fun removeTestStore() = lock.withLock {
        AppDiagnostics.event("Geofence", "Remove test store requested")
        val currentRegistration = hasCurrentRegistration()
        val requestId = settings.generation.takeIf { it.isNotBlank() }?.let { "$it:test" }
        settings.removeTestStore()
        settings.registered = settings.registered - "test"
        // Delete locally even if Play services cannot remove the fence right now. Queued
        // old ENTER events cannot match a card whose test location no longer exists.
        notifications.hide(TestCard.card)
        CardWidgetProvider.updateAll(context)
        settings.status = context.getString(R.string.location_test_card_deleted)
        // Removing one disposable fence must not clear other stores' presence or
        // repost a loyalty card the user is already scanning.
        try {
            if (requestId != null) previousGeofences().remove(requestId)
            if (currentRegistration) settings.markRegistered()
        } catch (error: Exception) {
            settings.invalidateRegistration()
            AppDiagnostics.event(
                "Geofence",
                "Test card deleted; fence cleanup needs a refresh",
                error
            )
            throw error
        }
    }

    suspend fun changePresentation(
        type: BarcodeType,
        style: CardNotificationStyle,
        collapsedPreview: Boolean
    ) = lock.withLock {
        if (settings.barcodeType == type && settings.notificationStyle == style &&
            settings.collapsedPreview == collapsedPreview
        ) return@withLock
        settings.barcodeType = type
        settings.notificationStyle = style
        settings.collapsedPreview = collapsedPreview
        if (settings.enabled) notifications.syncNearby(settings.activeCards())
        CardWidgetProvider.updateAll(context)
    }

    internal suspend fun onEvent(event: StoreEvent) = lock.withLock {
        AppDiagnostics.event(
            "Geofence",
            "Processing ${transitionName(event.transition)}; enabled=${settings.enabled}, error=${event.error != null}, receipt=${
                settings.generation.take(8)
            }, insideBefore=${settings.inside.sorted()}, ${fixSummary(event.location)}"
        )
        if (!settings.enabled || event.engine != settings.registeredEngine || event.engine != selectedEngine()) {
            AppDiagnostics.event(
                "Geofence",
                "Ignored event: automatic cards are off or engine changed"
            )
            return@withLock
        }
        if (event.error != null) {
            notifications.hide()
            settings.inside = emptySet()
            settings.registered = emptySet()
            settings.invalidateRegistration()
            settings.status = requireNotNull(event.error)
            AppDiagnostics.event(
                "Geofence",
                "Event error cleared nearby cards and invalidated registration"
            )
            CardWidgetProvider.updateAll(context)
            if (hasPreciseLocation() && hasBackgroundLocation()) RegionRefreshJob.schedule(context)
            return@withLock
        }
        val deliveredIds = event.requestIds
        val ids = deliveredIds
            .filter { settings.generation.isNotBlank() && it.startsWith("${settings.generation}:") }
        AppDiagnostics.event(
            "Geofence",
            "Event receipt match: accepted=${ids.map { it.substringAfter(':') }}, rejected=${deliveredIds.size - ids.size}"
        )
        if (ids.isEmpty()) {
            AppDiagnostics.event("Geofence", "Ignored stale or unrelated event")
            return@withLock
        }
        val transition = event.transition
        if (transition != StoreTransition.ENTER && transition != StoreTransition.EXIT) {
            AppDiagnostics.event(
                "Geofence",
                "Ignored unsupported transition: ${transitionName(transition)}"
            )
            return@withLock
        }
        val changedIds = if (settings.hasCurrentRegistration()) emptySet() else
            LocalCardRepository.get(context).pendingChanges().flatMap { it.changedStoreIds }.toSet()
        val stores = ids.map { it.substringAfter(':') }
            .filter { it in settings.registered && it !in changedIds }.toSet()
        val regionExit =
            transition == StoreTransition.EXIT && "${settings.generation}:region" in ids
        if (stores.isEmpty() && !regionExit) {
            AppDiagnostics.event("Geofence", "Ignored event: no currently registered store IDs")
            return@withLock
        }
        val location = event.location
        val boundaries = settings.stores().filter { it.id in stores }.map { store ->
            val distance =
                location?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() }?.let {
                    NearbyStores.distanceMeters(
                        it.latitude,
                        it.longitude,
                        store.latitude,
                        store.longitude
                    ).toInt()
                }
            "${store.id}:radius=${store.radiusMeters}m,distance=${distance?.let { "${it}m" } ?: "unknown"}"
        }
        // The selected engine owns ENTER/EXIT decisions; the triggering fix explains timing
        // without imposing a second radius rule that could suppress valid transitions.
        AppDiagnostics.event("Geofence", "${transitionName(transition)} boundaries=$boundaries")
        lastStoreEventElapsedNanos = location?.elapsedRealtimeNanos?.takeIf { it > 0 }
            ?: SystemClock.elapsedRealtimeNanos()
        settings.recordNearbyUpdate("Store ${transitionName(transition)}")
        val inside =
            NearbyStores.transition(settings.inside, stores, transition == StoreTransition.ENTER)
        settings.inside = inside
        AppDiagnostics.event(
            "Geofence",
            "Applied ${transitionName(transition)}; insideAfter=${inside.sorted()}"
        )
        // Repeated ENTER also recovers a missing notification; cancellation always
        // precedes region setup, which can fail or exceed the receiver deadline.
        publishPresence(onlyMissing = true)
        if (regionExit) {
            AppDiagnostics.event("Geofence", "Region exit; replacing nearest stores")
            // Framework alerts carry no fix. Request one only on region departure,
            // rather than keeping a GPS subscription alive between transitions.
            settings.invalidateRegistration()
            if (location != null) register(location)
            else {
                settings.status = context.getString(R.string.location_moving_to_a_new_region_updating_monitored_stores)
                RegionRefreshJob.schedule(context)
            }
        }
    }

    private fun publishPresence(onlyMissing: Boolean = false) {
        val activeCards = settings.activeCards()
        val posted = notifications.syncNearby(activeCards, onlyMissing)
        AppDiagnostics.event(
            "Geofence",
            "Notification outcome: nearbyCards=${activeCards.map { it.id }}, postingAllowed=$posted, onlyMissing=$onlyMissing, inside=${settings.inside.sorted()}"
        )
        if (activeCards.isEmpty()) {
            settings.status = context.resources.getQuantityString(R.plurals.monitoring_locations, settings.registered.size, settings.registered.size)
        } else {
            settings.status =
                if (posted) context.getString(R.string.location_nearby_cards_shown_if_allowed, activeCards.joinToString { it.brandName })
                else context.getString(R.string.location_at_a_store_but_notifications_are_disabled)
        }
        CardWidgetProvider.updateAll(context)
    }

    @SuppressLint("MissingPermission")
    private suspend fun register(location: Location, updatePresence: Boolean = true) {
        check(hasPreciseLocation() && hasBackgroundLocation()) { context.getString(R.string.location_location_permissions_are_required) }
        checkLocationServices()
        val registrationStores = settings.stores()
        val nearest =
            NearbyStores.nearest(registrationStores, location.latitude, location.longitude)
        AppDiagnostics.event(
            "Geofence",
            "Registering ${nearest.size} stores plus region exit; engine=${selectedEngine()}, radii=${nearest.map { "${it.id}:${it.radiusMeters}m" }}, ${
                fixSummary(location)
            }"
        )
        // Replace the previous batch rather than accumulating registrations toward Android's limit.
        settings.invalidateRegistration()
        val generation = UUID.randomUUID().toString()
        val backend = geofences()
        try {
            previousGeofences().removeAll()
            settings.registeredEngine = backend.engine
            settings.generation = generation
            settings.registered = nearest.map { it.id }.toSet()
            val radius = NearbyStores.regionRadius(
                registrationStores,
                nearest,
                location.latitude,
                location.longitude
            )
            backend.register(nearest, location, generation, radius)
            settings.saveRegionRadius(radius)
            settings.saveAnchor(location)
            settings.markRegistered(registrationStores)
            if (updatePresence) settings.inside =
                reconcilePresence(nearest, settings.inside, location)
            AppDiagnostics.event(
                "Geofence",
                "Registration ready; receipt=${generation.take(8)}, fresh-fix matches=${settings.inside.sorted()}, ENTER/EXIT delivery armed"
            )
            publishPresence(onlyMissing = true)
            if (updatePresence && usableFix(location)) settings.recordNearbyUpdate("Location registration check")
            if (settings.inside.isEmpty()) settings.status =
                context.resources.getQuantityString(R.plurals.monitoring_stores, nearest.size, nearest.size)
        } catch (error: Exception) {
            settings.invalidateRegistration()
            settings.inside = emptySet()
            notifications.hide()
            CardWidgetProvider.updateAll(context)
            AppDiagnostics.event("Geofence", "Registration failed", error)
            throw error
        }
    }

    companion object {
        // Keep the historical Google delivery identity when upgrading existing installs.
        internal fun deliveryIntent(context: Context, create: Boolean): PendingIntent? =
            PlayServicesGeofenceBackend.deliveryIntent(context, create)

        private val lock = Mutex()
        private val refreshLock = Mutex()
        private var lastStoreEventElapsedNanos = 0L

        private fun cacheIsDecisive(location: Location, stores: List<StoreLocation>): Boolean =
            usableFix(location) && location.accuracy <= 100f &&
                    (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000 <= 5_000 &&
                    stores.none { store ->
                        val distance = NearbyStores.distanceMeters(
                            location.latitude,
                            location.longitude,
                            store.latitude,
                            store.longitude
                        )
                        kotlin.math.abs(distance - store.radiusMeters) < location.accuracy
                    }

        internal fun transitionName(transition: StoreTransition?): String =
            transition?.name ?: "UNKNOWN"

        internal fun fixSummary(location: Location?): String {
            if (location == null) return "triggerFix=absent"
            val accuracy =
                if (location.hasAccuracy() && location.accuracy.isFinite()) "${location.accuracy.toInt()}m" else "unknown"
            val age = if (location.elapsedRealtimeNanos > 0)
                "${(SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000}ms" else "unknown"
            return "fixAccuracy=$accuracy, fixAge=$age"
        }

        private fun usableFix(location: Location): Boolean = location.hasAccuracy() &&
                location.accuracy.isFinite() && location.accuracy in 0f..1_000f &&
                location.latitude.isFinite() && location.longitude.isFinite() &&
                location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0 &&
                location.elapsedRealtimeNanos > 0 &&
                (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000 in 0..30_000

        internal fun reconcilePresence(
            stores: List<StoreLocation>,
            previous: Set<String>,
            location: Location
        ): Set<String> {
            if (!usableFix(location)) return previous.intersect(stores.map { it.id }.toSet())
            return NearbyStores.reconcile(
                stores, previous, location.latitude, location.longitude, location.accuracy,
                (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
            )
        }

        fun settingsResolution(error: Exception) =
            PlayServicesGeofenceBackend.settingsResolution(error)

        fun errorMessage(error: Exception, resources: android.content.res.Resources): String =
            PlayServicesGeofenceBackend.errorMessage(error, resources)
                ?: if (error is UserFacingException) error.userMessage(resources, R.string.location_location_setup_failed_try_again)
                else error.message ?: resources.getString(R.string.location_location_setup_failed_try_again)
    }
}
