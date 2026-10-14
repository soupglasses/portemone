// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.app.NotificationManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.finnes.portemone.cards.LocalCardRepository
import dev.finnes.portemone.location.GeofenceCoordinator
import dev.finnes.portemone.location.LocationEngine
import dev.finnes.portemone.location.LocationSettings
import dev.finnes.portemone.notifications.CardNotificationStyle
import dev.finnes.portemone.notifications.NotificationPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real framework delivery while Google packages are disabled on the isolated test emulator. */
@RequiresDeviceSetup("scripts/test-location-engines.sh disables Google services and supplies locationEngineTests")
class FrameworkWithoutGoogleTest {
    @org.junit.Before
    fun establishMembershipFixtures() {
        val repository =
            LocalCardRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val ids = repository.cards().map { it.id }.toSet()
        TestMemberships.cards.filter { it.id !in ids }.forEach { repository.saveCard(it) }
    }

    @Test
    fun automaticFrameworkEngineWorksWithoutRunningGoogleServices() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("locationEngineTests") == "true"
        )
        check(Build.MODEL.contains("sdk_gphone")) { "Use an isolated emulator" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val coordinator = GeofenceCoordinator(context)
        val settings = LocationSettings(context)
        val locationManager = context.getSystemService(LocationManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    command
                )
            )
                .use { it.readBytes() }
        }

        fun move(latitude: Double) {
            locationManager.setTestProviderLocation(
                LocationManager.FUSED_PROVIDER,
                Location("fused").apply {
                    this.latitude = latitude; longitude = 12.0; accuracy = 5f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos =
                    SystemClock.elapsedRealtimeNanos()
                })
        }

        fun awaitMoving(latitude: Double, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) {
                move(latitude)
                SystemClock.sleep(500)
            }
            assertTrue(
                "Framework transition and notification did not arrive without Google",
                condition()
            )
        }

        val preferences = context.getSharedPreferences("local_geofences", Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        var providerAdded = false
        try {
            runBlocking { coordinator.disable() }
            shell("pm disable-user --user 0 com.google.android.gms")
            shell("pm disable-user --user 0 com.android.vending")
            shell("appops set ${context.packageName} android:mock_location allow")
            preferences.edit().clear().commit()
            locationManager.addTestProvider(
                LocationManager.FUSED_PROVIDER, ProviderProperties.Builder()
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE).build()
            )
            providerAdded = true
            locationManager.setTestProviderEnabled(LocationManager.FUSED_PROVIDER, true)
            settings.saveTestStore(StoreLocation("test", "Google-free checkout", 55.01, 12.0, 200f))
            NotificationPreferences(context).notificationStyle = CardNotificationStyle.CUSTOM_CARD
            assertEquals(LocationEngine.AUTOMATIC, settings.locationEngine)
            assertEquals(LocationEngine.FRAMEWORK, coordinator.selectedEngine())
            move(55.0)
            runBlocking { coordinator.enable() }
            awaitMoving(55.01) {
                "test" in settings.inside && notifications.activeNotifications.any { it.tag == TestCard.card.id }
            }
            awaitMoving(55.0) {
                settings.inside.isEmpty() && notifications.activeNotifications.none { it.tag == TestCard.card.id }
            }
            assertTrue(coordinator.hasCurrentRegistration())
        } finally {
            runBlocking { coordinator.disable() }
            if (providerAdded) locationManager.removeTestProvider(LocationManager.FUSED_PROVIDER)
            shell("pm enable --user 0 com.google.android.gms")
            shell("pm enable --user 0 com.android.vending")
            shell("appops set ${context.packageName} android:mock_location default")
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
            settings.invalidateRegistration()
        }
    }
}
