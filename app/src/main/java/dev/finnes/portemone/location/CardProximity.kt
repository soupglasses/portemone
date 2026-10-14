// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.location

import android.location.Location
import android.os.SystemClock
import dev.finnes.portemone.Card
import dev.finnes.portemone.StoreLocation
import dev.finnes.portemone.isUsable

/** Distance ranks choices; only geofence presence decides which cards are nearby. */
object CardProximity {
    private val nearbyOrder = compareByDescending<Entry> { it.nearby }.thenBy {
        it.distanceMeters ?: Double.POSITIVE_INFINITY
    }

    data class Entry(
        val card: Card,
        val nearby: Boolean,
        val store: StoreLocation?,
        val distanceMeters: Double?
    ) {
        val proximityLabel: String?
            get() = store?.name?.takeIf { nearby }
    }

    fun order(cards: List<Card>, inside: Set<String>, fix: Location?): List<Entry> {
        val recent = fix?.takeIf {
            it.hasAccuracy() && it.accuracy.isFinite() && it.accuracy >= 0 &&
                    it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
                    it.longitude.isFinite() && it.longitude in -180.0..180.0 &&
                    it.elapsedRealtimeNanos > 0 &&
                    (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 in 0..30_000
        }
        return cards.map { card ->
            val matching = card.stores.filter { card.isUsable() && it.id in inside }
            val nearby = matching.isNotEmpty()
            val candidates = if (nearby) matching else card.stores
            val nearest = if (recent == null) candidates.firstOrNull() else candidates.minByOrNull {
                NearbyStores.distanceMeters(
                    recent.latitude,
                    recent.longitude,
                    it.latitude,
                    it.longitude
                )
            }
            val distance = if (recent != null && nearest != null) NearbyStores.distanceMeters(
                recent.latitude, recent.longitude, nearest.latitude, nearest.longitude
            ) else null
            Entry(card, nearby, nearest, distance)
        }.sortedWith(nearbyOrder)
    }

    /** Apply edits and presence changes without acquiring a fix or reordering equal-distance cards. */
    internal fun reconcile(
        entries: List<Entry>,
        cards: List<Card>,
        inside: Set<String>
    ): List<Entry> {
        val cardsById = cards.associateBy { it.id }
        val previousIds = entries.map { it.card.id }.toSet()
        val existing = entries.mapNotNull { entry ->
            val card = cardsById[entry.card.id] ?: return@mapNotNull null
            entry.copy(card = card)
        }
        val added = cards.filter { it.id !in previousIds }.map { Entry(it, false, null, null) }
        return (existing + added).map { entry ->
            val matches = entry.card.stores.filter { entry.card.isUsable() && it.id in inside }
            val candidates = if (matches.isNotEmpty()) matches else entry.card.stores.takeIf { entry.card.isUsable() }.orEmpty()
            val store = candidates.find { it.id == entry.store?.id } ?: candidates.firstOrNull()
            entry.copy(
                nearby = matches.isNotEmpty(),
                store = store,
                // A changed circle invalidates its cached distance even when its ID is unchanged.
                distanceMeters = entry.distanceMeters.takeIf { store != null && store == entry.store }
            )
        }.sortedWith(nearbyOrder)
    }
}
