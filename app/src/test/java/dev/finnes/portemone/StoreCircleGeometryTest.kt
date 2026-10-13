// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class StoreCircleGeometryTest {
    @Test fun savedStoreRadiusMatchesEveryPointOfDisplayedCircle() {
        for ((latitude, longitude, radius) in listOf(Triple(55.000000, 12.000000, 642.0),
            Triple(55.050000, 12.050000, 586.0), Triple(-33.86, 151.2, 150.0))) {
            val ring = StoreCircleGeometry.ring(latitude, longitude, radius)
            assertEquals(ring.first(), ring.last())
            for ((lat, lon) in ring) assertEquals(radius, distance(latitude, longitude, lat, lon), .01)
        }
    }

    @Test fun crossingDateLineStillUsesShortGeodesicRadius() {
        val ring = StoreCircleGeometry.ring(40.0, 179.999, 1000.0)
        assertTrue(ring.any { it.second < 0 })
        assertTrue(ring.all { it.second in -180.0..180.0 })
        for ((lat, lon) in ring) assertEquals(1000.0, distance(40.0, 179.999, lat, lon), .01)
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val a = sin(Math.toRadians(lat2 - lat1) / 2).pow(2) + cos(Math.toRadians(lat1)) *
            cos(Math.toRadians(lat2)) * sin(Math.toRadians(lon2 - lon1) / 2).pow(2)
        return 6_371_000 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
