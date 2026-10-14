// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.notifications.NotificationPreferences

import dev.finnes.portemone.cards.CardWriter

import android.Manifest
import android.content.Context
import android.location.Location
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.finnes.portemone.location.*
import dev.finnes.portemone.notifications.integrations.NotificationPresentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real persistence and coordinator, with location providers controlled at their platform boundary. */
class SavedCardReconciliationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = LocalCardRepository.get(context)
    private val settings = LocationSettings(context)
    private val preferences = context.getSharedPreferences("local_geofences", Context.MODE_PRIVATE)
    private var savedPreferences: Map<String, *> = emptyMap<String, Any>()
    private val backend = ControlledLocationBackend()
    private var scheduled = 0
    private val coordinator = GeofenceCoordinator(context, backend)
    private val writer = CardWriter(context) { scheduled++ }
    private val card = TestMemberships.cards.first().copy(
        id = "save-reconciliation-card",
        stores = listOf(StoreLocation("save-reconciliation-store", "Store", 55.0, 12.0, 150f))
    )
    private val other = card.copy(
        id = "save-reconciliation-other",
        stores = listOf(card.stores.single().copy(id = "save-reconciliation-other-store"))
    )

    @Before
    fun prepare() {
        savedPreferences = preferences.all.toMap()
        settings.enabled = false
        WorkManager.getInstance(context).cancelUniqueWork("saved-card-changes").result.get()
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        repository.saveCard(card)
        repository.saveCard(other)
        repository.pendingChanges().forEach { repository.acknowledge(it) }
        settings.locationEngine = LocationEngine.FRAMEWORK
        settings.registeredEngine = LocationEngine.FRAMEWORK
        NotificationPreferences(context).notificationPresentation = NotificationPresentation.STANDARD
        settings.saveAnchor(fix())
        settings.enabled = true
        settings.generation = "save-test"
        settings.registered = settings.stores().map { it.id }.toSet()
        settings.inside = setOf(card.stores.single().id, other.stores.single().id)
        settings.markRegistered()
    }

    @After
    fun restore() {
        backend.release.complete(Unit)
        repository.deleteCard(card.id)
        repository.deleteCard(other.id)
        repository.pendingChanges().filter {
            it.cardId == card.id || it.cardId == other.id || it.cardId == TestCard.card.id
        }
            .forEach { repository.acknowledge(it) }
        CardNotificationManager(context).hide(card)
        CardNotificationManager(context).hide(other)
        preferences.edit().clear().apply {
            savedPreferences.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }.commit()
    }

    @Test
    fun pendingBoundaryEditCannotRearmFrameworkWithAPreRebootAnchor() {
        instrumentation.runOnMainSync {
            runBlocking {
                writer.saveStore(card.id, card.stores.single().copy(radiusMeters = 250f))
                preferences.edit().putString("registration_environment", "obsolete:receipt")
                    .putString("registration_device", "obsolete:receipt")
                    .commit()
                try {
                    coordinator.reconcileCardValidity()
                    assertTrue(settings.inside.isEmpty())
                    val result = runCatching { coordinator.reconcileSavedCards() }
                    assertTrue(result.exceptionOrNull() is IllegalStateException)
                    assertEquals(0, backend.registrations)
                    assertFalse(repository.pendingChanges().isEmpty())
                } finally {
                    RegionRefreshJob.cancel(context)
                }
            }
        }
    }

    @Test
    fun metadataSaveAndItsReconciliationNeverConsultLocation() = runBlocking {
        val updated = card.copy(cardName = "Renamed", rawValue = "001234")
        writer.saveCard(updated, expected = card)
        assertEquals(updated, repository.cards().first { it.id == card.id })
        assertEquals(1, scheduled)
        assertEquals(0, backend.cachedRequests)
        coordinator.reconcileSavedCards()
        assertEquals(0, backend.cachedRequests)
        assertEquals(0, backend.registrations)
        assertTrue(repository.pendingChanges().isEmpty())
        assertTrue(
            settings.inside.containsAll(
                listOf(
                    card.stores.single().id,
                    other.stores.single().id
                )
            )
        )
    }

    @Test
    fun boundarySaveCompletesWhileAnOlderRegistrationIsSuspended() = runBlocking {
        val resized = card.copy(stores = listOf(card.stores.single().copy(radiusMeters = 250f)))
        writer.saveCard(resized, expected = card)
        assertEquals(0, backend.cachedRequests)
        assertEquals(0, backend.registrations)
        backend.blockRegistration = true
        coroutineScope {
            val running = async { coordinator.reconcileSavedCards() }
            withTimeout(5_000) { backend.started.await() }
            val latest =
                resized.copy(stores = listOf(resized.stores.single().copy(radiusMeters = 350f)))
            try {
                withTimeout(2_000) { writer.saveCard(latest, expected = resized) }
                assertEquals(latest, repository.cards().first { it.id == card.id })
            } finally {
                backend.release.complete(Unit)
            }
            running.await()
            assertFalse(settings.hasCurrentRegistration())
            assertEquals(card.id, repository.pendingChanges().single().cardId)
            coordinator.reconcileSavedCards()
            assertTrue(settings.hasCurrentRegistration())
            assertTrue(repository.pendingChanges().isEmpty())
            assertEquals(
                350f,
                backend.stores.first { it.id == card.stores.single().id }.radiusMeters
            )
        }
    }

    @Test
    fun missingCachedFixClearsOnlyChangedCircleAndUsesAnchorOnlyForRegistration() = runBlocking {
        writer.saveStore(card.id, card.stores.single().copy(radiusMeters = 250f))
        coordinator.reconcileSavedCards()
        assertEquals(1, backend.cachedRequests)
        assertEquals(1, backend.registrations)
        assertFalse(card.stores.single().id in settings.inside)
        assertTrue(other.stores.single().id in settings.inside)
        assertTrue(repository.pendingChanges().isEmpty())
    }

    @Test
    fun recentAccurateCachedFixChecksOnlyEditedCard() = runBlocking {
        settings.inside = emptySet()
        backend.cached = fix()
        writer.saveStore(card.id, card.stores.single().copy(radiusMeters = 250f))
        coordinator.reconcileSavedCards()
        assertEquals(setOf(card.stores.single().id), settings.inside)
        assertEquals(1, backend.cachedRequests)
    }

    @Test
    fun oldInvalidAndUncertainCachedFixesCannotEstablishPresence() = runBlocking {
        val fixes = listOf(
            fix(ageMillis = 31_000), fix(ageMillis = -10_000),
            fix().apply { accuracy = 1_000f }, fix().apply { removeAccuracy() },
            fix().apply { latitude = Double.NaN }, fix().apply { elapsedRealtimeNanos = 0 }
        )
        fixes.forEachIndexed { index, cached ->
            backend.cached = cached
            settings.inside = setOf(card.stores.single().id, other.stores.single().id)
            writer.saveStore(card.id, card.stores.single().copy(radiusMeters = 250f + index))
            coordinator.reconcileSavedCards()
            assertFalse(
                "Invalid fix must not establish presence",
                card.stores.single().id in settings.inside
            )
            assertTrue(other.stores.single().id in settings.inside)
        }
    }

    @Test
    fun disabledAutomaticCardsDoNotUseLocationOrRegister() = runBlocking {
        settings.enabled = false
        writer.saveStore(card.id, card.stores.single().copy(radiusMeters = 250f))
        coordinator.reconcileSavedCards()
        assertEquals(0, backend.cachedRequests)
        assertEquals(0, backend.registrations)
        assertTrue(repository.pendingChanges().isEmpty())
        assertTrue(settings.activeCards().isEmpty())
    }

    @Test
    fun registrationFailureLeavesDurableWorkAndRetryUsesLatestData() = runBlocking {
        backend.failRegistration = true
        writer.saveStore(card.id, card.stores.single().copy(radiusMeters = 250f))
        try {
            coordinator.reconcileSavedCards()
            fail("Expected the controlled registration failure")
        } catch (_: IllegalStateException) {
            assertEquals(card.id, repository.pendingChanges().single().cardId)
        }
        backend.failRegistration = false
        writer.saveStore(card.id, card.stores.single().copy(radiusMeters = 350f))
        coordinator.reconcileSavedCards()
        assertEquals(350f, backend.stores.first { it.id == card.stores.single().id }.radiusMeters)
        assertTrue(repository.pendingChanges().isEmpty())
    }

    @Test
    fun homeStaysBrowsableWhileSavedBoundariesArePending() = runBlocking {
        writer.saveStore(card.id, card.stores.single().copy(radiusMeters = 250f))
        val viewModels = androidx.lifecycle.ViewModelStore()
        lateinit var home: WalletHomeModel
        instrumentation.runOnMainSync {
            home = androidx.lifecycle.ViewModelProvider(
                viewModels,
                androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
                    context.applicationContext as android.app.Application
                )
            )[WalletHomeModel::class.java]
        }
        try {
            withTimeout(5_000) {
                while (home.state.value.restoring) kotlinx.coroutines.delay(10)
            }
            instrumentation.runOnMainSync {
                home.onForeground()
                home.reconcileEdits()
                assertFalse(home.state.value.loading)
            }
            assertFalse(home.state.value.entries.first { it.card.id == card.id }.nearby)
            assertTrue(home.state.value.entries.first { it.card.id == other.id }.nearby)
        } finally {
            instrumentation.runOnMainSync { viewModels.clear() }
        }
    }

    @Test
    fun uncertainCircleDoesNotPreventAnotherEditedCircleFromEstablishingPresence() = runBlocking {
        settings.inside = emptySet()
        backend.cached = fix()
        writer.saveStore(card.id, card.stores.single().copy(radiusMeters = 250f))
        writer.saveStore(
            other.id,
            other.stores.single().copy(latitude = 55.00225, radiusMeters = 250f)
        )
        coordinator.reconcileSavedCards()
        assertEquals(setOf(card.stores.single().id), settings.inside)
        assertEquals(1, backend.cachedRequests)
    }

    @Test
    fun fixThatAgesDuringRegistrationCannotEstablishPresence() = runBlocking {
        backend.cached = fix()
        backend.onRegistered = {
            backend.cached!!.elapsedRealtimeNanos =
                SystemClock.elapsedRealtimeNanos() - 31_000_000_000L
        }
        writer.saveStore(card.id, card.stores.single().copy(radiusMeters = 250f))
        coordinator.reconcileSavedCards()
        assertFalse(card.stores.single().id in settings.inside)
        assertTrue(other.stores.single().id in settings.inside)
    }

    @Test
    fun staleEnterCannotReactivateEditedCircleButNewEnterAndExitStillWork() = runBlocking {
        writer.saveStore(card.id, card.stores.single().copy(radiusMeters = 250f))
        settings.inside = setOf(other.stores.single().id)
        coordinator.onEvent(
            StoreEvent(
                LocationEngine.FRAMEWORK,
                listOf("save-test:${card.stores.single().id}"), StoreTransition.ENTER
            )
        )
        assertEquals(setOf(other.stores.single().id), settings.inside)
        coordinator.reconcileSavedCards()
        val requests = listOf("${settings.generation}:${card.stores.single().id}")
        coordinator.onEvent(StoreEvent(LocationEngine.FRAMEWORK, requests, StoreTransition.ENTER))
        assertTrue(settings.activeCards().any { it.id == card.id })
        coordinator.onEvent(StoreEvent(LocationEngine.FRAMEWORK, requests, StoreTransition.EXIT))
        assertFalse(settings.activeCards().any { it.id == card.id })
        assertTrue(settings.activeCards().any { it.id == other.id })
    }

    @Test
    fun realBackgroundWorkerDrainsCommittedChanges() = runBlocking {
        settings.enabled = false
        CardWriter(context).saveCard(
            card.copy(cardName = "Background update"),
            expected = card
        )
        withTimeout(10_000) {
            while (repository.pendingChanges().isNotEmpty()) kotlinx.coroutines.delay(20)
        }
        assertEquals("Background update", repository.cards().first { it.id == card.id }.cardName)
    }

    @Test
    fun deletionQueuesCleanupWithoutRemovingAnotherCardsPresence() = runBlocking {
        writer.deleteCard(card)
        assertFalse(repository.cards().any { it.id == card.id })
        assertEquals(1, scheduled)
        assertEquals(0, backend.cachedRequests)
        assertEquals(0, backend.freshRequests)
        val pending = repository.pendingChanges().single()
        assertEquals(card.id, pending.cardId)
        assertEquals(setOf(card.stores.single().id), pending.changedStoreIds)

        coordinator.reconcileSavedCards()
        assertFalse(card.stores.single().id in settings.inside)
        assertTrue(other.stores.single().id in settings.inside)
        assertTrue(repository.pendingChanges().isEmpty())
        assertFalse(backend.stores.any { it.id == card.stores.single().id })
    }

    @Test
    fun testStoreEditPersistsAndReconcilesWithoutRequestingGps() = runBlocking {
        val original = StoreLocation("test", "Temporary store", 55.0, 12.0, 150f)
        settings.saveTestStore(original)
        settings.registered = settings.registered + "test"
        settings.inside = settings.inside + "test"
        settings.markRegistered()
        val resized = original.copy(radiusMeters = 300f)

        writer.saveStore(TestCard.card.id, resized)
        assertEquals(resized, settings.cards().single { it.id == TestCard.card.id }.stores.single())
        assertFalse(repository.cards().any { it.id == TestCard.card.id })
        assertEquals(1, scheduled)
        assertEquals(0, backend.cachedRequests)
        val pending = repository.pendingChanges().single()
        assertEquals(TestCard.card.id, pending.cardId)
        assertEquals(setOf("test"), pending.changedStoreIds)

        coordinator.reconcileSavedCards()
        assertEquals(resized, backend.stores.single { it.id == "test" })
        assertFalse("test" in settings.inside)
        assertTrue(other.stores.single().id in settings.inside)
        assertEquals(0, backend.freshRequests)
        assertTrue(repository.pendingChanges().isEmpty())
    }

    @Test
    fun explicitRefreshStillRequestsAFreshFix() = runBlocking {
        backend.fresh = fix()
        coordinator.refreshNearby(force = true)
        assertEquals(1, backend.freshRequests)
        assertEquals(0L, backend.requestedMaxAge)
        assertEquals(0, backend.cachedRequests)
    }

    @Test
    fun cacheFailureFallsBackToUnknownPresenceWithoutRequestingGps() = runBlocking {
        backend.failCache = true
        writer.saveStore(card.id, card.stores.single().copy(radiusMeters = 250f))
        coordinator.reconcileSavedCards()
        assertFalse(card.stores.single().id in settings.inside)
        assertTrue(other.stores.single().id in settings.inside)
        assertEquals(1, backend.registrations)
        assertEquals(0, backend.freshRequests)
    }

    @Test
    fun schedulerFailureDoesNotTurnCommittedSaveIntoFailure() = runBlocking {
        val failingScheduler =
            CardWriter(context) { error("Scheduler unavailable") }
        failingScheduler.saveCard(card.copy(cardName = "Saved anyway"), expected = card)
        assertEquals("Saved anyway", repository.cards().first { it.id == card.id }.cardName)
        assertEquals(card.id, repository.pendingChanges().single().cardId)
        assertEquals(0, backend.cachedRequests)
    }

    private fun fix(ageMillis: Long = 0) = Location("controlled").apply {
        latitude = 55.0
        longitude = 12.0
        accuracy = 10f
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMillis * 1_000_000
    }
}

private class ControlledLocationBackend : LocationBackends, CurrentLocationBackend,
    GeofenceBackend {
    var cached: Location? = null
    var fresh: Location? = null
    var freshRequests = 0
    var requestedMaxAge: Long? = null
    var failCache = false
    var onRegistered: () -> Unit = {}
    var cachedRequests = 0
    var registrations = 0
    var stores = emptyList<StoreLocation>()
    var blockRegistration = false
    var failRegistration = false
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    override val engine = LocationEngine.FRAMEWORK
    override fun resolve(preference: LocationEngine) = engine
    override fun geofences(engine: LocationEngine): GeofenceBackend = this
    override fun currentLocation(engine: LocationEngine): CurrentLocationBackend = this
    override suspend fun cachedLocation(): Location? {
        cachedRequests++
        check(!failCache) { "Controlled cache failure" }
        return cached
    }

    override suspend fun currentLocation(maxAgeMillis: Long): Location {
        freshRequests++
        requestedMaxAge = maxAgeMillis
        return fresh ?: error("Saving must never request a fresh fix")
    }

    override suspend fun checkAvailability() {}
    override suspend fun register(
        stores: List<StoreLocation>,
        anchor: Location,
        generation: String,
        regionRadius: Float
    ) {
        registrations++
        this.stores = stores
        started.complete(Unit)
        if (blockRegistration) release.await()
        check(!failRegistration) { "Controlled registration failure" }
        onRegistered()
    }

    override suspend fun removeAll() {}
    override suspend fun remove(requestId: String) {}
    override fun hasDelivery() = true
}
