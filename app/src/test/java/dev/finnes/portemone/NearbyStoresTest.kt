// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import org.junit.Assert.*
import org.junit.Test

class NearbyStoresTest {
    @Test fun freshFixUsesEachStoresRadiusAndAccuracy() {
        val stores = listOf(StoreLocation("small", "Small", 0.0, 0.0, 100f),
            StoreLocation("large", "Large", 0.0, 0.0, 642f))
        // About 111 m from both pins: outside the small store, inside the large one.
        assertEquals(setOf("large"), NearbyStores.containing(stores, 0.001, 0.0, 15f, 1_000))
        assertTrue(NearbyStores.containing(stores, 0.001, 0.0, 600f, 1_000).isEmpty())
        assertTrue(NearbyStores.containing(stores, 0.01, 0.0, 15f, 1_000).isEmpty())
    }

    @Test fun oldOrInvalidFixCannotResurfaceACard() {
        val stores = listOf(StoreLocation("store", "Store", 0.0, 0.0, 200f))
        for (age in listOf(-1L, 30_001L, Long.MAX_VALUE)) {
            assertTrue(NearbyStores.containing(stores, 0.0, 0.0, 10f, age).isEmpty())
        }
        for (accuracy in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(NearbyStores.containing(stores, 0.0, 0.0, accuracy, 0).isEmpty())
        }
        assertEquals(setOf("store"), NearbyStores.containing(stores, 0.0, 0.0, 10f, 30_000))
    }

    @Test fun manualFixProducesEntryDepartureAndUncertainBoundaryRetention() {
        val stores = listOf(StoreLocation("a", "A", 0.0, 0.0, 150f),
            StoreLocation("b", "B", 0.0, 0.0, 300f))
        val inside = NearbyStores.reconcile(stores, emptySet(), 0.0, 0.0, 5f, 0)
        assertEquals(setOf("a", "b"), inside)
        assertEquals(setOf("b"), NearbyStores.reconcile(stores, inside, 0.002, 0.0, 5f, 0))
        assertTrue(NearbyStores.reconcile(stores, inside, 0.01, 0.0, 5f, 0).isEmpty())
        assertEquals(inside, NearbyStores.reconcile(stores, inside, 0.001, 0.0, 80f, 0))
        assertEquals(setOf("b"), NearbyStores.reconcile(stores, emptySet(), 0.001, 0.0, 80f, 0))
        assertEquals(inside, NearbyStores.reconcile(stores, inside, Double.NaN, 0.0, 5f, 0))
        assertEquals(setOf("a"), NearbyStores.reconcile(stores.take(1), inside, 0.0, 0.0, 5f, 31_000))
    }

    @Test fun nationwideListRegistersOnlyNearestEighty() {
        val stores = (1..250).map { StoreLocation("$it", "Store $it", it / 100.0, 0.0) }.reversed()
        val selected = NearbyStores.nearest(stores, 0.0, 0.0)
        assertEquals((1..80).map { "$it" }, selected.map { it.id })
    }

    @Test fun movingToAnotherRegionReplacesTheSelectedStores() {
        val copenhagen = (1..80).map { StoreLocation("cph$it", "Copenhagen", 55.67 + it / 10000.0, 12.56) }
        val aarhus = (1..80).map { StoreLocation("aar$it", "Aarhus", 56.16 + it / 10000.0, 10.20) }
        val stores = copenhagen + aarhus
        assertEquals(copenhagen.toSet(), NearbyStores.nearest(stores, 55.67, 12.56).toSet())
        assertEquals(aarhus.toSet(), NearbyStores.nearest(stores, 56.16, 10.20).toSet())
    }

    @Test fun duplicateStoresDoNotConsumeMultipleGeofences() {
        val store = StoreLocation("one", "One", 55.0, 12.0)
        assertEquals(listOf(store), NearbyStores.nearest(List(100) { store }, 55.0, 12.0))
        assertTrue(NearbyStores.nearest(emptyList(), 55.0, 12.0).isEmpty())
    }

    @Test fun enclosingLargeCircleIsRelevantEvenWhenItsCenterIsFartherAway() {
        val nearbySmall = (1..100).map { StoreLocation("small$it", "Small", 0.01 + it / 10000.0, 0.0, 100f) }
        val enclosing = StoreLocation("enclosing", "Large area", 0.05, 0.0, 10_000f)
        assertEquals(enclosing, NearbyStores.nearest(nearbySmall + enclosing, 0.0, 0.0).first())
        assertEquals(80, NearbyStores.nearest(nearbySmall + enclosing, 0.0, 0.0).size)
    }

    @Test fun denseRegionsRollBeforeUnmonitoredBoundariesWhileSparseRegionsKeepWideAnchor() {
        val stores = (1..1000).map { StoreLocation("$it", "Store", it / 10000.0, 0.0, 50f) }
        val selected = NearbyStores.nearest(stores, 0.0, 0.0)
        val radius = NearbyStores.regionRadius(stores, selected, 0.0, 0.0)
        assertTrue(radius in 100f..500f)
        assertEquals(NearbyStores.REGION_RADIUS_METERS,
            NearbyStores.regionRadius(selected, selected, 0.0, 0.0))
        val moved = NearbyStores.nearest(stores, 0.05, 0.0)
        assertTrue(moved.any { it.id !in selected.map { store -> store.id } })
    }

    @Test fun proximityWorksAcrossTheDateLine() {
        val across = StoreLocation("across", "Across date line", 0.0, -179.99)
        val farther = StoreLocation("farther", "Same hemisphere", 0.0, 179.0)
        assertEquals(across, NearbyStores.nearest(listOf(farther, across), 0.0, 179.99).first())
        assertEquals(2224.0, NearbyStores.distanceMeters(0.0, 179.99, 0.0, -179.99), 2.0)
    }

    @Test fun overlappingStoresKeepCardVisibleUntilLastExit() {
        var inside = NearbyStores.transition(emptySet(), setOf("a", "b"), entering = true)
        inside = NearbyStores.transition(inside, setOf("a"), entering = false)
        assertEquals(setOf("b"), inside)
        inside = NearbyStores.transition(inside, setOf("b"), entering = false)
        assertTrue(inside.isEmpty())
    }

    @Test fun repeatedEventsAreIdempotentAndUnknownExitDoesNotHideCard() {
        var inside = NearbyStores.transition(emptySet(), setOf("a"), entering = true)
        inside = NearbyStores.transition(inside, setOf("a"), entering = true)
        inside = NearbyStores.transition(inside, setOf("unknown"), entering = false)
        assertEquals(setOf("a"), inside)
        inside = NearbyStores.transition(inside, setOf("a"), entering = false)
        inside = NearbyStores.transition(inside, setOf("a"), entering = false)
        assertTrue(inside.isEmpty())
    }
}
