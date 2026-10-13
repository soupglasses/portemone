// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.UUID

/** Memberships, circles and original pass packages commit locally and atomically. */
class LocalCardRepository internal constructor(
    context: Context,
    databaseName: String = "wallet-cards.db"
) {
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes.asStateFlow()
    private val database =
        object : SQLiteOpenHelper(context.applicationContext, databaseName, null, 6) {
            override fun onConfigure(db: SQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }

            override fun onCreate(db: SQLiteDatabase) {
                createCards(db)
                createPendingChanges(db)
                db.execSQL("CREATE TABLE stores (id TEXT PRIMARY KEY, card_id TEXT NOT NULL, name TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, radius REAL NOT NULL, origin TEXT NOT NULL DEFAULT 'USER', FOREIGN KEY(card_id) REFERENCES cards(id) ON DELETE CASCADE)")
                db.execSQL("ALTER TABLE stores ADD COLUMN address TEXT")
                db.execSQL("CREATE INDEX stores_card ON stores(card_id)")

            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                if (oldVersion < 4) {
                    createCards(db)
                    // Early prototypes stored circles but no membership values. Preserve the
                    // circles as plain cards; users can supply their codes without shipped secrets.
                    db.query("stores", arrayOf("card_id"), null, null, "card_id", null, null)
                        .use { cursor ->
                            while (cursor.moveToNext()) {
                                val recovered = Card(
                                    cursor.getString(0), "Recovered", "Card", R.drawable.ic_card,
                                    0xFF146C64.toInt(), -1, BarcodeType.NONE, "",
                                    description = "Re-enter the membership code for this card. Its saved locations were preserved."
                                )
                                db.insertOrThrow("cards", null, cardValues(recovered))
                            }
                        }
                    db.execSQL("ALTER TABLE stores RENAME TO old_stores")
                    db.execSQL("DROP INDEX stores_card")
                    db.execSQL("CREATE TABLE stores (id TEXT PRIMARY KEY, card_id TEXT NOT NULL REFERENCES cards(id) ON DELETE CASCADE, name TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, radius REAL NOT NULL, origin TEXT NOT NULL DEFAULT 'USER')")
                    db.execSQL("INSERT INTO stores (id, card_id, name, latitude, longitude, radius) SELECT id, card_id, name, latitude, longitude, radius FROM old_stores WHERE card_id IN (SELECT id FROM cards)")
                    db.execSQL("DROP TABLE old_stores")
                    db.execSQL("CREATE INDEX stores_card ON stores(card_id)")
                }
                if (oldVersion < 5) db.execSQL("ALTER TABLE stores ADD COLUMN address TEXT")
                if (oldVersion < 6) createPendingChanges(db)
            }
        }

    @Synchronized
    fun cards(): List<Card> {
        val stores = mutableMapOf<String, MutableList<StoreLocation>>()
        database.readableDatabase.query("stores", null, null, null, null, null, "rowid")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    stores.getOrPut(cursor.getString(cursor.getColumnIndexOrThrow("card_id"))) { mutableListOf() }
                        .add(
                            StoreLocation(
                                cursor.getString(cursor.getColumnIndexOrThrow("id")),
                                cursor.getString(cursor.getColumnIndexOrThrow("name")),
                                cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
                                cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")),
                                cursor.getFloat(cursor.getColumnIndexOrThrow("radius")),
                                LocationOrigin.valueOf(
                                    cursor.getString(
                                        cursor.getColumnIndexOrThrow(
                                            "origin"
                                        )
                                    )
                                ),
                                cursor.getString(cursor.getColumnIndexOrThrow("address"))
                            )
                        )
                }
            }
        val cards = mutableListOf<Card>()
        database.readableDatabase.query(
            "cards",
            arrayOf("id", "payload"),
            null,
            null,
            null,
            null,
            "rowid"
        ).use { cursor ->
            while (cursor.moveToNext()) cards.add(
                CardStorageCodec.decode(
                    cursor.getString(0),
                    cursor.getString(1),
                    stores[cursor.getString(0)].orEmpty()
                )
            )
        }
        cards.forEach(AppDiagnostics::protect)
        return cards
    }

    @Synchronized
    fun saveStore(cardId: String, store: StoreLocation) {
        val before = cards().firstOrNull { it.id == cardId }
        requireNotNull(before) { throw UserFacingException(R.string.error_unknown_membership_card) }
        validateStore(store)
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.query("stores", arrayOf("card_id"), "id = ?", arrayOf(store.id), null, null, null)
                .use {
                    require(!it.moveToFirst() || it.getString(0) == cardId) { throw UserFacingException(R.string.error_this_location_belongs_to_another_card) }
                }
            val existing = before.stores
            require(existing.any { it.id == store.id } || existing.size < MAX_CARD_LOCATIONS) { throw UserFacingException(R.string.error_each_card_can_have_up_to_10_locations) }
            db.insertWithOnConflict(
                "stores",
                null,
                values(cardId, store.copy(name = store.name.trim())),
                SQLiteDatabase.CONFLICT_REPLACE
            )
                .also { check(it != -1L) { throw UserFacingException(R.string.error_could_not_save_this_location) } }
            recordChange(
                db,
                before,
                before.copy(stores = existing.filterNot { it.id == store.id } + store))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value += 1
    }

    @Synchronized
    fun removeStore(cardId: String, storeId: String) {
        val before = cards().firstOrNull { it.id == cardId }
        requireNotNull(before) { throw UserFacingException(R.string.error_unknown_membership_card) }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            if (db.delete("stores", "id = ? AND card_id = ?", arrayOf(storeId, cardId)) > 0) {
                recordChange(
                    db,
                    before,
                    before.copy(stores = before.stores.filterNot { it.id == storeId })
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value += 1
    }

    /** Card and location changes commit together, including imported pass updates. */
    @Synchronized
    fun saveCard(card: Card, originalPackage: ByteArray? = null, expected: Card? = null) {
        AppDiagnostics.protect(card)
        validateCard(card)
        require(card.stores.size <= MAX_CARD_LOCATIONS) { throw UserFacingException(R.string.error_each_card_can_have_up_to_10_locations_detail) }
        require(card.stores.map { it.id }
            .distinct().size == card.stores.size) { throw UserFacingException(R.string.error_duplicate_location_identifiers) }
        card.stores.forEach(::validateStore)
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val current = cards()
            require(expected == null || current.find { it.id == expected.id } == expected) { throw UserFacingException(R.string.error_this_card_changed_while_you_were_editing_reopen) }
            require(current.none { it.id == card.id && (it.source?.let { source -> source.format to (source.issuerId to source.recordId) } != card.source?.let { source -> source.format to (source.issuerId to source.recordId) }) }) { throw UserFacingException(R.string.error_a_card_s_pass_identity_cannot_be_changed) }
            require(current.none { it.id != card.id && card.source != null && it.source?.format == card.source.format && it.source?.issuerId == card.source.issuerId && it.source?.recordId == card.source.recordId }) { throw UserFacingException(R.string.error_this_pass_is_already_saved_update_the_existing) }
            require(card.stores.none { store -> current.any { it.id != card.id && it.stores.any { other -> other.id == store.id } } }) { throw UserFacingException(R.string.error_this_location_belongs_to_another_card) }
            // UPDATE preserves row order and avoids REPLACE's cascading deletion.
            val value = cardValues(
                card.copy(
                    brandName = card.brandName.trim(),
                    cardName = card.cardName.trim()
                )
            )
            require(
                value.getAsString("payload").toByteArray(Charsets.UTF_8).size <= 1024 * 1024
            ) { throw UserFacingException(R.string.error_this_card_contains_too_much_detail_to_save) }
            if (db.update(
                    "cards",
                    value,
                    "id = ?",
                    arrayOf(card.id)
                ) == 0
            ) db.insertOrThrow("cards", null, value)
            db.delete("stores", "card_id = ?", arrayOf(card.id))
            card.stores.forEach {
                db.insertOrThrow(
                    "stores",
                    null,
                    values(card.id, it.copy(name = it.name.trim()))
                )
            }
            if (originalPackage != null) {
                require(originalPackage.size <= 8 * 1024 * 1024) { throw UserFacingException(R.string.error_this_pass_package_is_too_large) }
                db.delete("pass_packages", "card_id = ?", arrayOf(card.id))
                // Small rows avoid Android CursorWindow's limit for large BLOBs.
                var offset = 0
                var part = 0
                while (offset < originalPackage.size) {
                    val end = minOf(offset + 128 * 1024, originalPackage.size)
                    db.insertOrThrow("pass_packages", null, ContentValues().apply {
                        put("card_id", card.id); put("part", part++); put(
                        "data",
                        originalPackage.copyOfRange(offset, end)
                    )
                    })
                    offset = end
                }
            }
            recordChange(db, current.find { it.id == card.id }, card)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value += 1
    }

    @Synchronized
    fun deleteCard(cardId: String) {
        val before = cards().firstOrNull { it.id == cardId } ?: return
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete("cards", "id = ?", arrayOf(cardId))
            recordChange(db, before, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value += 1
    }

    @Synchronized
    internal fun pendingChanges(): List<PendingCardChange> =
        database.readableDatabase.query(
            "pending_card_changes",
            null,
            null,
            null,
            null,
            null,
            "rowid"
        )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val ids =
                            JSONArray(cursor.getString(cursor.getColumnIndexOrThrow("changed_stores")))
                        add(
                            PendingCardChange(
                                cursor.getString(cursor.getColumnIndexOrThrow("card_id")),
                                cursor.getString(cursor.getColumnIndexOrThrow("token")),
                                (0 until ids.length()).map { ids.getString(it) }.toSet()
                            )
                        )
                    }
                }
            }

    @Synchronized
    internal fun acknowledge(change: PendingCardChange): Boolean =
        database.writableDatabase.delete(
            "pending_card_changes",
            "card_id = ? AND token = ?",
            arrayOf(change.cardId, change.token)
        ) > 0

    @Synchronized
    internal fun recordTestStoreChange(before: Card, after: Card) {
        recordChange(database.writableDatabase, before, after)
    }

    private fun recordChange(db: SQLiteDatabase, before: Card?, after: Card?) {
        val id = requireNotNull(after?.id ?: before?.id)
        val previous = pendingChanges().find { it.cardId == id }
        val changed = changedStoreIds(
            before?.takeIf { it.isUsable() }?.stores.orEmpty(),
            after?.takeIf { it.isUsable() }?.stores.orEmpty()
        ) + previous?.changedStoreIds.orEmpty()
        db.insertWithOnConflict("pending_card_changes", null, ContentValues().apply {
            put("card_id", id)
            put("token", UUID.randomUUID().toString())
            put("changed_stores", JSONArray(changed.toList()).toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
            .also { check(it != -1L) { throw UserFacingException(R.string.error_could_not_queue_this_card_update) } }
    }

    @Synchronized
    fun originalPackage(cardId: String): ByteArray? {
        val result = java.io.ByteArrayOutputStream()
        database.readableDatabase.query(
            "pass_packages",
            arrayOf("data"),
            "card_id = ?",
            arrayOf(cardId),
            null,
            null,
            "part"
        ).use { cursor ->
            while (cursor.moveToNext()) result.write(cursor.getBlob(0))
        }
        return result.toByteArray().takeIf { it.isNotEmpty() }
    }

    internal fun close() = database.close()

    companion object {
        @Volatile
        private var instance: LocalCardRepository? = null
        fun get(context: Context): LocalCardRepository = instance ?: synchronized(this) {
            instance ?: LocalCardRepository(context).also { instance = it }
        }

        private fun createPendingChanges(db: SQLiteDatabase) {
            // No foreign key: deletion must retain a cancellation tombstone until reconciled.
            db.execSQL("CREATE TABLE pending_card_changes (card_id TEXT PRIMARY KEY, token TEXT NOT NULL, changed_stores TEXT NOT NULL)")
        }

        private fun createCards(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE cards (id TEXT PRIMARY KEY, source_format TEXT, source_issuer TEXT, source_record TEXT, payload TEXT NOT NULL, UNIQUE(source_format, source_issuer, source_record))")
            db.execSQL("CREATE TABLE pass_packages (card_id TEXT NOT NULL REFERENCES cards(id) ON DELETE CASCADE, part INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(card_id, part))")
        }

        private fun cardValues(card: Card) = ContentValues().apply {
            put("id", card.id); put("source_format", card.source?.format)
            put("source_issuer", card.source?.issuerId); put("source_record", card.source?.recordId)
            put("payload", CardStorageCodec.encode(card))
        }

        private fun validateStore(store: StoreLocation) {
            require(store.id.isNotBlank() && store.id != "region" && store.id != "test" && ':' !in store.id) { throw UserFacingException(R.string.error_invalid_location_identifier) }
            require(store.name.isNotBlank()) { throw UserFacingException(R.string.error_give_this_location_a_name) }
            require(store.latitude.isFinite() && store.latitude in -90.0..90.0 && store.longitude.isFinite() && store.longitude in -180.0..180.0) { throw UserFacingException(R.string.error_choose_a_valid_location) }
            require(store.radiusMeters.isFinite() && store.radiusMeters in StoreRadiusRange) { throw UserFacingException(R.string.choose_a_radius_between_50_and_1_000_meters) }
        }

        private fun values(cardId: String, store: StoreLocation) = ContentValues().apply {
            put("id", store.id); put("card_id", cardId); put("name", store.name)
            put("latitude", store.latitude); put("longitude", store.longitude); put(
            "radius",
            store.radiusMeters
        ); put("origin", store.origin.name)
            put("address", store.address)
        }
    }
}
