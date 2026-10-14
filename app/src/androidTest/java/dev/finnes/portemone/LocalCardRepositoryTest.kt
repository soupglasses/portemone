// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.location.Location
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.finnes.portemone.cards.LocalCardRepository
import dev.finnes.portemone.location.GeofenceCoordinator
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalCardRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun editsSurviveReopeningAndStayWithTheirMembership() {
        val database = "store-test-${UUID.randomUUID()}.db"
        var repository = LocalCardRepository(context, database)
        try {
            TestMemberships.cards.forEach(repository::saveCard)
            val firstCard = repository.cards().first { it.id == "fixture-one" }
            val secondCard = repository.cards().first { it.id == "fixture-two" }
            val added = StoreLocation("new-store", "Second Example One", 56.2, 10.2, 350f)
            repository.saveStore(firstCard.id, added)
            val edited = firstCard.stores.single().copy(latitude = 56.0, radiusMeters = 275f)
            repository.saveStore(firstCard.id, edited)
            assertEquals(secondCard, repository.cards().first { it.id == secondCard.id })
            repository.close()
            repository = LocalCardRepository(context, database)
            assertEquals(
                listOf(added, edited).toSet(),
                repository.cards().first { it.id == firstCard.id }.stores.toSet()
            )
            assertThrows(IllegalArgumentException::class.java) {
                repository.saveStore(
                    secondCard.id,
                    added
                )
            }
            repository.removeStore(secondCard.id, added.id)
            assertTrue(repository.cards().first { it.id == firstCard.id }.stores.contains(added))
            repository.removeStore(firstCard.id, added.id)
            repository.removeStore(firstCard.id, edited.id)
            repository.close()
            repository = LocalCardRepository(context, database)
            assertTrue(
                "Deleted seed stores must not be reseeded",
                repository.cards().first { it.id == firstCard.id }.stores.isEmpty()
            )
            assertEquals(secondCard, repository.cards().first { it.id == secondCard.id })
        } finally {
            repository.close()
            context.deleteDatabase(database)
        }
    }

    @Test
    fun invalidCircleDoesNotChangeSavedLocations() {
        val database = "store-validation-${UUID.randomUUID()}.db"
        val repository = LocalCardRepository(context, database)
        try {
            TestMemberships.cards.forEach(repository::saveCard)
            val before = repository.cards()
            val store = before.first().stores.single()
            listOf(
                store.copy(latitude = Double.NaN), store.copy(longitude = 181.0),
                store.copy(radiusMeters = -1f), store.copy(name = " "), store.copy(id = "region")
            ).forEach {
                assertThrows(IllegalArgumentException::class.java) {
                    repository.saveStore(
                        before.first().id,
                        it
                    )
                }
            }
            assertEquals(before, repository.cards())
            assertEquals(TestMemberships.cards.size.toLong(), repository.revision.value)
        } finally {
            repository.close(); context.deleteDatabase(database)
        }
    }

    @Test
    fun freshWalletStaysEmptyAndCardLifecycleSurvivesReopening() {
        val database = "card-lifecycle-${UUID.randomUUID()}.db"
        var repository = LocalCardRepository(context, database)
        val card = Card(
            "local-one",
            "Example",
            "Personal",
            R.drawable.ic_card,
            0xff146c64.toInt(),
            -1,
            BarcodeType.CODE_128,
            "0000012345",
            stores = listOf(StoreLocation("one-store", "Store", 55.0, 12.0))
        )
        try {
            assertTrue(repository.cards().isEmpty())
            repository.saveCard(card)
            val updated = card.copy(cardName = "Work")
            repository.saveCard(updated, expected = card)
            repository.close(); repository = LocalCardRepository(context, database)
            assertEquals(updated, repository.cards().single())
            repository.deleteCard(card.id)
            repository.close(); repository = LocalCardRepository(context, database)
            assertTrue("Deleted cards must never be reseeded", repository.cards().isEmpty())
        } finally {
            repository.close(); context.deleteDatabase(database)
        }
    }

    @Test
    fun capAllowsEditingAndRejectsEleventhLocationAtomically() {
        val database = "card-cap-${UUID.randomUUID()}.db"
        val repository = LocalCardRepository(context, database)
        val stores = (1..10).map { StoreLocation("store-$it", "Store $it", 55.0, 12.0) }
        val card = Card(
            "cap-card",
            "Example",
            "Card",
            R.drawable.ic_card,
            0,
            -1,
            BarcodeType.QR_CODE,
            "00001",
            stores = stores
        )
        try {
            repository.saveCard(card)
            repository.saveStore(card.id, stores.first().copy(radiusMeters = 250f))
            val before = repository.cards()
            val revision = repository.revision.value
            assertThrows(IllegalArgumentException::class.java) {
                repository.saveStore(
                    card.id,
                    StoreLocation("eleventh", "Extra", 55.0, 12.0)
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                repository.saveCard(
                    card.copy(
                        stores = stores + StoreLocation("eleventh", "Extra", 55.0, 12.0)
                    )
                )
            }
            assertEquals(before, repository.cards()); assertEquals(
                revision,
                repository.revision.value
            )
            repository.removeStore(card.id, stores.last().id)
            repository.saveStore(card.id, StoreLocation("replacement", "Replacement", 55.0, 12.0))
            assertEquals(10, repository.cards().single().stores.size)
        } finally {
            repository.close(); context.deleteDatabase(database)
        }
    }

    @Test
    fun packageAndSourceUpdateCommitTogetherAndStaleEditsCannotResurrectDeletedCards() {
        val database = "card-package-${UUID.randomUUID()}.db"
        var repository = LocalCardRepository(context, database)
        val card = Card(
            "package-one",
            "Example",
            "Card",
            R.drawable.ic_card,
            0,
            -1,
            BarcodeType.QR_CODE,
            "00001",
            source = CardSource("pkpass", "pass.example", "001")
        )
        val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        try {
            repository.saveCard(card, bytes)
            val duplicate = card.copy(id = "package-two")
            assertThrows(IllegalArgumentException::class.java) {
                repository.saveCard(
                    duplicate,
                    byteArrayOf(1)
                )
            }
            repository.close(); repository = LocalCardRepository(context, database)
            assertArrayEquals(bytes, repository.originalPackage(card.id))
            val update = card.copy(rawValue = "00002")
            repository.saveCard(update, byteArrayOf(9, 8, 7), expected = card)
            assertEquals(listOf(update), repository.cards())
            assertArrayEquals(byteArrayOf(9, 8, 7), repository.originalPackage(card.id))
            assertThrows(IllegalArgumentException::class.java) {
                repository.saveCard(
                    card,
                    bytes,
                    expected = card
                )
            }
            assertArrayEquals(byteArrayOf(9, 8, 7), repository.originalPackage(card.id))
            repository.deleteCard(card.id)
            assertNull(repository.originalPackage(card.id))
            assertThrows(IllegalArgumentException::class.java) {
                repository.saveCard(
                    update,
                    bytes,
                    expected = update
                )
            }
            assertTrue(repository.cards().isEmpty())
        } finally {
            repository.close(); context.deleteDatabase(database)
        }
    }

    @Test
    fun upgradeFromLocationOnlyDatabasePreservesCirclesWithoutInventingMemberships() {
        val database = "card-migration-${UUID.randomUUID()}.db"
        context.openOrCreateDatabase(database, 0, null).use { db ->
            db.execSQL("CREATE TABLE stores (id TEXT PRIMARY KEY, card_id TEXT NOT NULL, name TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, radius REAL NOT NULL)")
            db.execSQL("CREATE INDEX stores_card ON stores(card_id)")
            db.execSQL("INSERT INTO stores VALUES ('fixture-one-store', 'fixture-one', 'Edited store', 55.0, 12.0, 275)")
            db.version = 3
        }
        var repository = LocalCardRepository(context, database)
        try {
            val cards = repository.cards()
            assertEquals(1, cards.size)
            assertEquals(
                StoreLocation("fixture-one-store", "Edited store", 55.0, 12.0, 275f),
                cards.single().stores.single()
            )
            assertEquals(BarcodeType.NONE, cards.single().barcodeType)
            assertEquals("", cards.single().rawValue)
            assertEquals("Recovered", cards.single().brandName)
            repository.deleteCard("fixture-one")
            repository.close(); repository = LocalCardRepository(context, database)
            assertTrue(repository.cards().isEmpty())
        } finally {
            repository.close(); context.deleteDatabase(database)
        }
    }

    @Test
    fun refreshRequiresConfidentEntryAndExitAndHonorsSavedRadius() {
        val store = StoreLocation("store", "Store", 56.0, 10.0, 100f)
        val fix = Location("test").apply {
            latitude = 56.0; longitude = 10.0; accuracy = 10f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        assertEquals(
            setOf(store.id),
            GeofenceCoordinator.reconcilePresence(listOf(store), emptySet(), fix)
        )
        fix.latitude += 0.0009 // Approximately 100 m: uncertainty overlaps the boundary.
        assertTrue(GeofenceCoordinator.reconcilePresence(listOf(store), emptySet(), fix).isEmpty())
        assertEquals(
            setOf(store.id),
            GeofenceCoordinator.reconcilePresence(listOf(store), setOf(store.id), fix)
        )
        fix.latitude += 0.001
        assertTrue(
            GeofenceCoordinator.reconcilePresence(listOf(store), setOf(store.id), fix).isEmpty()
        )
        assertEquals(
            setOf(store.id),
            GeofenceCoordinator.reconcilePresence(
                listOf(store.copy(radiusMeters = 300f)),
                emptySet(),
                fix
            )
        )
        fix.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 31_000_000_000L
        assertTrue(
            GeofenceCoordinator.reconcilePresence(
                listOf(store.copy(radiusMeters = 300f)),
                emptySet(),
                fix
            ).isEmpty()
        )
    }
}
