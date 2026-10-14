// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.test.platform.app.InstrumentationRegistry
import dev.finnes.portemone.cards.LocalCardRepository
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CardChangeOutboxTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val original = TestMemberships.cards.first()

    @Test
    fun savedCardAndPendingWorkSurviveReopeningDatabase() = withDatabase { name, repository ->
        repository.saveCard(original)
        val pending = repository.pendingChanges().single()
        repository.close()
        LocalCardRepository(context, name).useRepository { reopened ->
            assertEquals(original, reopened.cards().single())
            assertEquals(pending, reopened.pendingChanges().single())
            assertTrue(reopened.acknowledge(pending))
            assertTrue(reopened.pendingChanges().isEmpty())
        }
    }

    @Test
    fun upgradingVersionFivePreservesCardsAndAddsDurableWork() = withDatabase { name, repository ->
        repository.saveCard(original)
        repository.close()
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("DROP TABLE pending_card_changes")
            db.version = 5
        }
        LocalCardRepository(context, name).useRepository { upgraded ->
            assertEquals(original, upgraded.cards().single())
            assertTrue(upgraded.pendingChanges().isEmpty())
            upgraded.saveCard(original.copy(cardName = "After upgrade"))
            assertEquals(original.id, upgraded.pendingChanges().single().cardId)
        }
    }

    @Test
    fun failedTransactionRollsBackBothCardAndPendingWork() = withDatabase { _, repository ->
        repository.saveCard(original)
        val pending = repository.pendingChanges()
        val updated = original.copy(cardName = "Changed", stores = emptyList())
        try {
            repository.saveCard(updated, ByteArray(8 * 1024 * 1024 + 1), original)
            fail("Oversized pass should fail after the card mutation and roll back")
        } catch (_: IllegalArgumentException) {
            assertEquals(listOf(original), repository.cards())
            assertEquals(pending, repository.pendingChanges())
        }
    }

    @Test
    fun metadataChangesDoNotRequireLocationWork() = withDatabase { _, repository ->
        repository.saveCard(original)
        repository.acknowledge(repository.pendingChanges().single())
        repository.saveCard(original.copy(cardName = "New name", rawValue = "001234"))
        assertTrue(repository.pendingChanges().single().changedStoreIds.isEmpty())
        repository.acknowledge(repository.pendingChanges().single())
        repository.saveStore(
            original.id,
            original.stores.single().copy(name = "New store name", address = "New address")
        )
        assertTrue(repository.pendingChanges().single().changedStoreIds.isEmpty())
    }

    @Test
    fun newerEditsAccumulateChangedCirclesAndCannotBeAcknowledgedByOlderAttempt() =
        withDatabase { _, repository ->
            repository.saveCard(original)
            repository.acknowledge(repository.pendingChanges().single())
            repository.saveStore(original.id, original.stores.single().copy(radiusMeters = 300f))
            val older = repository.pendingChanges().single()
            val newStore = original.stores.single().copy(id = "second-store", latitude = 56.0)
            repository.saveStore(original.id, newStore)
            val latest = repository.pendingChanges().single()
            assertEquals(setOf(original.stores.single().id, newStore.id), latest.changedStoreIds)
            assertFalse(repository.acknowledge(older))
            assertEquals(latest, repository.pendingChanges().single())
            assertTrue(repository.acknowledge(latest))
        }

    @Test
    fun deletionKeepsCancellationWorkAfterTheCardIsGone() = withDatabase { _, repository ->
        repository.saveCard(original)
        repository.acknowledge(repository.pendingChanges().single())
        repository.deleteCard(original.id)
        assertTrue(repository.cards().isEmpty())
        val pending = repository.pendingChanges().single()
        assertEquals(original.id, pending.cardId)
        assertEquals(original.stores.map { it.id }.toSet(), pending.changedStoreIds)
        assertTrue(repository.acknowledge(pending))
        assertFalse(repository.acknowledge(pending))
    }

    @Test
    fun archivingAndRestoringReconcileGeofences() = withDatabase { _, repository ->
        repository.saveCard(original)
        repository.acknowledge(repository.pendingChanges().single())
        repository.saveCard(original.copy(archived = true))
        assertEquals(
            original.stores.map { it.id }.toSet(),
            repository.pendingChanges().single().changedStoreIds
        )
        repository.acknowledge(repository.pendingChanges().single())
        repository.saveCard(original)
        assertEquals(
            original.stores.map { it.id }.toSet(),
            repository.pendingChanges().single().changedStoreIds
        )
    }

    private fun withDatabase(test: (String, LocalCardRepository) -> Unit) {
        val name = "card-outbox-${UUID.randomUUID()}.db"
        val repository = LocalCardRepository(context, name)
        try {
            test(name, repository)
        } finally {
            repository.close(); context.deleteDatabase(name)
        }
    }

    private fun LocalCardRepository.useRepository(test: (LocalCardRepository) -> Unit) {
        try {
            test(this)
        } finally {
            close()
        }
    }
}
