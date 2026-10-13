// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SavedLocationListTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun locationsAreVisibleWithoutDetailsAndOpenTheirSavedPin() {
        val repository = LocalCardRepository.get(context)
        val settings = LocationSettings(context)
        val enabled = settings.enabled
        val first = StoreLocation(
            UUID.randomUUID().toString(),
            "Market & café",
            55.6761,
            12.5683,
            address = "Example Street 12"
        )
        val second = StoreLocation(UUID.randomUUID().toString(), "Other branch", 55.68, 12.57)
        val stores = listOf(first, second) + (3..10).map { index ->
            StoreLocation(
                UUID.randomUUID().toString(),
                "Branch $index",
                55.68 + index * .001,
                12.57
            )
        }
        val card = TestCard.card.copy(id = UUID.randomUUID().toString(), stores = stores)
        Intents.init()
        try {
            settings.enabled = false
            repository.saveCard(card)
            Intents.intending(hasAction(Intent.ACTION_VIEW))
                .respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
            ActivityScenario.launch<CardActivity>(CardActivity.intent(context, card))
                .use { scenario ->
                    compose.waitUntil(8_000) {
                        compose.onAllNodesWithTag("card_details").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithText("Details", substring = false).assertDoesNotExist()
                    compose.onNodeWithTag("card_details")
                        .performScrollToNode(hasText(first.address!!))
                    compose.onNodeWithText(first.address!!).assertIsDisplayed().performClick()
                    Intents.intended(
                        allOf(
                            hasAction(Intent.ACTION_VIEW),
                            hasData(first.mapsIntent().data)
                        )
                    )
                    assertEquals(
                        "55.6761,12.5683 (Market & café)",
                        android.net.Uri.decode(
                            first.mapsIntent().data.toString().substringAfter("?q=")
                        )
                    )
                    compose.onNodeWithTag("card_details")
                        .performScrollToNode(hasTestTag("location_maps_${second.id}"))
                    compose.onNodeWithTag("location_maps_${second.id}").assertIsDisplayed()
                        .performClick()
                    Intents.intended(
                        allOf(
                            hasAction(Intent.ACTION_VIEW),
                            hasData(second.mapsIntent().data)
                        )
                    )
                    stores.forEach { store ->
                        compose.onNodeWithTag("card_details")
                            .performScrollToNode(hasTestTag("location_map_${store.id}"))
                        compose.onNodeWithTag("location_map_${store.id}").assertIsDisplayed()
                        compose.onNodeWithTag("card_details")
                            .performScrollToNode(hasTestTag("location_maps_${store.id}"))
                        compose.onNodeWithTag("location_maps_${store.id}").assertIsDisplayed()
                    }
                    scenario.recreate()
                    compose.onNodeWithTag("card_details")
                        .performScrollToNode(hasText(first.address!!))
                    compose.onNodeWithText(first.address!!).assertIsDisplayed()
                }
        } finally {
            Intents.release()
            repository.deleteCard(card.id)
            settings.enabled = enabled
        }
    }

    @Test
    fun versionFourLocationsSurviveAddressMigration() {
        val name = "address-migration-${UUID.randomUUID()}.db"
        val store = StoreLocation("branch", "Original store", 55.1, 12.1, 250f)
        val card = TestCard.card.copy(id = "fixture", stores = listOf(store))
        try {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("CREATE TABLE cards (id TEXT PRIMARY KEY, source_format TEXT, source_issuer TEXT, source_record TEXT, payload TEXT NOT NULL)")
                db.execSQL("CREATE TABLE stores (id TEXT PRIMARY KEY, card_id TEXT NOT NULL, name TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, radius REAL NOT NULL, origin TEXT NOT NULL DEFAULT 'USER')")
                db.execSQL("CREATE INDEX stores_card ON stores(card_id)")
                db.execSQL(
                    "INSERT INTO cards(id, payload) VALUES (?, ?)",
                    arrayOf(card.id, CardStorageCodec.encode(card))
                )
                db.execSQL(
                    "INSERT INTO stores VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        store.id,
                        card.id,
                        store.name,
                        store.latitude,
                        store.longitude,
                        store.radiusMeters,
                        store.origin.name
                    )
                )
                db.version = 4
            }
            val repository = LocalCardRepository(context, name)
            val addressed = store.copy(address = "Example Street 12")
            try {
                assertEquals(card, repository.cards().single())
                repository.saveStore(card.id, addressed)
            } finally {
                repository.close()
            }
            val reopened = LocalCardRepository(context, name)
            try {
                assertEquals(addressed, reopened.cards().single().stores.single())
            } finally {
                reopened.close()
            }

        } finally {
            context.deleteDatabase(name)
        }
    }
}
