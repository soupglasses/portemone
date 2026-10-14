// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.core.content.edit

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.provider.Settings
import java.security.MessageDigest
import java.time.Instant
import dev.finnes.portemone.location.LocationEngine
import dev.finnes.portemone.location.LocationBackendFactory

/** Geofence state stays local; membership locations are owned by the card repository. */
class LocationSettings(private val context: Context) {
    private val preferences = context.getSharedPreferences("local_geofences", Context.MODE_PRIVATE)

    fun observe(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) {
            if (enabled != value) AppDiagnostics.event("Settings", "enabled=$value")
            preferences.edit { putBoolean("enabled", value) }
        }
    var locationEngine: LocationEngine
        get() = runCatching {
            LocationEngine.valueOf(
                preferences.getString(
                    "location_engine",
                    "AUTOMATIC"
                )!!
            )
        }
            .getOrDefault(LocationEngine.AUTOMATIC)
        internal set(value) {
            preferences.edit { putString("location_engine", value.name) }
        }
    internal var registeredEngine: LocationEngine
        get() = runCatching {
            LocationEngine.valueOf(
                preferences.getString(
                    "registered_engine",
                    "PLAY_SERVICES"
                )!!
            )
        }
            .getOrDefault(LocationEngine.PLAY_SERVICES)
        set(value) {
            preferences.edit { putString("registered_engine", value.name) }
        }

    var status: String
        get() = preferences.getString("status", context.getString(R.string.location_automatic_cards_are_off)).orEmpty()
        set(value) {
            if (status != value) AppDiagnostics.event("Settings", "Location status changed")
            preferences.edit { putString("status", value) }
        }

    val lastNearbyUpdateAtMillis: Long
        get() = preferences.getLong("last_nearby_update_at", 0L)
    val lastNearbyUpdateSource: String
        get() = preferences.getString("last_nearby_update_source", "").orEmpty()

    /** Record only applied location results, never an attempt or registration receipt alone. */
    fun recordNearbyUpdate(source: String) {
        val updatedAt = System.currentTimeMillis()
        preferences.edit {
            putLong("last_nearby_update_at", updatedAt)
                .putString("last_nearby_update_source", source)
        }
        AppDiagnostics.event(
            "Location",
            "Nearby check succeeded; updatedAt=${Instant.ofEpochMilli(updatedAt)}, source=$source"
        )
    }

    var barcodeType: BarcodeType
        get() = runCatching { BarcodeType.valueOf(preferences.getString("type", null).orEmpty()) }
            .getOrDefault(BarcodeType.CODE_128)
        set(value) {
            if (barcodeType != value) AppDiagnostics.event("Settings", "barcodeType=$value")
            preferences.edit { putString("type", value.name) }
        }
    var generation: String
        get() = preferences.getString("generation", "").orEmpty()
        set(value) {
            preferences.edit { putString("generation", value) }
        }
    var inside: Set<String>
        get() = preferences.getStringSet("inside", emptySet()).orEmpty().toSet()
        set(value) {
            preferences.edit { putStringSet("inside", value) }
        }
    var registered: Set<String>
        get() = preferences.getStringSet("registered", emptySet()).orEmpty().toSet()
        set(value) {
            preferences.edit { putStringSet("registered", value) }
        }

    // Play services owns the registrations, so they survive our Activity and process.
    // A reboot, app update, or changed store boundaries makes the saved receipt obsolete.
    private fun registrationEnvironment(stores: List<StoreLocation> = stores()): String? {
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot < 0) return null
        @Suppress("DEPRECATION")
        val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val boundaries = stores.sortedBy { it.id }.joinToString(";") {
            "${it.id}:${it.latitude}:${it.longitude}:${it.radiusMeters}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(boundaries.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$boot:$updated:$digest:${LocationBackendFactory(context).resolve(locationEngine)}"
    }

    internal fun hasCurrentDeviceRegistration(): Boolean {
        val current = registrationEnvironment() ?: return false
        val saved = preferences.getString("registration_device", null)
            ?: preferences.getString("registration_environment", null)?.split(':')?.take(2)
                ?.joinToString(":")
        return current.split(':').take(2).joinToString(":") == saved
    }

    fun hasCurrentRegistration(): Boolean = generation.isNotBlank() &&
            anchor() != null && registrationEnvironment()?.let {
        it == preferences.getString("registration_environment", null)
    } == true

    fun markRegistered(stores: List<StoreLocation> = stores()) {
        val environment = registrationEnvironment(stores)
        preferences.edit {
            putString("registration_environment", environment)
            // Preserve the device epoch across failed registration retries, not across reboots.
            putString("registration_device", environment?.split(':')?.take(2)?.joinToString(":"))
        }
    }

    fun invalidateRegistration() {
        preferences.edit {
            remove("registration_environment").putString("generation", "")
                .putStringSet("registered", emptySet())
        }
    }

    fun anchor(): Location? {
        if (!preferences.contains("anchor_lat")) return null
        return Location("saved region").apply {
            latitude = preferences.getString("anchor_lat", "0")!!.toDouble()
            longitude = preferences.getString("anchor_lon", "0")!!.toDouble()
        }
    }

    internal val regionRadius: Float
        get() = preferences.getFloat("anchor_radius", NearbyStores.REGION_RADIUS_METERS)

    internal fun saveRegionRadius(radius: Float) {
        preferences.edit { putFloat("anchor_radius", radius) }
    }

    fun saveAnchor(location: Location) {
        preferences.edit {
            putString("anchor_lat", location.latitude.toString())
                .putString("anchor_lon", location.longitude.toString())
        }
    }

    fun saveTestStore(location: Location) {
        saveTestStore(
            StoreLocation(
                "test",
                preferences.getString("test_name", context.getString(R.string.test_store_name))!!,
                location.latitude,
                location.longitude,
                preferences.getFloat("test_radius", 200f)
            )
        )
    }

    fun saveTestStore(store: StoreLocation) {
        require(store.id == "test" && store.name.isNotBlank()) { context.getString(R.string.invalid_test_store) }
        require(
            store.latitude.isFinite() && store.latitude in -90.0..90.0 &&
                    store.longitude.isFinite() && store.longitude in -180.0..180.0 &&
                    store.radiusMeters.isFinite() && store.radiusMeters in StoreRadiusRange
        ) { context.getString(R.string.choose_valid_store_area) }
        preferences.edit {
            putString("test_lat", store.latitude.toString())
                .putString("test_lon", store.longitude.toString())
                .putString("test_name", store.name.trim())
                .putFloat("test_radius", store.radiusMeters)
        }
    }

    fun removeTestStore() {
        preferences.edit {
            remove("test_lat").remove("test_lon").remove("test_name")
                .remove("test_radius")
                .putStringSet("inside", inside - "test")
        }
    }

    fun cards(): List<Card> {
        val test = if (preferences.contains("test_lat")) listOf(
            StoreLocation(
                "test",
                preferences.getString("test_name", context.getString(R.string.test_store_name))!!,
                preferences.getString("test_lat", "0")!!.toDouble(),
                preferences.getString("test_lon", "0")!!.toDouble(),
                preferences.getFloat("test_radius", 200f)
            )
        ) else emptyList()
        // A temporary test area owns a distinct membership and never activates a retailer's card.
        return LocalCardRepository.get(context).cards() + if (test.isEmpty()) emptyList() else
            listOf(TestCard.card.copy(barcodeType = barcodeType, stores = test))
    }

    fun stores(): List<StoreLocation> = cards().filter { it.isUsable() }.flatMap { it.stores }

    internal fun hasPendingCardChanges(): Boolean =
        LocalCardRepository.get(context).pendingChanges().isNotEmpty()

    internal fun activeStoreIds(): Set<String> {
        if (!enabled) return emptySet()
        val changedIds = if (hasCurrentRegistration()) emptySet() else
            LocalCardRepository.get(context).pendingChanges().flatMap { it.changedStoreIds }.toSet()
        return inside - changedIds
    }

    fun activeCards(): List<Card> = NearbyStores.matching(cards(), activeStoreIds())

}
