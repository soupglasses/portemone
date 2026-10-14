// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import dev.finnes.portemone.GeofenceReceiver
import dev.finnes.portemone.R
import dev.finnes.portemone.StoreLocation
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("MissingPermission")
internal class FrameworkCurrentLocationBackend(private val context: Context) : CurrentLocationBackend {
    private val manager = context.getSystemService(LocationManager::class.java)
    private fun providers() = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER).filter { it in manager.allProviders && manager.isProviderEnabled(it) }

    override suspend fun cachedLocation(): Location? = providers().mapNotNull { manager.getLastKnownLocation(it) }
        .filter { it.hasAccuracy() && it.accuracy.isFinite() && it.accuracy >= 0 &&
            (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 in 0..30_000 }
        .minWithOrNull(compareBy<Location> { it.accuracy }.thenByDescending { it.elapsedRealtimeNanos })

    override suspend fun currentLocation(maxAgeMillis: Long): Location {
        if (maxAgeMillis > 0) cachedLocation()?.takeIf {
            (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 <= maxAgeMillis
        }?.let { return it }
        val providers = providers()
        check(providers.isNotEmpty()) { context.getString(R.string.location_no_enabled_location_provider_turn_on_device_location_and) }
        // Without a network provider, GPS remains a bounded foreground recovery path.
        // Framework getCurrentLocation may itself use a fix from the past few seconds.
        return withTimeoutOrNull(16_000) {
            coroutineScope {
                val requests = providers.map { provider -> async { request(provider) } }.toMutableList()
                var best: Location? = null
                while (requests.isNotEmpty()) {
                    val (completed, fix) = select {
                        requests.forEach { request -> request.onAwait { request to it } }
                    }
                    requests.remove(completed)
                    if (fix == null || !fix.hasAccuracy() || !fix.accuracy.isFinite() || fix.accuracy < 0) continue
                    if (best == null || fix.accuracy < best.accuracy) best = fix
                    if (fix.accuracy <= 50f) break
                }
                requests.forEach { it.cancel() }
                best ?: error(context.getString(R.string.location_no_location_fix_try_again_outdoors_or_near_a))
            }
        } ?: error(context.getString(R.string.location_location_request_timed_out_try_again_outdoors_or_near))
    }

    private suspend fun request(provider: String): Location? = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        manager.getCurrentLocation(provider, LocationRequest.Builder(0)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setDurationMillis(15_000).build(),
            cancellation, context.mainExecutor) { location ->
            if (continuation.isActive) continuation.resume(location)
        }
    }
}

@SuppressLint("MissingPermission")
internal class FrameworkGeofenceBackend(private val context: Context) : GeofenceBackend {
    override val engine = LocationEngine.FRAMEWORK
    private val manager = context.getSystemService(LocationManager::class.java)
    private val ledger = context.getSharedPreferences("framework_geofences", Context.MODE_PRIVATE)
    private val requestIds get() = ledger.getStringSet("requests", emptySet()).orEmpty().toSet()

    override suspend fun checkAvailability() {
        check(manager.isLocationEnabled) { context.getString(R.string.location_turn_on_device_location_to_monitor_stores) }
        // Android's proximity manager subscribes to the framework fused provider.
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/location/geofence/GeofenceManager.java
        // A successful API call alone cannot establish support on a ROM without it.
        check(LocationManager.FUSED_PROVIDER in manager.allProviders &&
            manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            context.getString(R.string.location_android_framework_proximity_alerts_need_an_enabled_fused_location)
        }
    }

    override suspend fun register(stores: List<StoreLocation>, anchor: Location, generation: String, regionRadius: Float) = withContext(Dispatchers.IO) {
        require(stores.size <= NearbyStores.LIMIT)
        val circles = stores + StoreLocation("region", "Monitored region", anchor.latitude,
            anchor.longitude, regionRadius)
        val ids = circles.map { "$generation:${it.id}" }.toSet()
        // Persist cleanup identities before touching system state, including partial
        // registration if the process dies or a later circle throws.
        check(ledger.edit().putStringSet("requests", requestIds + ids).commit()) { context.getString(R.string.location_could_not_save_proximity_registrations) }
        try {
            circles.forEach { store ->
                manager.addProximityAlert(store.latitude, store.longitude, store.radiusMeters, -1,
                    requireNotNull(deliveryIntent(context, "$generation:${store.id}", true)))
            }
        } catch (error: Exception) {
            ids.forEach { remove(it) }
            throw error
        }
    }

    override suspend fun removeAll() = withContext(Dispatchers.IO) { requestIds.forEach { remove(it) } }
    override suspend fun remove(requestId: String) = withContext(Dispatchers.IO) {
        deliveryIntent(context, requestId, false)?.let {
            manager.removeProximityAlert(it)
            it.cancel()
        }
        check(ledger.edit().putStringSet("requests", requestIds - requestId).commit()) { context.getString(R.string.location_could_not_clear_proximity_registrations) }
    }
    override fun hasDelivery(): Boolean = LocationManager.FUSED_PROVIDER in manager.allProviders &&
        manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) && requestIds.isNotEmpty() &&
        requestIds.all { deliveryIntent(context, it, false) != null }

    companion object {
        const val ACTION = "dev.finnes.portemone.FRAMEWORK_GEOFENCE_EVENT"
        fun deliveryIntent(context: Context, requestId: String, create: Boolean): PendingIntent? =
            PendingIntent.getBroadcast(context, 21,
                Intent(context, GeofenceReceiver::class.java).setAction(ACTION)
                    .setData(Uri.Builder().scheme("portemone-geofence").authority("framework")
                        .appendPath(requestId).build()),
                (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or PendingIntent.FLAG_MUTABLE)

        fun decode(intent: Intent): StoreEvent? {
            if (intent.action != ACTION || !intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return null
            if (intent.data?.scheme != "portemone-geofence" || intent.data?.authority != "framework") return null
            val requestId = intent.data?.lastPathSegment ?: return null
            return StoreEvent(LocationEngine.FRAMEWORK, listOf(requestId),
                if (intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)) StoreTransition.ENTER else StoreTransition.EXIT)
        }
    }
}
