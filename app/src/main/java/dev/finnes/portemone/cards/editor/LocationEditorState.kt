// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.cards.editor

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import dev.finnes.portemone.LocationOrigin
import dev.finnes.portemone.R
import dev.finnes.portemone.StoreLocation
import dev.finnes.portemone.StoreRadiusRange
import kotlinx.coroutines.CancellationException
import java.util.Locale
import java.util.UUID

internal data class EditorPosition(val latitude: Double, val longitude: Double)
internal data class StoreSearchResult(val label: String, val latitude: Double, val longitude: Double)

internal class LocationEditorState(
    private val original: StoreLocation?,
    private val anchor: StoreLocation?,
    val id: String = original?.id ?: UUID.randomUUID().toString()
) {
    var name by mutableStateOf(original?.name.orEmpty())
    var latitude by mutableStateOf((original?.latitude ?: anchor?.latitude)?.toString().orEmpty())
        private set
    var longitude by mutableStateOf((original?.longitude ?: anchor?.longitude)?.toString().orEmpty())
        private set
    var radius by mutableStateOf((original?.radiusMeters ?: 150f).toString().removeSuffix(".0"))
    var address by mutableStateOf(original?.address)
        private set
    var search by mutableStateOf("")
    var error by mutableStateOf<Int?>(null)
    var searchError by mutableStateOf<Int?>(null)
        private set
    var searchResults by mutableStateOf<List<StoreSearchResult>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var locating by mutableStateOf(false)
        private set
    var mapPositionRevision by mutableIntStateOf(0)
        private set
    private var positionRevision = 0

    val lat get() = latitude.toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 }
    val lon get() = longitude.toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }
    val meters get() = radius.toFloatOrNull()?.takeIf { it.isFinite() && it in StoreRadiusRange }
    val valid get() = name.isNotBlank() && lat != null && lon != null && meters != null
    val draft get() = StoreLocation(id, name.trim(), lat ?: 0.0, lon ?: 0.0, meters ?: 150f,
        original?.origin ?: LocationOrigin.USER, address)
    val dirty get() = original?.let {
        name.trim() != it.name || lat != it.latitude || lon != it.longitude ||
            meters != it.radiusMeters || address != it.address
    } ?: (name.isNotBlank() || search.isNotBlank() || lat != anchor?.latitude ||
        lon != anchor?.longitude || meters != 150f)

    fun editLatitude(value: String) {
        positionRevision++
        address = null
        latitude = value
    }

    fun editLongitude(value: String) {
        positionRevision++
        address = null
        longitude = value
    }

    fun placePin(latitude: Double, longitude: Double) {
        positionRevision++
        changePosition(latitude, longitude)
    }

    fun centerMap() { mapPositionRevision++ }

    fun selectResult(result: StoreSearchResult) {
        placePin(result.latitude, result.longitude)
        address = result.label
        if (name.isBlank()) name = search.trim()
        searchResults = emptyList()
        centerMap()
    }

    private fun changePosition(lat: Double, lon: Double) {
        address = null
        latitude = "%.6f".format(Locale.US, lat)
        longitude = "%.6f".format(Locale.US, lon)
    }

    suspend fun locate(currentPosition: suspend () -> EditorPosition) {
        val revision = positionRevision
        locating = true
        error = null
        try {
            val position = currentPosition()
            // A delayed fix must not replace a pin the user has already placed.
            if (positionRevision == revision) {
                changePosition(position.latitude, position.longitude)
                centerMap()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = R.string.could_not_find_your_location_try_again_search_an
        } finally {
            locating = false
        }
    }

    suspend fun find(searchPlaces: suspend (String) -> List<StoreSearchResult>) {
        val query = search.trim()
        searching = true
        searchError = null
        searchResults = emptyList()
        try {
            searchResults = searchPlaces(query)
            if (searchResults.isEmpty()) searchError = R.string.no_result_try_a_street_and_city_or_place
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            searchError = R.string.search_is_unavailable_place_the_pin_on_the_map
        } finally {
            searching = false
        }
    }

    companion object {
        fun saver(original: StoreLocation?, anchor: StoreLocation?) = mapSaver(
            save = { state: LocationEditorState -> mapOf(
                "id" to state.id, "name" to state.name, "latitude" to state.latitude,
                "longitude" to state.longitude, "radius" to state.radius,
                "address" to state.address, "search" to state.search, "error" to state.error,
                "positionRevision" to state.positionRevision
            ) },
            restore = { values -> LocationEditorState(original, anchor, values["id"] as String).apply {
                name = values["name"] as String
                latitude = values["latitude"] as String
                longitude = values["longitude"] as String
                radius = values["radius"] as String
                address = values["address"] as String?
                search = values["search"] as String
                error = values["error"] as Int?
                positionRevision = values["positionRevision"] as Int
            } }
        )
    }
}
