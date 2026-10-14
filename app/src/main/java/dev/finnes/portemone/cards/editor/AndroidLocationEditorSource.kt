// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.cards.editor

import android.content.Context
import android.location.Geocoder
import dev.finnes.portemone.GeofenceCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidLocationEditorSource(private val context: Context) {
    private val coordinator = GeofenceCoordinator(context)

    fun hasPreciseLocation() = coordinator.hasPreciseLocation()

    suspend fun currentPosition(): EditorPosition {
        val position = coordinator.currentLocation(maxAgeMillis = 0)
        return EditorPosition(position.latitude, position.longitude)
    }

    suspend fun search(query: String): List<StoreSearchResult> = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        Geocoder(context).getFromLocationName(query, 4).orEmpty().map {
            StoreSearchResult(it.getAddressLine(0) ?: query, it.latitude, it.longitude)
        }
    }
}
