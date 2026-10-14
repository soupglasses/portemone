// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.location.Location
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.finnes.portemone.location.CardProximity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardProximityTest {
    private val fix
        get() = Location("test").apply {
            latitude = 56.0; longitude = 10.0; accuracy = 5f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

    private fun store(id: String, latitude: Double) = StoreLocation(id, id, latitude, 10.0, 150f)

    @Test
    fun nearbyCardsComeFirstAndEachCardUsesItsNearestMatchingStore() {
        val far = store("far branch", 56.01)
        val near = store("near branch", 56.0001)
        val middle = store("middle", 56.0005)
        val unconfirmed = store("unconfirmed", 56.0)
        val a = TestMemberships.cards.first().copy(id = "a", stores = listOf(far, near))
        val b = a.copy(id = "b", stores = listOf(middle))
        val c = a.copy(id = "c", stores = listOf(unconfirmed))
        val ranked = CardProximity.order(listOf(c, b, a), setOf(far.id, near.id, middle.id), fix)
        assertEquals(listOf("a", "b", "c"), ranked.map { it.card.id })
        assertEquals(near, ranked.first().store)
        assertTrue(ranked.first().proximityLabel!!.contains("near branch"))
        assertNull(ranked.last().proximityLabel)
        assertEquals("near branch", ranked.first().proximityLabel)
    }

    @Test
    fun cardsOutsideStoreCirclesSortByTheirNearestSavedStore() {
        val ikea = TestMemberships.cards.first().copy(id = "ikea", stores = listOf(store("ikea-store", 56.08)))
        val coop = ikea.copy(id = "coop", stores = listOf(store("coop-far", 56.1), store("coop-close", 56.005)))
        val unknown = ikea.copy(id = "unknown", stores = emptyList())
        val cards = listOf(unknown, ikea, coop)
        val ranked = CardProximity.order(cards, emptySet(), fix)
        assertEquals(listOf("coop", "ikea", "unknown"), ranked.map { it.card.id })
        assertTrue(ranked.none { it.nearby })
        assertEquals("coop-close", ranked.first().store?.id)
        val reconciled = CardProximity.reconcile(ranked, cards, emptySet())
        assertEquals(ranked, reconciled)
    }

    @Test
    fun membershipsAtTheSameStoreKeepTheirSavedOrder() {
        val shared = store("shared store", 56.0001)
        val a = TestMemberships.cards.first().copy(id = "a", stores = listOf(shared))
        val b = a.copy(id = "b", rawValue = "another membership")
        val result = CardProximity.order(listOf(b, a), setOf(shared.id), fix)
        assertEquals(listOf("b", "a"), result.map { it.card.id })
        assertEquals(result.first().distanceMeters, result.last().distanceMeters)
    }

    @Test
    fun staleLocationDoesNotClaimCurrentDistances() {
        val a =
            TestMemberships.cards.first().copy(id = "a", stores = listOf(store("a store", 56.01)))
        val b = a.copy(id = "b", stores = listOf(store("b store", 56.0)))
        val old = fix.apply { elapsedRealtimeNanos -= 60_000_000_000L }
        val result = CardProximity.order(listOf(a, b), setOf("a store", "b store"), old)
        assertEquals(listOf("a", "b"), result.map { it.card.id })
        assertTrue(result.all { it.distanceMeters == null })
        assertEquals("a store", result.first().proximityLabel)
    }
}
