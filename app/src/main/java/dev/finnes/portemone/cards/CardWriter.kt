// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.cards

import android.content.Context
import dev.finnes.portemone.AppDiagnostics
import dev.finnes.portemone.Card
import dev.finnes.portemone.CardChangeWork
import dev.finnes.portemone.CardNotificationManager
import dev.finnes.portemone.LocalCardRepository
import dev.finnes.portemone.LocationSettings
import dev.finnes.portemone.R
import dev.finnes.portemone.StoreLocation
import dev.finnes.portemone.TestCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Commits card edits locally before scheduling their system presentation. */
internal class CardWriter internal constructor(
    context: Context,
    private val scheduleCardChanges: () -> Unit,
) {
    constructor(context: Context) : this(
        context, { CardChangeWork.enqueue(context.applicationContext) }
    )

    private val context = context.applicationContext
    private val repository = LocalCardRepository.get(this.context)
    private val settings = LocationSettings(this.context)
    private val notifications = CardNotificationManager(this.context)

    suspend fun saveStore(cardId: String, store: StoreLocation) {
        persistCardChange(cardId) {
            if (cardId == TestCard.card.id) {
                val before = checkNotNull(settings.cards().find { it.id == cardId }) {
                    context.getString(R.string.location_this_test_card_has_been_deleted)
                }
                settings.saveTestStore(store)
                repository.recordTestStoreChange(before, before.copy(stores = listOf(store)))
            } else repository.saveStore(cardId, store)
        }
    }

    suspend fun saveCard(card: Card, originalPackage: ByteArray? = null, expected: Card? = null) {
        persistCardChange(card.id) {
            repository.saveCard(card, originalPackage, expected)
        }
    }

    suspend fun deleteCard(card: Card) {
        persistCardChange(card.id) { repository.deleteCard(card.id) }
    }

    private suspend fun persistCardChange(cardId: String, mutation: () -> Unit) =
        withContext(Dispatchers.IO) {
            // Saving never acquires the location coordinator's lock: a slow registration or GPS
            // request must not delay committing a user's edit. The outbox commits with the card.
            mutation()
            runCatching { notifications.hide(cardId) }.onFailure {
                AppDiagnostics.event("Cards", "Saved card cancellation will retry", it)
            }
            runCatching { scheduleCardChanges() }.onFailure {
                // Application startup drains the outbox if scheduling was interrupted or failed.
                AppDiagnostics.event("Cards", "Saved card scheduling will retry on startup", it)
            }
        }

}
