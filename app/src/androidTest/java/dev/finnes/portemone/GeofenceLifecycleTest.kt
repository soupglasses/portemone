// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.app.NotificationManager
import android.content.Context
import android.location.Location
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a test emulator with location and notification permissions granted. */
@RunWith(AndroidJUnit4::class)
class GeofenceLifecycleTest {
    @org.junit.Before
    fun establishMembershipFixtures() {
        val repository =
            LocalCardRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val ids = repository.cards().map { it.id }.toSet()
        TestMemberships.cards.filter { it.id !in ids }.forEach { repository.saveCard(it) }
        // This scenario starts with a settled wallet, including tombstones from prior tests.
        repository.pendingChanges().forEach { repository.acknowledge(it) }
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reopeningWalletKeepsNearbyCardAndRegistration() = withSavedSettings {
        // Establish a saved home view through the Activity before enabling the
        // fixture receipt; reopening should restore it without requesting GPS.
        prepareHomeSnapshot()
        val settings = prepareRegistration()
        val card = TestMemberships.cards.first()
        val notifications = CardNotificationManager(context)
        assertTrue(notifications.showSaved(card))
        val manager = context.getSystemService(NotificationManager::class.java)
        await { manager.activeNotifications.any { it.tag == card.id } }
        val postTime = manager.activeNotifications.single { it.tag == card.id }.postTime
        repeat(2) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.recreate()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals("test-registration", LocationSettings(context).generation)
                assertEquals(setOf("fixture-one-store"), LocationSettings(context).inside)
                assertTrue(LocationSettings(context).hasCurrentRegistration())
                assertEquals(
                    postTime,
                    manager.activeNotifications.single { it.tag == card.id }.postTime
                )
            }
        }
        // A new manager uses persisted state rather than an Activity-local flag.
        runBlocking { GeofenceCoordinator(context).restore() }
        assertEquals("test-registration", settings.generation)
        assertEquals(setOf("fixture-one-store"), settings.inside)
    }

    @Test
    fun homeTracksStorePresenceWithoutItsOwnRefresh() = withSavedSettings {
        prepareHomeSnapshot()
        val settings = prepareRegistration()
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var home: WalletHomeModel
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            home = androidx.lifecycle.ViewModelProvider(
                store,
                androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
                    context.applicationContext as android.app.Application
                )
            )[WalletHomeModel::class.java]
        }
        try {
            await { !home.state.value.restoring }
            await { home.state.value.entries.any { it.card.id == "fixture-one" && it.nearby } }
            settings.inside = setOf("fixture-two-store")
            await {
                val entries = home.state.value.entries
                entries.any { it.card.id == "fixture-two" && it.nearby } &&
                        entries.none { it.card.id == "fixture-one" && it.nearby }
            }
            settings.inside = emptySet()
            await { home.state.value.entries.none { it.nearby } }
            assertFalse(home.state.value.loading)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test
    fun frameworkStartupDropsPreRebootPresenceAndSchedulesFreshRecovery() = withSavedSettings {
        val settings = prepareRegistration()
        settings.locationEngine = dev.finnes.portemone.location.LocationEngine.FRAMEWORK
        settings.registeredEngine = dev.finnes.portemone.location.LocationEngine.FRAMEWORK
        val notifications = CardNotificationManager(context)
        val card = TestMemberships.cards.first()
        assertTrue(notifications.showSaved(card))
        val system = context.getSystemService(NotificationManager::class.java)
        await { system.activeNotifications.any { it.tag == card.id } }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                val coordinator = GeofenceCoordinator(context)
                runBlocking {
                    coordinator.restore()
                    coordinator.reconcileCardValidity()
                }
                assertTrue(settings.inside.isEmpty())
                assertTrue(settings.generation.isEmpty())
                assertFalse(coordinator.hasCurrentRegistration())
                assertRecoveryScheduled()
            } finally {
                // Inspect the queued job before the main looper can start location acquisition.
                RegionRefreshJob.cancel(context)
            }
        }
        await { system.activeNotifications.none { it.tag == card.id } }
    }

    @Test
    fun validityReconciliationDoesNotRearmAnObsoleteAnchor() = withSavedSettings {
        val settings = prepareRegistration()
        settings.invalidateRegistration()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                runBlocking { GeofenceCoordinator(context).reconcileCardValidity() }
                assertTrue(settings.inside.isEmpty())
                assertTrue(settings.generation.isEmpty())
                assertRecoveryScheduled()
            } finally {
                RegionRefreshJob.cancel(context)
            }
        }
    }

    @Test
    fun providerErrorClearsPresenceAndSchedulesRecoveryWithoutAnActivity() = withSavedSettings {
        val settings = prepareRegistration()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                runBlocking {
                    GeofenceCoordinator(context).onEvent(
                        dev.finnes.portemone.location.StoreEvent(
                            dev.finnes.portemone.location.LocationEngine.PLAY_SERVICES,
                            emptyList(), null, error = "Geofencing is unavailable"
                        )
                    )
                }
                assertTrue(settings.enabled)
                assertTrue(settings.inside.isEmpty())
                assertTrue(settings.registered.isEmpty())
                assertRecoveryScheduled()
            } finally {
                RegionRefreshJob.cancel(context)
            }
        }
    }

    private fun assertRecoveryScheduled() {
        val jobs = context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs
        assertTrue(jobs.any { it.service.className == RegionRefreshJob::class.java.name })
    }

    @Test
    fun obsoleteReceiptsAreNotReused() = withSavedSettings {
        val settings = prepareRegistration()
        assertTrue(LocationSettings(context).hasCurrentRegistration())
        val preferences = context.getSharedPreferences("local_geofences", Context.MODE_PRIVATE)
        // Model an older installation or boot without changing device-wide settings.
        val receipt = preferences.getString("registration_environment", null)!!
        val parts = receipt.split(':').toMutableList()
        parts[0] = (parts[0].toInt() - 1).toString()
        preferences.edit().putString("registration_environment", parts.joinToString(":")).commit()
        assertFalse(LocationSettings(context).hasCurrentRegistration())
        settings.markRegistered()
        settings.saveTestStore(Location("test").apply { latitude = 55.0; longitude = 12.0 })
        assertFalse(LocationSettings(context).hasCurrentRegistration())
        settings.markRegistered()
        assertTrue(GeofenceCoordinator(context).hasCurrentRegistration())
        GeofenceCoordinator.deliveryIntent(context, create = false)!!.cancel()
        assertFalse(GeofenceCoordinator(context).hasCurrentRegistration())
        settings.invalidateRegistration()
        assertFalse(LocationSettings(context).hasCurrentRegistration())
        assertTrue(settings.generation.isEmpty())
        assertTrue(settings.registered.isEmpty())
    }

    private fun prepareHomeSnapshot() {
        LocationSettings(context).enabled = false
        context.getSharedPreferences("wallet_home_snapshot", Context.MODE_PRIVATE).edit().clear()
            .commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            await {
                context.getSharedPreferences("wallet_home_snapshot", Context.MODE_PRIVATE)
                    .contains("snapshot")
            }
        }
    }

    private fun prepareRegistration(): LocationSettings = LocationSettings(context).apply {
        enabled = true
        locationEngine = dev.finnes.portemone.location.LocationEngine.PLAY_SERVICES
        registeredEngine = dev.finnes.portemone.location.LocationEngine.PLAY_SERVICES
        barcodeType = BarcodeType.CODE_128
        dev.finnes.portemone.notifications.NotificationPreferences(context).notificationStyle = CardNotificationStyle.CUSTOM_CARD
        dev.finnes.portemone.notifications.NotificationPreferences(context).collapsedPreview = false
        generation = "test-registration"
        registered = setOf("fixture-one-store", "fixture-two-store")
        inside = setOf("fixture-one-store")
        saveAnchor(Location("saved").apply { latitude = 55.000000; longitude = 12.000000 })
        GeofenceCoordinator.deliveryIntent(context, create = true)
        markRegistered()
        recordNearbyUpdate("Fixture location check")
    }

    private fun withSavedSettings(test: () -> Unit) {
        val preferences = context.getSharedPreferences("local_geofences", Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        val homePreferences =
            context.getSharedPreferences("wallet_home_snapshot", Context.MODE_PRIVATE)
        val originalHome = homePreferences.all.toMap()
        try {
            test()
        } finally {
            CardNotificationManager(context).hide()
            homePreferences.edit().clear().apply {
                originalHome.forEach { (key, value) -> if (value is String) putString(key, value) }
            }.commit()
            preferences.edit().clear().apply {
                original.forEach { (key, value) ->
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
    }

    private fun await(condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 5_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue(condition())
    }
}
