// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import kotlin.math.*

/** Geodesic boundary: the drawn radius uses meters, not screen pixels or longitude degrees. */
internal object StoreCircleGeometry {
    fun ring(latitude: Double, longitude: Double, radiusMeters: Double): List<Pair<Double, Double>> {
        val lat = Math.toRadians(latitude)
        val lon = Math.toRadians(longitude)
        val angularDistance = radiusMeters / 6_371_000.0
        val points = (0 until 72).map { index ->
            val bearing = 2 * PI * index / 72
            val nextLat = asin(sin(lat) * cos(angularDistance) + cos(lat) * sin(angularDistance) * cos(bearing))
            val nextLon = lon + atan2(sin(bearing) * sin(angularDistance) * cos(lat),
                cos(angularDistance) - sin(lat) * sin(nextLat))
            Math.toDegrees(nextLat) to ((Math.toDegrees(nextLon) + 540) % 360 - 180)
        }
        return points + points.first()
    }
}
