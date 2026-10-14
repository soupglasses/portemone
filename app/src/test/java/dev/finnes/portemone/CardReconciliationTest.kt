// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.location.CardProximity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardReconciliationTest {
    private val first = TestMemberships.cards[0]
    private val second = TestMemberships.cards[1]

    @Test
    fun catalogEditsKeepBrowsingOrderAndUseCurrentCardData() {
        val edited = first.copy(cardName = "Updated label")
        val entries = listOf(entry(second, 25.0), entry(first, 25.0))

        val result = CardProximity.reconcile(entries, listOf(edited, second), inside(first, second))

        assertEquals(listOf(second, edited), result.map { it.card })
        assertEquals(listOf(25.0, 25.0), result.map { it.distanceMeters })
    }

    @Test
    fun removedCardsDisappearAndNewCardsFollowExistingCardsAtEqualDistance() {
        val added = first.copy(id = "added")
        val result = CardProximity.reconcile(
            listOf(entry(second), entry(first)), listOf(added, first), emptySet()
        )

        assertEquals(listOf(first, added), result.map { it.card })
        assertTrue(result.none { it.nearby })
    }

    @Test
    fun editedCircleInvalidatesDistanceWithoutLosingConfirmedPresence() {
        val edited = first.copy(stores = listOf(first.stores.single().copy(radiusMeters = 400f)))
        val result = CardProximity.reconcile(
            listOf(entry(first, 10.0), entry(second, 25.0)),
            listOf(edited, second), inside(edited, second)
        )

        assertEquals(listOf(second, edited), result.map { it.card })
        assertTrue(result.last().nearby)
        assertEquals(edited.stores.single(), result.last().store)
        assertNull(result.last().distanceMeters)
    }

    @Test
    fun leavingOneStoreSelectsAnotherMatchingStoreWithoutReusingItsDistance() {
        val otherStore = first.stores.single().copy(id = "other-store")
        val card = first.copy(stores = first.stores + otherStore)
        val result = CardProximity.reconcile(
            listOf(entry(card, 10.0)), listOf(card), setOf(otherStore.id)
        ).single()

        assertTrue(result.nearby)
        assertEquals(otherStore, result.store)
        assertNull(result.distanceMeters)
    }

    @Test
    fun disabledPresenceClearsNearbyStateAndDistances() {
        val result = CardProximity.reconcile(
            listOf(entry(first, 10.0)), listOf(first), emptySet()
        ).single()

        assertFalse(result.nearby)
        assertNull(result.store)
        assertNull(result.distanceMeters)
    }

    @Test
    fun unusableCardsCannotRemainNearby() {
        for (card in listOf(
            first.copy(archived = true),
            first.copy(voided = true),
            first.copy(expiresAt = "2000-01-01T00:00:00Z")
        )) {
            val result = CardProximity.reconcile(
                listOf(entry(first, 10.0)), listOf(card), inside(card)
            ).single()

            assertFalse(result.nearby)
            assertNull(result.store)
            assertNull(result.distanceMeters)
        }
    }

    private fun entry(card: Card, distance: Double? = null) =
        CardProximity.Entry(card, true, card.stores.first(), distance)

    private fun inside(vararg cards: Card) = cards.flatMap { it.stores }.map { it.id }.toSet()
}
