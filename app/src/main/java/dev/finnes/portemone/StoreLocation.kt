// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

data class StoreLocation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 150f,
    val origin: LocationOrigin = LocationOrigin.USER,
    val address: String? = null,
)

internal val StoreRadiusRange = 50f..1000f

enum class LocationOrigin { USER, IMPORTED, CATALOG }

// Only small location values belong in saved state, not the card's image data.
internal val StoreLocationsSaver =
    androidx.compose.runtime.saveable.listSaver<List<StoreLocation>, String>(
        save = { stores ->
            stores.map { store ->
                org.json.JSONObject().put("id", store.id).put("name", store.name)
                    .put("latitude", store.latitude).put("longitude", store.longitude)
                    .put("address", store.address ?: org.json.JSONObject.NULL)
                    .put("radius", store.radiusMeters).put("origin", store.origin.name).toString()
            }
        },
        restore = { values ->
            values.map { value ->
                val store = org.json.JSONObject(value)
                StoreLocation(
                    store.getString("id"),
                    store.getString("name"),
                    store.getDouble("latitude"),
                    store.getDouble("longitude"),
                    store.getDouble("radius").toFloat(),
                    LocationOrigin.valueOf(store.getString("origin")),
                    store.optString("address")
                        .takeUnless { store.isNull("address") || it.isBlank() }
                )
            }
        }
    )
