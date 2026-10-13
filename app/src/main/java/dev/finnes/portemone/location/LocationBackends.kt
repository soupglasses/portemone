// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.location

import dev.finnes.portemone.R
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dev.finnes.portemone.StoreLocation

/** One position request, with no service or long-lived subscription. */
internal interface CurrentLocationBackend {
    suspend fun cachedLocation(): Location?
    suspend fun currentLocation(maxAgeMillis: Long): Location
}

/** Providers own registration and delivery; the coordinator owns store/card presence. */
internal interface GeofenceBackend {
    val engine: LocationEngine
    suspend fun checkAvailability()
    suspend fun register(
        stores: List<StoreLocation>,
        anchor: Location,
        generation: String,
        regionRadius: Float
    )

    suspend fun removeAll()
    suspend fun remove(requestId: String)
    fun hasDelivery(): Boolean
}

enum class LocationEngine(@param:androidx.annotation.StringRes val labelRes: Int) {
    AUTOMATIC(R.string.locationengine_automatic), PLAY_SERVICES(R.string.locationengine_google_play_services), FRAMEWORK(R.string.locationengine_android_framework);

    companion object {
        internal fun select(
            preference: LocationEngine,
            microG: Boolean,
            googleAvailable: Boolean
        ): LocationEngine =
            when {
                preference != AUTOMATIC -> preference
                microG -> FRAMEWORK
                googleAvailable -> PLAY_SERVICES
                else -> FRAMEWORK
            }
    }
}

internal enum class StoreTransition { ENTER, EXIT }

/** Request IDs include a registration generation so queued events cannot revive old circles. */
internal data class StoreEvent(
    val engine: LocationEngine,
    val requestIds: List<String>,
    val transition: StoreTransition?,
    val location: Location? = null,
    val error: String? = null,
)

internal interface LocationBackends {
    fun resolve(preference: LocationEngine): LocationEngine
    fun geofences(engine: LocationEngine): GeofenceBackend
    fun currentLocation(engine: LocationEngine): CurrentLocationBackend
}

internal class LocationBackendFactory(private val context: Context) : LocationBackends {
    override fun resolve(preference: LocationEngine): LocationEngine {
        if (preference != LocationEngine.AUTOMATIC) return preference
        val microG = isMicroG()
        return LocationEngine.select(preference, microG, !microG && googleAvailable())
    }

    override fun geofences(engine: LocationEngine): GeofenceBackend = when (engine) {
        LocationEngine.PLAY_SERVICES -> PlayServicesGeofenceBackend(context)
        LocationEngine.FRAMEWORK -> FrameworkGeofenceBackend(context)
        LocationEngine.AUTOMATIC -> error("Resolve the location engine first")
    }

    override fun currentLocation(engine: LocationEngine): CurrentLocationBackend = when (engine) {
        LocationEngine.PLAY_SERVICES -> PlayServicesCurrentLocationBackend(context)
        LocationEngine.FRAMEWORK -> FrameworkCurrentLocationBackend(context)
        LocationEngine.AUTOMATIC -> error("Resolve the location engine first")
    }

    private fun googleAvailable(): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    @Suppress("DEPRECATION")
    private fun isMicroG(): Boolean = try {
        // microG occupies Google's package name and may spoof Google's signature.
        // Inspect its declared components, not the signature or Play Store presence.
        // https://github.com/microg/GmsCore/blob/master/play-services-core/src/main/AndroidManifest.xml
        val info = context.packageManager.getPackageInfo(
            "com.google.android.gms",
            PackageManager.GET_SERVICES
        )
        info.services.orEmpty().any { it.name.startsWith("org.microg.") }
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
