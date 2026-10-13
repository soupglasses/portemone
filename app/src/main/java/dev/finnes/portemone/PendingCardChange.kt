// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

/** A coalesced outbox entry; acknowledging an older attempt must not erase a newer edit. */
internal data class PendingCardChange(
    val cardId: String,
    val token: String,
    val changedStoreIds: Set<String>,
)

internal fun changedStoreIds(before: List<StoreLocation>, after: List<StoreLocation>): Set<String> {
    fun boundaries(stores: List<StoreLocation>) = stores.associate {
        it.id to Triple(it.latitude, it.longitude, it.radiusMeters)
    }

    val old = boundaries(before)
    val new = boundaries(after)
    return (old.keys + new.keys).filterTo(mutableSetOf()) { old[it] != new[it] }
}
