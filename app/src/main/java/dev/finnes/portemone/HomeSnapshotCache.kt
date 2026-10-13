// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Cache identities, ordering and last-known distances; membership values stay in the card repository. */
internal class HomeSnapshotCache(context: Context) {
    private val preferences =
        context.getSharedPreferences("wallet_home_snapshot", Context.MODE_PRIVATE)

    fun restore(cards: List<Card>, enabled: Boolean): WalletHomeState? {
        val encoded = preferences.getString("snapshot", null) ?: return null
        return runCatching {
            val json = JSONObject(encoded)
            check(json.getInt("version") == 1)
            val items = json.getJSONArray("entries")
            val entries = (0 until items.length()).mapNotNull { index ->
                val item = items.getJSONObject(index)
                val card = cards.find { it.id == item.getString("id") } ?: return@mapNotNull null
                val store = card.stores.find { it.id == item.optString("store") }
                val sameBoundary =
                    store != null && item.optString("boundary") == store.boundaryKey()
                CardProximity.Entry(
                    card, enabled && item.getBoolean("nearby") && sameBoundary, store,
                    if (sameBoundary && item.has("distance")) item.getDouble("distance") else null
                )
            }.distinctBy { it.card.id }
            val all = entries + cards.filter { card -> entries.none { it.card.id == card.id } }
                .map { CardProximity.Entry(it, false, null, null) }
            val selected = preferences.getString("selected", null)
            WalletHomeState(
                restoring = false, entries = all, automaticEnabled = enabled,
                selectedId = selected?.takeIf { id -> all.any { it.nearby && it.card.id == id } }
                    ?: all.firstOrNull { it.nearby }?.card?.id,
                error = json.optString("error").takeIf { it.isNotBlank() })
        }.getOrElse {
            AppDiagnostics.event("Home", "Could not restore card view", it)
            null
        }
    }

    fun select(id: String) {
        preferences.edit { putString("selected", id) }
    }

    fun save(state: WalletHomeState) {
        val items = JSONArray()
        state.entries.forEach { entry ->
            items.put(
                JSONObject().put("id", entry.card.id).put("nearby", entry.nearby)
                    .put("store", entry.store?.id).put("boundary", entry.store?.boundaryKey())
                    .put("distance", entry.distanceMeters)
            )
        }
        val json = JSONObject().put("version", 1).put("entries", items).put("error", state.error)
        preferences.edit {
            putString("snapshot", json.toString()).putString(
                "selected",
                state.selectedId
            )
        }
    }
}

private fun StoreLocation.boundaryKey() = "$latitude/$longitude/$radiusMeters"
