// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import kotlin.math.*

object NearbyStores {
    fun matching(cards: List<Card>, insideStoreIds: Set<String>): List<Card> =
        cards.filter { card -> card.isUsable() && card.stores.any { it.id in insideStoreIds } }

    // Reserve one of the provider's 100 geofences for movement-triggered replacement.
    const val LIMIT = 99
    const val REGION_RADIUS_METERS = 20_000f

    // A refresh can show a card immediately only when a recent fix places its
    // accuracy circle inside the store boundary. Otherwise await the selected background engine.
    fun containing(
        stores: List<StoreLocation>, latitude: Double, longitude: Double,
        accuracyMeters: Float, ageMillis: Long
    ): Set<String> {
        return reconcile(stores, emptySet(), latitude, longitude, accuracyMeters, ageMillis)
    }

    fun reconcile(
        stores: List<StoreLocation>, previous: Set<String>, latitude: Double, longitude: Double,
        accuracyMeters: Float, ageMillis: Long
    ): Set<String> {
        if (ageMillis !in 0..30_000 || !accuracyMeters.isFinite() || accuracyMeters !in 0f..1_000f ||
            !latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0
        ) {
            return previous.intersect(stores.map { it.id }.toSet())
        }
        return stores.filter {
            val distance = distanceMeters(latitude, longitude, it.latitude, it.longitude)
            // An uncertain fix does not invent entry or departure at a boundary.
            distance + accuracyMeters <= it.radiusMeters ||
                    (it.id in previous && distance - accuracyMeters <= it.radiusMeters)
        }.map { it.id }.toSet()
    }

    fun nearest(
        stores: List<StoreLocation>,
        latitude: Double,
        longitude: Double
    ): List<StoreLocation> =
        stores.distinctBy { it.id }.map { store ->
            // Compute geodesic distance once per store, not on every sort comparison.
            val distance = distanceMeters(latitude, longitude, store.latitude, store.longitude)
            Triple(store, (distance - store.radiusMeters).coerceAtLeast(0.0), distance)
        }.sortedWith(compareBy<Triple<StoreLocation, Double, Double>> { it.second }
            .thenBy { it.third }.thenBy { it.first.id })
            .take(LIMIT).map { it.first }

    // Roll dense regions sooner: a fixed 20 km anchor can otherwise leave the
    // nearest batch unchanged while the user walks past hundreds of other stores.
    fun regionRadius(
        stores: List<StoreLocation>,
        selected: List<StoreLocation>,
        latitude: Double,
        longitude: Double
    ): Float {
        val ids = selected.map { it.id }.toSet()
        val nextBoundary = stores.filter { it.id !in ids }.minOfOrNull {
            (distanceMeters(
                latitude,
                longitude,
                it.latitude,
                it.longitude
            ) - it.radiusMeters).coerceAtLeast(0.0)
        } ?: return REGION_RADIUS_METERS
        return (nextBoundary / 2).toFloat().coerceIn(100f, REGION_RADIUS_METERS)
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latDelta = Math.toRadians(lat2 - lat1)
        val lonDelta = Math.toRadians(lon2 - lon1)
        val a = sin(latDelta / 2).pow(2) + cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) * sin(lonDelta / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    // One card may match overlapping stores. An exit must not hide it while another still matches.
    fun transition(inside: Set<String>, stores: Set<String>, entering: Boolean): Set<String> =
        if (entering) inside + stores else inside - stores
}
