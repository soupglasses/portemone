// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.notifications.NotificationPreferences

import dev.finnes.portemone.notifications.integrations.NotificationPresentation

import android.app.NotificationManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import dev.finnes.portemone.location.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.TimeUnit

/** Shared observable-behavior contract, run against real provider APIs on an isolated emulator. */
@RequiresDeviceSetup("scripts/test-location-engines.sh supplies mock providers and locationEngineTests")
@RunWith(Parameterized::class)
class LocationEngineBehaviorTest(private val engine: LocationEngine) {
    @org.junit.Before
    fun establishMembershipFixtures() {
        val repository =
            LocalCardRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val ids = repository.cards().map { it.id }.toSet()
        TestMemberships.cards.filter { it.id !in ids }.forEach { repository.saveCard(it) }
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines() =
            listOf(arrayOf(LocationEngine.PLAY_SERVICES), arrayOf(LocationEngine.FRAMEWORK))
    }

    @Test
    fun backgroundArrivalAndDepartureUpdateNotificationsWithoutForegroundRefresh() = withEngine {
        move(55.0, 12.0)
        runBlocking { coordinator.enable() }
        assertTrue(settings.inside.isEmpty())
        // Repeated fixes exercise the provider's actual geofence delivery, including
        // its background throttle, rather than calling coordinator.onEvent ourselves.
        awaitMoving(55.01, 12.0, 120_000) {
            settings.inside == setOf("test") && notifications().any { it.tag == TestCard.card.id }
        }
        assertEquals("Store ENTER", settings.lastNearbyUpdateSource)
        awaitMoving(55.0, 12.0, 120_000) {
            settings.inside.isEmpty() && notifications().none { it.tag == TestCard.card.id }
        }
        assertEquals("Store EXIT", settings.lastNearbyUpdateSource)
        assertTrue(coordinator.hasCurrentRegistration())
    }

    @Test
    fun manualRefreshRecoversBothArrivalAndDepartureAndKeepsUnchangedRegistration() = withEngine {
        // Keep an Activity foreground for the one-shot request, just as a user pull does.
        ActivityScenario.launch(MainActivity::class.java).use {
            move(55.01, 12.0)
            runBlocking { coordinator.enable() }
            await { notifications().any { it.tag == TestCard.card.id } }
            val generation = settings.generation
            val postTime = notifications().single { it.tag == TestCard.card.id }.postTime
            runBlocking { coordinator.refreshNearby(force = false) }
            assertEquals(generation, settings.generation)
            assertEquals(postTime, notifications().single { it.tag == TestCard.card.id }.postTime)
            // Remove long-term monitoring first: only a manual fix can recover this.
            runBlocking { LocationBackendFactory(context).geofences(engine).removeAll() }
            move(55.0, 12.0)
            freshRefresh()
            await { settings.inside.isEmpty() && notifications().none { it.tag == TestCard.card.id } }
            assertTrue(coordinator.hasCurrentRegistration())
            runBlocking { LocationBackendFactory(context).geofences(engine).removeAll() }
            move(55.01, 12.0)
            freshRefresh()
            await { settings.inside == setOf("test") && notifications().any { it.tag == TestCard.card.id } }
            assertTrue(
                settings.lastNearbyUpdateSource in setOf(
                    "Manual nearby check",
                    "Store ENTER"
                )
            )
        }
    }

    @Test
    fun staleGenerationAndDisabledEngineCannotResurrectCards() = withEngine {
        move(55.0, 12.0)
        runBlocking { coordinator.enable() }
        val oldGeneration = settings.generation
        // Boundary edits replace the actual registrations and receipt.
        runBlocking {
            coroutineScope {
                val save = async {
                    coordinator.saveStore(
                        TestCard.card.id,
                        settings.stores().single { it.id == "test" }.copy(radiusMeters = 300f)
                    )
                }
                delay(300)
                move(55.0, 12.0)
                save.await()
            }
        }
        assertNotEquals(oldGeneration, settings.generation)
        runBlocking {
            coordinator.onEvent(
                StoreEvent(
                    engine,
                    listOf("$oldGeneration:test"),
                    StoreTransition.ENTER
                )
            )
        }
        assertTrue(settings.inside.isEmpty())
        assertTrue(notifications().none { it.tag == TestCard.card.id })
        val generation = settings.generation
        runBlocking { coordinator.disable() }
        runBlocking {
            coordinator.onEvent(
                StoreEvent(
                    engine,
                    listOf("$generation:test"),
                    StoreTransition.ENTER
                )
            )
        }
        assertTrue(settings.inside.isEmpty())
        assertTrue(notifications().none { it.tag == TestCard.card.id })
    }

    @Test
    fun switchingEngineCleansOldMonitoringAndRejectsItsEvents() = withEngine {
        ActivityScenario.launch(MainActivity::class.java).use {
            move(55.01, 12.0)
            runBlocking { coordinator.enable() }
            val oldGeneration = settings.generation
            val next =
                if (engine == LocationEngine.FRAMEWORK) LocationEngine.PLAY_SERVICES else LocationEngine.FRAMEWORK
            runBlocking {
                coroutineScope {
                    val change = async { coordinator.changeEngine(next) }
                    delay(300)
                    move(55.01, 12.0)
                    change.await()
                }
            }
            assertEquals(next, coordinator.selectedEngine())
            assertTrue(coordinator.hasCurrentRegistration())
            assertFalse(LocationBackendFactory(context).geofences(engine).hasDelivery())
            runBlocking {
                coordinator.onEvent(
                    StoreEvent(
                        engine,
                        listOf("$oldGeneration:test"),
                        StoreTransition.EXIT
                    )
                )
            }
            assertEquals(setOf("test"), settings.inside)
            assertTrue(notifications().any { it.tag == TestCard.card.id })
        }
    }

    @Test
    fun meaningfulMovementReplacesRegionWithoutPeriodicPolling() = withEngine {
        move(55.0, 12.0)
        runBlocking { coordinator.enable() }
        val original = settings.generation
        awaitMoving(55.4, 12.0, 120_000) {
            settings.generation != original && coordinator.hasCurrentRegistration() &&
                    settings.anchor()?.let { kotlin.math.abs(it.latitude - 55.4) < 0.001 } == true
        }
        assertTrue(settings.inside.isEmpty())
        assertTrue(notifications().none { it.tag == TestCard.card.id })
        assertTrue(settings.registered.size <= NearbyStores.LIMIT)
    }

    @Test
    fun disablingWithCardsVisibleRemovesNotificationsAndPreventsLaterArrival() = withEngine {
        move(55.01, 12.0)
        runBlocking { coordinator.enable() }
        await { notifications().any { it.tag == TestCard.card.id } }
        runBlocking { coordinator.disable() }
        await { notifications().none { it.tag == TestCard.card.id } }
        assertFalse(LocationBackendFactory(context).geofences(engine).hasDelivery())
        move(55.0, 12.0)
        move(55.01, 12.0)
        SystemClock.sleep(500)
        assertTrue(settings.inside.isEmpty())
        assertFalse(settings.enabled)
    }

    @Test
    fun restoringAtAnotherRegionUsesCurrentPositionInsteadOfStrandingAnOldAnchor() = withEngine {
        move(55.01, 12.0)
        runBlocking { coordinator.enable() }
        await { notifications().any { it.tag == TestCard.card.id } }
        // Model lost OS registrations after a reboot, retaining the saved anchor.
        runBlocking { LocationBackendFactory(context).geofences(engine).removeAll() }
        move(55.4, 12.0)
        runBlocking { coordinator.restore() }
        awaitMoving(55.4, 12.0, 120_000) {
            coordinator.hasCurrentRegistration() &&
                    settings.anchor()?.let { kotlin.math.abs(it.latitude - 55.4) < 0.001 } == true
        }
        assertTrue(settings.inside.isEmpty())
        assertTrue(notifications().none { it.tag == TestCard.card.id })
    }

    @Test
    fun turningAutomaticCardsOffDuringRefreshCannotRestoreNearbyCardsOrEnabledUi() = withEngine {
        move(55.01, 12.0)
        runBlocking { coordinator.enable() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var home: WalletHomeModel
            scenario.onActivity {
                home = androidx.lifecycle.ViewModelProvider(it)[WalletHomeModel::class.java]
            }
            await { !home.state.value.restoring && !home.state.value.loading && home.state.value.automaticEnabled }
            instrumentation.runOnMainSync { home.refresh() }
            if (engine == LocationEngine.PLAY_SERVICES) await { home.state.value.loading }
            runBlocking { coordinator.disable() }
            move(55.01, 12.0)
            await { !home.state.value.loading && !home.state.value.automaticEnabled }
            assertTrue(home.state.value.entries.none { it.nearby })
            assertTrue(notifications().none { it.tag == TestCard.card.id })
        }
    }

    @Test
    fun rollingPoolOverGoogleLimitKeepsOneMembershipVisibleUntilItsLastMatchingExit() = withEngine {
        val repository = LocalCardRepository.get(context)
        val membership = repository.cards().first { it.id == "fixture-one" }
        val first = (1..99).map {
            StoreLocation(
                "contract-a-$it",
                "First region",
                55.01 + it / 100_000.0,
                12.0,
                200f
            )
        }
        val second = (1..99).map {
            StoreLocation(
                "contract-b-$it",
                "Second region",
                55.4 + it / 100_000.0,
                12.0,
                200f
            )
        }
        val additionalCards = (first.drop(5) + second.drop(5)).chunked(MAX_CARD_LOCATIONS)
            .mapIndexed { index, stores ->
                membership.copy(
                    id = "contract-pool-$index",
                    rawValue = "1234567890",
                    source = null,
                    stores = stores
                )
            }
        try {
            repository.saveCard(
                membership.copy(stores = first.take(5) + second.take(5)),
                expected = membership
            )
            additionalCards.forEach { repository.saveCard(it) }
            move(55.01, 12.0)
            runBlocking { coordinator.enable() }
            assertTrue(settings.stores().size > 100)
            assertEquals(NearbyStores.LIMIT, settings.registered.size)
            assertTrue("test" in settings.inside)
            assertEquals(1, notifications().count { it.tag == membership.id })
            val initialGeneration = settings.generation
            // Leave the synthetic card and some overlapping circles, while remaining
            // within others belonging to the same membership.
            awaitMoving(55.0125, 12.0, 120_000) {
                // Complete this region replacement before beginning another trip.
                coordinator.hasCurrentRegistration() && settings.generation != initialGeneration &&
                        "test" !in settings.inside && settings.inside.any { it.startsWith("contract-a-") } &&
                        notifications().count { it.tag == membership.id } == 1
            }
            val original = settings.generation
            awaitMoving(55.4, 12.0, 120_000) {
                settings.generation != original && coordinator.hasCurrentRegistration() &&
                        settings.registered == second.map { it.id }.toSet() &&
                        settings.inside.containsAll(second.map { it.id }) &&
                        notifications().count { it.tag == membership.id } == 1
            }
            assertEquals(NearbyStores.LIMIT, settings.registered.size)
            // Still within the same monitored region, but outside every store circle.
            awaitMoving(55.41, 12.0, 120_000) {
                settings.inside.isEmpty() && notifications().none { it.tag == membership.id }
            }
        } finally {
            additionalCards.forEach { repository.deleteCard(it.id) }
            repository.saveCard(membership)
        }
    }

    @Test
    fun settingsExposeAndPersistExplicitAndAutomaticEngineChoices() = withEngine {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            compose.onNodeWithTag("location_engine_${engine.name}").performScrollTo()
                .assertIsSelected()
            val next =
                if (engine == LocationEngine.FRAMEWORK) LocationEngine.PLAY_SERVICES else LocationEngine.FRAMEWORK
            compose.onNodeWithTag("location_engine_${next.name}").performScrollTo().performClick()
            compose.waitUntil(10_000) { settings.locationEngine == next }
            assertEquals(next, coordinator.selectedEngine())
            compose.onNodeWithTag("location_engine_AUTOMATIC").performScrollTo().performClick()
            compose.waitUntil(10_000) { settings.locationEngine == LocationEngine.AUTOMATIC }
            assertEquals(LocationEngine.PLAY_SERVICES, coordinator.selectedEngine())
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings = LocationSettings(context)
    private val coordinator = GeofenceCoordinator(context)
    private val system = context.getSystemService(NotificationManager::class.java)
    private val framework = context.getSystemService(LocationManager::class.java)
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    private fun notifications() =
        system.activeNotifications.filter { it.id == CardNotificationManager.ID }

    private fun withEngine(test: () -> Unit) {
        assumeTrue(
            "Opt in to isolated emulator location tests",
            InstrumentationRegistry.getArguments().getString("locationEngineTests") == "true"
        )
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) { "Use an isolated emulator, never a personal phone" }
        check(coordinator.hasPreciseLocation() && coordinator.hasBackgroundLocation() && system.areNotificationsEnabled()) {
            "Grant precise/background location and notifications first"
        }
        val preferences = listOf(
            "local_geofences",
            "wallet_home_snapshot",
            "framework_geofences",
            "play_services_geofences"
        )
            .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        try {
            runBlocking { coordinator.disable() }
            preferences.keys.forEach {
                context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
            }
            shell("appops set ${context.packageName} android:mock_location allow")
            Tasks.await(fused.setMockMode(true), 20, TimeUnit.SECONDS)
            framework.addTestProvider(
                LocationManager.FUSED_PROVIDER, ProviderProperties.Builder()
                    .setHasNetworkRequirement(false).setHasSatelliteRequirement(false)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE).build()
            )
            framework.setTestProviderEnabled(LocationManager.FUSED_PROVIDER, true)
            NotificationPreferences(context).notificationStyle = CardNotificationStyle.CUSTOM_CARD
            NotificationPreferences(context).notificationPresentation = NotificationPresentation.STANDARD
            settings.barcodeType = BarcodeType.QR_CODE
            settings.saveTestStore(StoreLocation("test", "Synthetic checkout", 55.01, 12.0, 200f))
            runBlocking { coordinator.changeEngine(engine) }
            test()
        } finally {
            runBlocking { coordinator.disable() }
            framework.removeTestProvider(LocationManager.FUSED_PROVIDER)
            Tasks.await(fused.setMockMode(false), 20, TimeUnit.SECONDS)
            shell("appops set ${context.packageName} android:mock_location default")
            preferences.forEach { (name, values) ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply {
                    values.forEach { (key, value) ->
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
            settings.invalidateRegistration()
        }
    }

    private fun move(latitude: Double, longitude: Double) {
        val location = Location("fused").apply {
            this.latitude = latitude; this.longitude = longitude; accuracy = 5f
            time = System.currentTimeMillis(); elapsedRealtimeNanos =
            SystemClock.elapsedRealtimeNanos()
        }
        framework.setTestProviderLocation(LocationManager.FUSED_PROVIDER, location)
        Tasks.await(fused.setMockLocation(location), 20, TimeUnit.SECONDS)
    }

    private fun freshRefresh() = runBlocking {
        val current = framework.getLastKnownLocation(LocationManager.FUSED_PROVIDER)!!
        coroutineScope {
            val refresh = async { coordinator.refreshNearby(force = true) }
            delay(300)
            move(current.latitude, current.longitude)
            refresh.await()
        }
    }

    private fun awaitMoving(
        latitude: Double,
        longitude: Double,
        timeout: Long,
        condition: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) {
            move(latitude, longitude)
            SystemClock.sleep(1_000)
        }
        assertTrue(
            "$engine did not deliver the expected background presence and notification",
            condition()
        )
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(condition())
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                command
            )
        )
            .use { it.readBytes() }
    }
}
