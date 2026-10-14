// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.cards.changedStoreIds
import org.junit.Assert.*
import org.junit.Test

class PendingCardChangeTest {
    private val store = StoreLocation("one", "Store", 55.0, 12.0, 150f)

    @Test
    fun labelsAddressesAndOrderingDoNotChangeGeofences() {
        val other = store.copy(id = "two")
        assertTrue(
            changedStoreIds(
                listOf(store, other),
                listOf(other, store.copy(name = "Renamed", address = "A street"))
            ).isEmpty()
        )
    }

    @Test
    fun addedRemovedMovedAndResizedCirclesRequireReconciliation() {
        for (changed in listOf(
            store.copy(latitude = 56.0),
            store.copy(longitude = 13.0),
            store.copy(radiusMeters = 250f)
        )) {
            assertEquals(setOf(store.id), changedStoreIds(listOf(store), listOf(changed)))
        }
        assertEquals(setOf(store.id), changedStoreIds(emptyList(), listOf(store)))
        assertEquals(setOf(store.id), changedStoreIds(listOf(store), emptyList()))
    }
}
