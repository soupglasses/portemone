// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StoreLocationEditorTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = LocalCardRepository.get(context)

    @Test
    fun cancelKeepsSavedCircleAndUnsavedDraftRequiresConfirmation() = withCard { card ->
        edit(card).use {
            openArea()
            compose.onNodeWithText("Meters").performScrollTo().performTextReplacement("450")
            compose.onAllNodesWithText("Cancel").onLast().performClick()
            compose.onNodeWithText("Discard changes?").assertIsDisplayed()
            compose.onNodeWithText("Keep editing").performClick()
            compose.onNodeWithText("Meters").assertTextContains("450")
            compose.onAllNodesWithText("Cancel").onLast().performClick()
            compose.onNodeWithText("Discard", substring = false).performClick()
            assertEquals(card.stores, saved(card).stores)
        }
    }

    @Test
    fun customRadiusSurvivesRecreationAndOnlyPersistsWithCardSave() = withCard { card ->
        edit(card).use { scenario ->
            openArea()
            val slider =
                compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))
            slider.performScrollTo()
            assertEquals(
                100f..500f,
                slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range
            )
            compose.onNodeWithText("Meters").performScrollTo().performTextReplacement("1001")
            compose.onNodeWithText("Save changes").performClick()
            compose.onNodeWithText("Choose a radius between 50 and 1,000 meters.").performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText("Meters").performScrollTo().performTextReplacement("49")
            compose.onNodeWithText("Save changes").performClick()
            compose.onNodeWithText("Choose a radius between 50 and 1,000 meters.").performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText("Meters").performScrollTo().performTextReplacement("75")
            compose.onNodeWithText("Save changes").performClick()
            assertEquals(card.stores, saved(card).stores)
            scenario.recreate()
            openArea()
            compose.onNodeWithText("Meters").performScrollTo().assertTextContains("75")
                .performTextReplacement("750")
            compose.onNodeWithText("Save changes").performClick()
            compose.onNodeWithText("Save", substring = false).performClick()
            compose.waitUntil(8_000) { saved(card).stores.single().radiusMeters == 750f }
            assertEquals(
                card.stores.single().copy(radiusMeters = 750f),
                saved(card).stores.single()
            )
        }
    }

    @Test
    fun removingLocationThenDiscardingCardKeepsSavedLocation() = withCard { card ->
        edit(card).use {
            openArea()
            compose.onNodeWithText("Remove location").performScrollTo().performClick()
            compose.onNodeWithText("Remove", substring = false).performClick()
            compose.onNodeWithText("Add a location").performScrollTo().assertIsDisplayed()
            assertEquals(card.stores, saved(card).stores)
            compose.onAllNodesWithText("Cancel").onLast().performClick()
            compose.onNodeWithText("Discard", substring = false).performClick()
            assertEquals(card.stores, saved(card).stores)
        }
    }

    @Test
    fun firstLocationCanBeAddedBeforeSavingNewCard() = withCard { fixture ->
        ActivityScenario.launch<CardManagementActivity>(
            Intent(context, CardManagementActivity::class.java)
                .putExtra("card_action", "add")
        ).use { scenario ->
            await("Issuer")
            compose.onNodeWithText("Issuer").performScrollTo()
                .performTextReplacement(fixture.brandName + " new")
            compose.onNodeWithText("Label").performScrollTo().performTextReplacement("Personal")
            compose.onNodeWithText("Barcode value").performScrollTo()
                .performTextReplacement("000123")
            compose.onNodeWithText("Add a location").performScrollTo().performClick()
            compose.onNodeWithText("Store name").performTextReplacement("New store")
            compose.onNodeWithText("Edit exact coordinates").performScrollTo().performClick()
            compose.onNodeWithText("Latitude").performScrollTo().performTextReplacement("55.6761")
            compose.onNodeWithText("Longitude").performTextReplacement("12.5683")
            compose.onNodeWithText("Add location").performClick()
            scenario.recreate()
            compose.onNodeWithText("New store").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Save", substring = false).performClick()
            compose.waitUntil(8_000) {
                repository.cards().any { it.brandName == fixture.brandName + " new" }
            }
            val saved = repository.cards().single { it.brandName == fixture.brandName + " new" }
            assertEquals(55.6761, saved.stores.single().latitude, .000001)
            assertEquals(12.5683, saved.stores.single().longitude, .000001)
        }
    }

    @RequiresDeviceSetup("Requires storeLocationTests and a mock GPS provider")
    @Test
    fun firstLocationStartsAtCurrentPosition() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("storeLocationTests") == "true"
        )
        check(android.os.Build.MODEL.contains("sdk_gphone")) { "Use an emulator for mock location tests" }
        check(GeofenceCoordinator(context).hasPreciseLocation())
        val settings = LocationSettings(context)
        val engine = settings.locationEngine
        val manager = context.getSystemService(android.location.LocationManager::class.java)
        val provider = android.location.LocationManager.FUSED_PROVIDER
        fun fix() =
            manager.setTestProviderLocation(provider, android.location.Location(provider).apply {
                latitude = 55.6761; longitude = 12.5683; accuracy = 5f
                time = System.currentTimeMillis(); elapsedRealtimeNanos =
                android.os.SystemClock.elapsedRealtimeNanos()
            })
        try {
            settings.locationEngine = dev.finnes.portemone.location.LocationEngine.FRAMEWORK
            manager.addTestProvider(
                provider, android.location.provider.ProviderProperties.Builder()
                    .setPowerUsage(android.location.provider.ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(android.location.provider.ProviderProperties.ACCURACY_FINE).build()
            )
            manager.setTestProviderEnabled(provider, true)
            withCard { fixture ->
                val card = fixture.copy(stores = emptyList())
                repository.saveCard(card)
                edit(card).use {
                    await("Store locations")
                    fix()
                    compose.onNodeWithText("Add a location").performScrollTo().performClick()
                    compose.onNodeWithText("Edit exact coordinates").performScrollTo()
                        .performClick()
                    compose.waitUntil(10_000) {
                        fix()
                        compose.onAllNodes(hasText("55.676100")).fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithText("Latitude").performScrollTo()
                        .assertTextContains("55.676100")
                    compose.onNodeWithText("Longitude").assertTextContains("12.568300")
                    assertTrue(saved(card).stores.isEmpty())
                }
            }
        } finally {
            manager.removeTestProvider(provider)
            settings.locationEngine = engine
        }
    }

    @Test
    fun savedStoreRadiusRejectsValuesOutsideLimits() = withCard { card ->
        for (radius in listOf(49f, 1001f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                repository.saveStore(card.id, card.stores.single().copy(radiusMeters = radius))
            }
        }
        for (radius in listOf(50f, 1000f)) {
            repository.saveStore(card.id, card.stores.single().copy(radiusMeters = radius))
            assertEquals(radius, saved(card).stores.single().radiusMeters)
        }
    }

    @Test
    fun movingPinClearsAnAddressThatNoLongerMatches() = withCard { card ->
        edit(card).use {
            openArea()
            compose.onNodeWithText("Edit exact coordinates").performScrollTo().performClick()
            compose.onNodeWithText("Latitude").performScrollTo().performTextReplacement("55.7")
            compose.onNodeWithText("Save changes").performClick()
            compose.onNodeWithText("Save", substring = false).performClick()
            compose.waitUntil(8_000) { saved(card).stores.single().latitude == 55.7 }
            assertNull(saved(card).stores.single().address)
        }
    }

    private fun openArea() {
        await("Store locations")
        compose.onNodeWithText("Edit", substring = false).performScrollTo().performClick()
    }

    private fun await(text: String) = compose.waitUntil(8_000) {
        compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
    }

    private fun edit(card: Card) = ActivityScenario.launch<CardManagementActivity>(
        Intent(context, CardManagementActivity::class.java).putExtra("edit_card_id", card.id)
    )

    private fun saved(card: Card) = repository.cards().single { it.id == card.id }
    private fun withCard(test: (Card) -> Unit) {
        val settings = LocationSettings(context)
        val enabled = settings.enabled
        val id = UUID.randomUUID().toString()
        val card = TestCard.card.copy(
            id = id, brandName = "Location fixture $id",
            stores = listOf(
                StoreLocation(
                    UUID.randomUUID().toString(),
                    "Test store",
                    55.6761,
                    12.5683,
                    address = "Example Street 12"
                )
            )
        )
        try {
            settings.enabled = false
            repository.saveCard(card)
            test(card)
        } finally {
            repository.cards().filter { it.brandName.startsWith(card.brandName) }
                .forEach { repository.deleteCard(it.id) }
            settings.enabled = enabled
        }
    }
}
