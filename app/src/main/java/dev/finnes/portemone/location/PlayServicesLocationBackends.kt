// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import dev.finnes.portemone.GeofenceReceiver
import dev.finnes.portemone.R
import dev.finnes.portemone.StoreLocation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("MissingPermission")
internal class PlayServicesCurrentLocationBackend(private val context: Context) : CurrentLocationBackend {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    override suspend fun cachedLocation(): Location? = client.lastLocation.awaitResult()
    override suspend fun currentLocation(maxAgeMillis: Long): Location {
        val cancellation = CancellationTokenSource()
        return try {
            withTimeoutOrNull(16_000) {
                client.getCurrentLocation(CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(maxAgeMillis).setDurationMillis(15_000).build(), cancellation.token)
                    .awaitResult() ?: error(context.getString(R.string.location_no_location_fix_enable_location_and_try_again))
            } ?: error(context.getString(R.string.location_location_request_timed_out_try_again_outdoors_or_near))
        } finally { cancellation.cancel() }
    }
}

@SuppressLint("MissingPermission")
internal class PlayServicesGeofenceBackend(private val context: Context) : GeofenceBackend {
    override val engine = LocationEngine.PLAY_SERVICES
    private val client by lazy { LocationServices.getGeofencingClient(context) }
    private val ledger = context.getSharedPreferences("play_services_geofences", Context.MODE_PRIVATE)
    private val requestIds get() = ledger.getStringSet("requests", emptySet()).orEmpty().toSet()
    private fun available() = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    private val pendingIntent get() = requireNotNull(deliveryIntent(context, true))

    override suspend fun checkAvailability() {
        check(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
            context.getString(R.string.location_google_play_services_is_unavailable_choose_automatic_or_android)
        }
        val settings = LocationServices.getSettingsClient(context)
        settings.checkLocationSettings(LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000).build())
            .build()).awaitResult()
        check(settings.isGoogleLocationAccuracyEnabled().awaitResult()) {
            context.getString(R.string.location_enable_google_location_accuracy_in_device_location_settings_then)
        }
    }

    override suspend fun register(stores: List<StoreLocation>, anchor: Location, generation: String, regionRadius: Float) {
        require(stores.size <= NearbyStores.LIMIT)
        removeAll()
        val ids = (stores.map { "$generation:${it.id}" } + "$generation:region").toSet()
        check(ledger.edit().putStringSet("requests", ids).commit()) { context.getString(R.string.location_could_not_save_google_registrations) }
        val fences = stores.map { store ->
            Geofence.Builder().setRequestId("$generation:${store.id}")
                .setCircularRegion(store.latitude, store.longitude, store.radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .setNotificationResponsiveness(30_000).build()
        } + Geofence.Builder().setRequestId("$generation:region")
            .setCircularRegion(anchor.latitude, anchor.longitude, regionRadius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT).build()
        client.addGeofences(GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofences(fences).build(), pendingIntent).awaitResult()
    }

    override suspend fun removeAll() {
        val delivery = deliveryIntent(context, false)
        val ids = requestIds
        if (delivery == null && ids.isEmpty()) return
        if (!available()) {
            // Keep IDs for cleanup if Google services return. A canceled delivery
            // token prevents the old engine from sending usable callbacks meanwhile.
            delivery?.cancel()
            return
        }
        if (ids.isNotEmpty()) client.removeGeofences(ids.toList()).awaitResult()
        if (delivery != null) {
            client.removeGeofences(delivery).awaitResult()
            delivery.cancel()
        }
        check(ledger.edit().remove("requests").commit()) { context.getString(R.string.location_could_not_clear_google_registrations) }
    }
    override suspend fun remove(requestId: String) {
        if (!available()) return
        client.removeGeofences(listOf(requestId)).awaitResult()
        check(ledger.edit().putStringSet("requests", requestIds - requestId).commit()) { context.getString(R.string.location_could_not_clear_google_registration) }
    }
    override fun hasDelivery() = available() && deliveryIntent(context, false) != null

    companion object {
        const val ACTION = "dev.finnes.portemone.GEOFENCE_EVENT"
        fun deliveryIntent(context: Context, create: Boolean): PendingIntent? = PendingIntent.getBroadcast(context, 20,
            Intent(context, GeofenceReceiver::class.java).setAction(ACTION),
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or PendingIntent.FLAG_MUTABLE)

        fun decode(intent: Intent, resources: android.content.res.Resources): StoreEvent? {
            if (intent.action != ACTION) return null
            val event = GeofencingEvent.fromIntent(intent) ?: return null
            return StoreEvent(LocationEngine.PLAY_SERVICES, event.triggeringGeofences.orEmpty().map { it.requestId },
                when (event.geofenceTransition) {
                    Geofence.GEOFENCE_TRANSITION_ENTER -> StoreTransition.ENTER
                    Geofence.GEOFENCE_TRANSITION_EXIT -> StoreTransition.EXIT
                    else -> null
                }, event.triggeringLocation, if (event.hasError()) geofenceError(event.errorCode, resources) else null)
        }

        private fun geofenceError(code: Int, resources: android.content.res.Resources): String = when (code) {
            GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE -> resources.getString(R.string.location_geofencing_is_unavailable_check_location_and_google_location_accuracy)
            else -> resources.getString(R.string.location_location_setup_failed_refresh_nearby_stores_to_retry, GeofenceStatusCodes.getStatusCodeString(code))
        }

        fun settingsResolution(error: Exception) = (error as? ResolvableApiException)?.resolution?.intentSender

        fun errorMessage(error: Exception, resources: android.content.res.Resources): String? = when (error) {
            is ResolvableApiException -> resources.getString(R.string.location_location_services_need_attention_refresh_nearby_stores_to_review)
            is ApiException -> geofenceError(error.statusCode, resources)
            else -> null
        }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel(CancellationException("Location request canceled")) }
}
