// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import dev.finnes.portemone.cards.LocalCardRepository
import dev.finnes.portemone.location.GeofenceCoordinator
import dev.finnes.portemone.location.LocationSettings
import dev.finnes.portemone.location.NearbyStores
import dev.finnes.portemone.notifications.CardNotificationManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual foreground lifecycle, Play services fixes, and Android notifications. */
@RequiresDeviceSetup("scripts/test-location-engines.sh controls real providers and foreground recovery")
@RunWith(AndroidJUnit4::class)
class NearbyRefreshTest {
    @org.junit.Before
    fun establishMembershipFixtures() {
        val repository =
            LocalCardRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val ids = repository.cards().map { it.id }.toSet()
        TestMemberships.cards.filter { it.id !in ids }.forEach { repository.saveCard(it) }
    }

    @get:Rule
    val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun temporaryTestCardCanBeOpenedAndDeletedWithoutChangingRealMemberships() =
        withLocationFixture {
            val repository = LocalCardRepository.get(context)
            val originalCards = repository.cards()
            settings.barcodeType = BarcodeType.CODE_128
            moveTo(atStore())
            runBlocking { manager.enable() }
            ActivityScenario.launch(DeveloperToolsActivity::class.java).use {
                compose.onNodeWithText("Create test card here").performScrollTo().performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Move test card here")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
                }
                compose.onNodeWithText("Move test card here").assertIsEnabled()
            }
            deferBackgroundEvents()
            val temporary = settings.cards().single { it.id == TestCard.card.id }
            assertEquals("1234567890", temporary.rawValue)
            assertEquals(200f, temporary.stores.single().radiusMeters, 0f)
            await { walletNotifications().any { it.tag == temporary.id } && cardNotification() != null }
            val realNotificationPostTime = cardNotification()!!.postTime
            val previousUpdate = settings.lastNearbyUpdateAtMillis
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitForegroundRefreshFinished(previousUpdate)
                if (compose.onAllNodesWithTag("nearby_card_${temporary.id}").fetchSemanticsNodes()
                        .isEmpty()
                ) {
                    compose.onNodeWithTag("nearby_card_pager").performTouchInput { swipeLeft() }
                }
                compose.onNodeWithTag("nearby_card_${temporary.id}").assertIsDisplayed()
                screenshot("temporary-test-card-home.png")
                compose.onNodeWithTag("nearby_card_${temporary.id}").performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithContentDescription("Enlarge Test code")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
                }
                // Verify the rendered checkout code, rather than trusting the title or intent.
                onView(activeBarcode()).check { view, error ->
                    if (error != null) throw error
                    val image = view as android.widget.ImageView
                    val bitmap = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val decoded = com.google.zxing.MultiFormatReader().decode(
                        com.google.zxing.BinaryBitmap(
                            com.google.zxing.common.HybridBinarizer(
                                com.google.zxing.RGBLuminanceSource(
                                    bitmap.width, bitmap.height, pixels
                                )
                            )
                        )
                    )
                    assertEquals("1234567890", decoded.text)
                    assertEquals(com.google.zxing.BarcodeFormat.CODE_128, decoded.barcodeFormat)
                }
                screenshot("temporary-test-card-detail.png")
                compose.onNodeWithText("Delete test card").performClick()
                compose.onNodeWithText("Delete").performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag("wallet_cards")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
                }
                await {
                    settings.cards().none { it.id == temporary.id } &&
                            walletNotifications().none { it.tag == temporary.id }
                }
                compose.waitForIdle()
                compose.onNodeWithTag("nearby_card_${temporary.id}").assertDoesNotExist()
                compose.onNodeWithTag("wallet_card_${temporary.id}").assertDoesNotExist()
                assertNearbyCard("fixture-one", "Example One")
                assertEquals(
                    "Deleting a temporary card must preserve the existing loyalty notification",
                    realNotificationPostTime, cardNotification()!!.postTime
                )
                assertEquals(
                    "Real memberships and their store radii must remain unchanged",
                    originalCards,
                    repository.cards()
                )
                assertFalse(settings.stores().any { store -> store.id == "test" })
            }
        }

    @Test
    fun cachedRefreshRecoversCardsAndClearsThemOutsideWithoutReplacingValidFences() =
        withLocationFixture {
            withoutBackgroundDelivery {
                moveTo(atStore())
                runBlocking { manager.enable() }
                assertTrue(
                    "The test must establish real Play services registrations",
                    manager.hasCurrentRegistration()
                )
                val generation = settings.generation
                await { cardNotification() != null }

                // Android 37 sheds rapid duplicate cancellations across test cleanup.
                // Model a later dismissal, after the notification API rate limiter settles.
                SystemClock.sleep(2_000)
                systemNotifications.cancel(card.id, CardNotificationManager.ID)
                await { cardNotification() == null }
                runBlocking { manager.refreshNearby(force = false) }
                await { cardNotification() != null }
                assertEquals(setOf(card.id), settings.activeCards().map { it.id }.toSet())
                assertEquals(generation, settings.generation)
                val postTime = cardNotification()!!.postTime

                val previousUpdate = settings.lastNearbyUpdateAtMillis
                SystemClock.sleep(30)
                runBlocking { manager.refreshNearby(force = false) }
                assertTrue(
                    "Only a successful check advances the last update time",
                    settings.lastNearbyUpdateAtMillis > previousUpdate
                )
                assertTrue(settings.lastNearbyUpdateSource.isNotBlank())
                assertEquals(
                    "Refreshing an existing card must preserve its post time and expanded state",
                    postTime, cardNotification()!!.postTime
                )
                assertEquals(generation, settings.generation)

                moveTo(outside())
                runBlocking { manager.refreshNearby(force = false) }
                await { walletNotifications().isEmpty() }
                assertTrue(settings.inside.isEmpty())
                assertEquals(
                    "A short trip must keep the region's existing registrations",
                    generation,
                    settings.generation
                )
                assertTrue(manager.hasCurrentRegistration())
                val lastSuccessfulUpdate = settings.lastNearbyUpdateAtMillis
                runBlocking {
                    manager.disable()
                    manager.refreshNearby(force = false)
                }
                assertEquals(
                    "A skipped refresh must not claim that nearby stores were updated",
                    lastSuccessfulUpdate, settings.lastNearbyUpdateAtMillis
                )
            }
        }

    @Test
    fun backgroundPresenceAndResumeUseSharedNearbyStateWithoutAnotherLocationCheck() =
        withLocationFixture {
            moveTo(outside())
            runBlocking { manager.enable() }
            deferBackgroundEvents()
            val generation = settings.generation
            val previousUpdate = settings.lastNearbyUpdateAtMillis
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                awaitForegroundRefreshFinished(previousUpdate)
                assertHomeHasNoScannableCode()
                activity.moveToState(Lifecycle.State.STARTED)
                moveTo(atStore())
                runBlocking { manager.refreshNearby(force = false) }
                await { cardNotification() != null }
                val arrivalUpdate = settings.lastNearbyUpdateAtMillis
                activity.moveToState(Lifecycle.State.RESUMED)
                awaitHome()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("nearby_card_fixture-one").fetchSemanticsNodes()
                        .isNotEmpty()
                }
                assertNearbyCard("fixture-one", "Example One")
                assertEquals(
                    "Resume must not start another location check",
                    arrivalUpdate,
                    settings.lastNearbyUpdateAtMillis
                )
                moveTo(outside())
                runBlocking { manager.refreshNearby(force = false) }
                await { walletNotifications().isEmpty() }
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("nearby_card_fixture-one").fetchSemanticsNodes()
                        .isEmpty()
                }
                assertHomeHasNoScannableCode()
                val departureUpdate = settings.lastNearbyUpdateAtMillis
                activity.moveToState(Lifecycle.State.STARTED)
                activity.moveToState(Lifecycle.State.RESUMED)
                awaitHome()
                assertHomeHasNoScannableCode()
                assertEquals(departureUpdate, settings.lastNearbyUpdateAtMillis)
                assertTrue(settings.inside.isEmpty())
                assertEquals(
                    "A short trip must preserve valid registrations",
                    generation,
                    settings.generation
                )
            }
        }

    @Test
    fun partialPullShowsNativeGestureHintWithoutRequestingLocation() = withLocationFixture {
        moveTo(atStore())
        runBlocking { manager.enable() }
        deferBackgroundEvents()
        val previousUpdate = settings.lastNearbyUpdateAtMillis
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitForegroundRefreshFinished(previousUpdate)
            val completedUpdate = settings.lastNearbyUpdateAtMillis
            compose.onNodeWithText("Refresh nearby stores").assertDoesNotExist()
            val gestureHint = SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo
            )
            compose.onAllNodes(gestureHint).assertCountEquals(0)
            compose.onNodeWithTag("wallet_cards").performTouchInput {
                down(androidx.compose.ui.geometry.Offset(centerX, 24f))
                moveBy(
                    androidx.compose.ui.geometry.Offset(
                        0f,
                        120f * context.resources.displayMetrics.density
                    ),
                    delayMillis = 300
                )
            }
            compose.onNode(gestureHint).assertIsDisplayed()
            compose.onNodeWithTag("wallet_loading").assertDoesNotExist()
            assertEquals(completedUpdate, settings.lastNearbyUpdateAtMillis)
            screenshot("nearby-pull-hint.png")
            compose.onNodeWithTag("wallet_cards").performTouchInput { up() }
            compose.waitForIdle()
            compose.onAllNodes(gestureHint).assertCountEquals(0)
            compose.onNodeWithTag("wallet_loading").assertDoesNotExist()
            assertEquals(
                "Releasing before the threshold must not request GPS", completedUpdate,
                settings.lastNearbyUpdateAtMillis
            )
        }
    }

    @Test
    fun reopeningRestoresCompletedCardsWithoutLocationLoadingOrAnotherCheck() =
        withLocationFixture {
            moveTo(atStore())
            runBlocking { manager.enable() }
            deferBackgroundEvents()
            val previousUpdate = settings.lastNearbyUpdateAtMillis
            var savedUpdate = 0L
            var savedPostTime = 0L
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                awaitForegroundRefreshFinished(previousUpdate)
                assertNearbyCard("fixture-one", "Example One")
                savedUpdate = settings.lastNearbyUpdateAtMillis
                savedPostTime = cardNotification()!!.postTime
                activity.recreate()
                awaitHome()
                compose.onNodeWithTag("wallet_loading").assertDoesNotExist()
                assertNearbyCard("fixture-one", "Example One")
                assertEquals(savedUpdate, settings.lastNearbyUpdateAtMillis)
                compose.onNodeWithContentDescription("Settings").performClick()
                backToHomeWithoutRefresh()
                assertNearbyCard("fixture-one", "Example One")
            }
            // A new Activity has a new ViewModel. Only the persisted completed result
            // can show the old card while the current fused fix is somewhere else.
            moveTo(outside())
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitHome()
                compose.onNodeWithTag("wallet_loading").assertDoesNotExist()
                assertNearbyCard("fixture-one", "Example One")
                assertEquals(
                    "Cached reopening must not request a new location",
                    savedUpdate,
                    settings.lastNearbyUpdateAtMillis
                )
                assertEquals(
                    "Reopening must not repost the existing card",
                    savedPostTime,
                    cardNotification()!!.postTime
                )
                compose.onNodeWithTag("nearby_card_fixture-one").performClick()
                awaitViewer("Example One")
                backToHomeWithoutRefresh()
                assertNearbyCard("fixture-one", "Example One")
                assertEquals(savedUpdate, settings.lastNearbyUpdateAtMillis)
            }
        }

    @Test
    fun resumeChecksStalePresenceEvenWithAValidRegistration() = withLocationFixture {
        moveTo(outside())
        runBlocking { manager.enable() }
        deferBackgroundEvents()
        val previousUpdate = settings.lastNearbyUpdateAtMillis
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            awaitForegroundRefreshFinished(previousUpdate)
            val generation = settings.generation
            for (arriving in listOf(true, false)) {
                activity.moveToState(Lifecycle.State.STARTED)
                moveTo(if (arriving) atStore() else outside())
                context.getSharedPreferences("local_geofences", Context.MODE_PRIVATE).edit()
                    .putLong("last_nearby_update_at", System.currentTimeMillis() - 360_000).commit()
                val staleUpdate = settings.lastNearbyUpdateAtMillis
                assertTrue(manager.hasCurrentRegistration())
                activity.moveToState(Lifecycle.State.RESUMED)
                awaitForegroundRefreshFinished(staleUpdate)
                assertEquals(arriving, settings.activeCards().any { it.id == card.id })
                assertEquals(generation, settings.generation)
                if (arriving) {
                    assertNearbyCard(card.id, card.brandName)
                    await { cardNotification() != null }
                } else {
                    compose.onNodeWithTag("nearby_card_deck").assertDoesNotExist()
                    await { cardNotification() == null }
                }
            }
        }
    }

    @Test
    fun resumeRepairsInvalidRegistrationUsingTheSharedLocationEngine() = withLocationFixture {
        moveTo(atStore())
        runBlocking { manager.enable() }
        deferBackgroundEvents()
        val previousUpdate = settings.lastNearbyUpdateAtMillis
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            awaitForegroundRefreshFinished(previousUpdate)
            assertNearbyCard("fixture-one", "Example One")
            val generation = settings.generation
            val updatedAt = settings.lastNearbyUpdateAtMillis
            activity.moveToState(Lifecycle.State.STARTED)
            settings.invalidateRegistration()
            activity.moveToState(Lifecycle.State.RESUMED)
            awaitForegroundRefreshFinished(updatedAt)
            assertNearbyCard("fixture-one", "Example One")
            assertTrue(manager.hasCurrentRegistration())
            assertNotEquals(
                "An obsolete receipt must be replaced on resume",
                generation,
                settings.generation
            )
            assertNotNull(cardNotification())
        }
    }

    @Test
    fun onlyNearbySecondCardShowsOneCardAtHomeAndOpensTheSameFocusedViewer() = withLocationFixture {
        val secondCard = LocalCardRepository.get(context).cards().first { it.id == "fixture-two" }
        val store = secondCard.stores.first { it.id == "fixture-two-store" }
        moveTo(fix(store.latitude, store.longitude))
        runBlocking { manager.enable() }
        deferBackgroundEvents()
        val previousUpdate = settings.lastNearbyUpdateAtMillis
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitForegroundRefreshFinished(previousUpdate)
            assertEquals(setOf(secondCard.id), settings.activeCards().map { it.id }.toSet())
            assertNearbyCard("fixture-two", "Example Two")
            compose.onNodeWithTag("previous_nearby_card").assertDoesNotExist()
            compose.onNodeWithTag("next_nearby_card").assertDoesNotExist()
            compose.onNodeWithTag("wallet_card_fixture-one").assertIsDisplayed()
            val row =
                compose.onNodeWithTag("wallet_card_fixture-one").fetchSemanticsNode().boundsInRoot
            val wallet = compose.onNodeWithTag("wallet_cards").fetchSemanticsNode().boundsInRoot
            val density = context.resources.displayMetrics.density
            assertEquals(
                "Compact cards use a centered reading column",
                wallet.center.x,
                row.center.x,
                1f
            )
            assertEquals(
                "Compact cards retain gutters and stop growing on wide screens",
                minOf(wallet.width - 40f * density, 480f * density), row.width, 1f
            )
            screenshot("nearby-single-home.png")
            compose.onNodeWithTag("nearby_card_fixture-two").performClick()
            awaitViewer("Example Two")
            backToHomeWithoutRefresh()
            assertNearbyCard("fixture-two", "Example Two")
        }
    }

    @Test
    fun overlappingStoresKeepSelectedCardWhenRefreshChangesDistanceOrder() = withLocationFixture {
        val repository = LocalCardRepository.get(context)
        val secondCard = repository.cards().first { it.id == "fixture-two" }
        val firstCardCenter = atStore()
        val point = fix(firstCardCenter.latitude + 0.00045, firstCardCenter.longitude)
        val overlap = StoreLocation(
            "foreground-overlap-test", "Example Two overlap test",
            point.latitude, point.longitude, 150f
        )
        repository.saveStore(secondCard.id, overlap)
        try {
            moveTo(point)
            runBlocking { manager.enable() }
            deferBackgroundEvents()
            var previousUpdate = settings.lastNearbyUpdateAtMillis
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitForegroundRefreshFinished(previousUpdate)
                assertEquals(
                    setOf(card.id, secondCard.id),
                    settings.activeCards().map { it.id }.toSet()
                )
                assertNearbyCard("fixture-two", "Example Two")
                screenshot("nearby-overlap-home.png")
                val originalBounds = compose.onNodeWithTag("nearby_card_fixture-two")
                    .fetchSemanticsNode().boundsInRoot
                // Choosing a page explicitly protects that membership from subsequent
                // distance changes, even while both store circles overlap.
                compose.onNodeWithTag("nearby_card_pager").performTouchInput { swipeLeft() }
                assertNearbyCard("fixture-one", "Example One")
                compose.onNodeWithTag("nearby_card_pager").performTouchInput { swipeRight() }
                assertNearbyCard("fixture-two", "Example Two")
                compose.onNodeWithTag("nearby_card_fixture-two").performClick()
                awaitViewer("Example Two")

                moveTo(atStore())
                runBlocking { manager.refreshNearby(force = false) }
                assertEquals(
                    setOf(card.id, secondCard.id),
                    settings.activeCards().map { it.id }.toSet()
                )
                assertOnlyCode("Example Two")
                backToHomeWithoutRefresh()
                assertNearbyCard("fixture-two", "Example Two")
                assertEquals(
                    "Returning must preserve the focused card's position and size",
                    originalBounds, compose.onNodeWithTag("nearby_card_fixture-two")
                        .fetchSemanticsNode().boundsInRoot
                )

                previousUpdate = settings.lastNearbyUpdateAtMillis
                pullToRefreshWithFreshFix()
                awaitForegroundRefreshFinished(previousUpdate)
                compose.onNodeWithTag("wallet_cards").performScrollToIndex(0)
                assertNearbyCard("fixture-one", "Example One")
                compose.onNodeWithTag("nearby_card_pager").performTouchInput { swipeLeft() }
                assertNearbyCard("fixture-two", "Example Two")
            }
            val lastCompletedCheck = settings.lastNearbyUpdateAtMillis
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitHome()
                assertNearbyCard("fixture-two", "Example Two")
                assertEquals(
                    "The selected deck page must survive a new Activity without refreshing location",
                    lastCompletedCheck, settings.lastNearbyUpdateAtMillis
                )
            }
        } finally {
            repository.removeStore(secondCard.id, overlap.id)
        }
    }

    @Test
    fun departingSelectedStoreRevealsRemainingNearbyCardAndLastDepartureClearsDeck() =
        withLocationFixture {
            val repository = LocalCardRepository.get(context)
            val secondCard = repository.cards().first { it.id == "fixture-two" }
            val center = atStore()
            val overlap = StoreLocation(
                "foreground-pin-test", "Example Two overlap test",
                center.latitude + 0.00072, center.longitude, 100f
            )
            repository.saveStore(secondCard.id, overlap)
            try {
                moveTo(center)
                runBlocking { manager.enable() }
                deferBackgroundEvents()
                val previousUpdate = settings.lastNearbyUpdateAtMillis
                ActivityScenario.launch(MainActivity::class.java).use {
                    awaitForegroundRefreshFinished(previousUpdate)
                    assertNearbyCard("fixture-one", "Example One")
                    compose.onNodeWithTag("nearby_card_pager").performTouchInput { swipeLeft() }
                    assertNearbyCard("fixture-two", "Example Two")
                    moveTo(fix(center.latitude - 0.00050, center.longitude))
                    runBlocking { manager.refreshNearby(force = false) }
                    await { settings.activeCards().map { it.id }.toSet() == setOf(card.id) }
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithTag("nearby_card_fixture-two").fetchSemanticsNodes()
                            .isEmpty()
                    }
                    assertNearbyCard("fixture-one", "Example One")
                    moveTo(outside())
                    runBlocking { manager.refreshNearby(force = false) }
                    await { settings.activeCards().isEmpty() }
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithTag("nearby_card_deck").fetchSemanticsNodes()
                            .isEmpty()
                    }
                    assertHomeHasNoScannableCode()
                    compose.onNodeWithTag("wallet_card_fixture-two").assertIsDisplayed()
                }
            } finally {
                repository.removeStore(secondCard.id, overlap.id)
            }
        }

    @Test
    fun firstUncachedLocationCheckShowsOnlyLoadingThenPublishesCompletedNearbyCards() =
        withLocationFixture {
            moveTo(outside())
            runBlocking { manager.enable() }
            deferBackgroundEvents()
            // Let the real fused cache age; historical mock timestamps are rejected.
            SystemClock.sleep(5_500)
            val previousUpdate = settings.lastNearbyUpdateAtMillis
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitLoading()
                assertEquals(
                    "A pending fix cannot claim a completed location check",
                    previousUpdate,
                    settings.lastNearbyUpdateAtMillis
                )
                screenshot("nearby-initial-loading.png")
                compose.mainClock.autoAdvance = false
                try {
                    moveTo(atStore())
                    await { settings.lastNearbyUpdateAtMillis > previousUpdate }
                    compose.waitUntil(5_000) {
                        compose.mainClock.advanceTimeByFrame()
                        compose.onAllNodesWithTag("wallet_cards")
                            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
                    }
                    // A finished fix reveals the new result through the outgoing
                    // globe, rather than replacing the entire screen in one frame.
                    compose.mainClock.advanceTimeBy(80)
                    compose.onNodeWithTag("wallet_loading").assertExists()
                    compose.onNodeWithTag("wallet_cards").assertExists()
                    compose.mainClock.advanceTimeBy(300)
                    compose.onNodeWithTag("wallet_loading").assertDoesNotExist()
                } finally {
                    compose.mainClock.autoAdvance = true
                }
                awaitForegroundRefreshFinished(previousUpdate)
                assertEquals(setOf(card.id), settings.activeCards().map { it.id }.toSet())
                assertNearbyCard("fixture-one", "Example One")
                compose.onNodeWithTag("wallet_card_fixture-two").assertIsDisplayed()
                compose.onNodeWithTag("wallet_loading").assertDoesNotExist()
            }
        }

    @Test
    fun pullIgnoresEvenRecentCachedFixAndWaitsForNewLocationBeforePublishing() =
        withLocationFixture {
            moveTo(atStore())
            runBlocking { manager.enable() }
            deferBackgroundEvents()
            val beforeLaunch = settings.lastNearbyUpdateAtMillis
            val secondCard =
                LocalCardRepository.get(context).cards().first { it.id == "fixture-two" }
            val secondCardStore = secondCard.stores.first { it.id == "fixture-two-store" }
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                awaitForegroundRefreshFinished(beforeLaunch)
                assertNearbyCard("fixture-one", "Example One")
                // This cache is deliberately fresh. A manual check must still obtain
                // a fix produced after its request, rather than reuse this Example One position.
                moveTo(atStore())
                assertTrue(
                    SystemClock.elapsedRealtimeNanos() -
                            checkNotNull(fused.lastLocation.completed()).elapsedRealtimeNanos < TimeUnit.SECONDS.toNanos(
                        5
                    )
                )
                val beforePull = settings.lastNearbyUpdateAtMillis
                pullToRefresh()
                awaitLoading()
                assertEquals(beforePull, settings.lastNearbyUpdateAtMillis)
                // Configuration recreation must keep the same pending check and full-page loader.
                activity.recreate()
                awaitLoading()
                moveTo(fix(secondCardStore.latitude, secondCardStore.longitude))
                awaitForegroundRefreshFinished(beforePull)
                assertNearbyCard("fixture-two", "Example Two")
                compose.onNodeWithTag("wallet_card_fixture-one").assertIsDisplayed()
                compose.onNodeWithTag("nearby_card_fixture-one").assertDoesNotExist()
                compose.onNodeWithTag("wallet_loading").assertDoesNotExist()
                screenshot("nearby-after-refresh.png")
            }
        }

    @Test
    fun failedLocationCheckKeepsCompletedCardsAndDoesNotAdvanceLastSuccessfulUpdate() =
        withLocationFixture {
            moveTo(atStore())
            runBlocking { manager.enable() }
            deferBackgroundEvents()
            val beforeLaunch = settings.lastNearbyUpdateAtMillis
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitForegroundRefreshFinished(beforeLaunch)
                assertNearbyCard("fixture-one", "Example One")
                // Keep the shared presence intact while removing automatic delivery,
                // so this failure exercises the user’s one-shot check alone.
                deferBackgroundEvents()
                val previousUpdate = settings.lastNearbyUpdateAtMillis
                val locationManager =
                    context.getSystemService(android.location.LocationManager::class.java)
                try {
                    shell("cmd location set-location-enabled false")
                    await { !locationManager.isLocationEnabled }
                    val failure =
                        runCatching { runBlocking { manager.refreshNearby() } }.exceptionOrNull()
                    assertNotNull(
                        "Disabled device location must not be reported as a successful refresh",
                        failure
                    )
                    assertEquals(previousUpdate, settings.lastNearbyUpdateAtMillis)
                    pullToRefresh()
                    awaitHome()
                    compose.onNodeWithTag("wallet_loading").assertDoesNotExist()
                    assertNearbyCard("fixture-one", "Example One")
                    compose.onNodeWithTag("wallet_cards").performScrollToNode(
                        hasText("Turn on device Location", substring = true)
                    )
                    compose.onNodeWithText("Turn on device Location", substring = true)
                        .assertIsDisplayed()
                    assertEquals(
                        "A failed pull must retain the previous successful check time",
                        previousUpdate, settings.lastNearbyUpdateAtMillis
                    )
                } finally {
                    restoreDeviceLocation()
                }
            }
        }

    @Test
    fun aNearbyUpdateCannotReplaceTheMembershipAlreadyChosenByTheUser() = withLocationFixture {
        moveTo(outside())
        runBlocking { manager.enable() }
        deferBackgroundEvents()
        val beforeLaunch = settings.lastNearbyUpdateAtMillis
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitForegroundRefreshFinished(beforeLaunch)
            compose.onNodeWithText("Example Two Card").performClick()
            awaitViewer("Example Two")
            val selectedTop = compose.onNodeWithContentDescription("Enlarge Example Two code")
                .fetchSemanticsNode().boundsInRoot.top
            moveTo(atStore())
            // Presence can change independently while CardActivity owns the screen.
            runBlocking { manager.refreshNearby(force = false) }
            await { cardNotification() != null }
            assertEquals(setOf(card.id), settings.activeCards().map { it.id }.toSet())
            assertOnlyCode("Example Two")
            assertEquals(
                "Nearby updates must not move the chosen membership",
                selectedTop, compose.onNodeWithContentDescription("Enlarge Example Two code")
                    .fetchSemanticsNode().boundsInRoot.top, 1f
            )
            backToHomeWithoutRefresh()
            compose.onNodeWithTag("nearby_card_fixture-one").assertIsDisplayed()
            compose.onNodeWithTag("wallet_card_fixture-two").assertIsDisplayed()
        }
    }

    private fun restoreDeviceLocation() {
        shell("cmd location set-location-enabled true")
        // The system switch changes before Play services is able to re-register.
        // A queued location-off broadcast can also follow an initial successful check.
        var readySince = 0L
        await {
            val ready = runCatching {
                LocationServices.getSettingsClient(context).checkLocationSettings(
                    LocationSettingsRequest.Builder().addLocationRequest(
                        LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000)
                            .build()
                    ).build()
                ).completed()
            }.isSuccess
            if (!ready) readySince = 0L
            else if (readySince == 0L) readySince = SystemClock.elapsedRealtime()
            ready && SystemClock.elapsedRealtime() - readySince >= 500
        }
    }

    private fun activeBarcode() = object : org.hamcrest.TypeSafeMatcher<android.view.View>() {
        override fun matchesSafely(view: android.view.View) =
            view.id == R.id.barcode && view.isActivated

        override fun describeTo(description: org.hamcrest.Description) {
            description.appendText("the activated checkout barcode")
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = java.io.File(context.getExternalFilesDir(null), name)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream()
            .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun assertHomeHasNoScannableCode() {
        compose.onNodeWithTag("wallet_cards").assertIsDisplayed()
        compose.onNodeWithContentDescription("Enlarge Example One code").assertDoesNotExist()
        compose.onNodeWithContentDescription("Enlarge Example Two code").assertDoesNotExist()
        onView(activeBarcode()).check(doesNotExist())
    }

    private fun awaitViewer(brand: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Enlarge $brand code")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        assertOnlyCode(brand)
    }

    private fun assertOnlyCode(brand: String) {
        compose.onNodeWithContentDescription("Enlarge $brand code").assertIsDisplayed()
        val otherBrand = if (brand == "Example One") "Example Two" else "Example One"
        compose.onNodeWithContentDescription("Enlarge $otherBrand code").assertDoesNotExist()
        onView(activeBarcode()).check(matches(withContentDescription("$brand QR membership code")))
    }

    private fun backToHomeWithoutRefresh() {
        val previousUpdate = LocationSettings(context).lastNearbyUpdateAtMillis
        androidx.test.espresso.Espresso.pressBack()
        awaitHome()
        compose.onNodeWithTag("wallet_loading").assertDoesNotExist()
        assertEquals(
            "Returning from another app screen must use the completed home result",
            previousUpdate, LocationSettings(context).lastNearbyUpdateAtMillis
        )
    }

    private fun awaitHome() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("wallet_cards")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() &&
                    compose.onAllNodesWithTag("wallet_loading")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        compose.onNodeWithTag("wallet_cards").assertIsDisplayed()
    }

    private fun awaitLoading() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("wallet_loading")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithTag("wallet_loading").assertIsDisplayed()
        compose.onNodeWithText("Finding nearby stores").assertIsDisplayed()
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo
            )
        ).assertCountEquals(0)
        compose.onNodeWithTag("wallet_cards").assertDoesNotExist()
        onView(activeBarcode()).check(doesNotExist())
    }

    private fun pullToRefreshWithFreshFix() {
        pullToRefresh()
        awaitLoading()
        supplyFreshFixUntilHome()
    }

    private fun supplyFreshFixUntilHome() {
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val target = checkNotNull(fused.lastLocation.completed())
        val deadline = SystemClock.elapsedRealtime() + 10_000
        // The loader can draw before Play services has subscribed. Repeat a bounded
        // fresh fix until it responds, synchronously, with no mock stream left running.
        do {
            fused.setMockLocation(fix(target.latitude, target.longitude)).completed()
            SystemClock.sleep(100)
            if (compose.onAllNodesWithTag("wallet_cards")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            ) {
                awaitHome()
                return
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("The manual refresh did not finish after receiving fresh mock fixes")
    }

    private fun pullToRefresh() {
        compose.onNodeWithTag("wallet_cards").performScrollToIndex(0)
        compose.onNodeWithTag("wallet_cards").performTouchInput {
            swipeDown(startY = 24f, endY = height * .8f, durationMillis = 450)
        }
    }

    private fun assertNearbyCard(id: String, brand: String) {
        compose.waitForIdle()
        compose.onNodeWithTag("wallet_cards").assertIsDisplayed()
        compose.onNodeWithTag("nearby_card_deck").assertIsDisplayed()
        val deck = compose.onNodeWithTag("nearby_card_deck").fetchSemanticsNode().boundsInRoot
        val wallet = compose.onNodeWithTag("wallet_cards").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Nearby cards must remain readable on larger screens without stretching",
            deck.width <= 468f * context.resources.displayMetrics.density + 1f
        )
        assertEquals(
            "The carousel uses the available width up to its card and gutter cap",
            minOf(wallet.width, 468f * context.resources.displayMetrics.density), deck.width, 1f
        )
        val face = compose.onNodeWithTag("nearby_card_$id").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "The actual card remains no wider than 420 dp",
            face.width <= 420f * context.resources.displayMetrics.density + 1f
        )
        assertEquals(
            "The centered card leaves equal 24 dp gutters",
            48f * context.resources.displayMetrics.density, deck.width - face.width, 1f
        )
        assertEquals(
            "The nearby card must be centered in the wallet",
            wallet.center.x,
            deck.center.x,
            1f
        )
        compose.onNodeWithTag("previous_nearby_card").assertDoesNotExist()
        compose.onNodeWithTag("next_nearby_card").assertDoesNotExist()
        compose.onNodeWithTag("nearby_card_$id").assertIsDisplayed()
        // Exactly one Android barcode view must be present; Espresso rejects an
        // ambiguous match if a hidden page accidentally exposes a second code.
        onView(activeBarcode()).check(matches(withContentDescription("$brand QR membership code")))
    }

    private fun awaitForegroundRefreshFinished(previousUpdate: Long) {
        // An unchanged notification cannot prove this particular opening checked location.
        await { LocationSettings(context).lastNearbyUpdateAtMillis > previousUpdate }
        awaitHome()
        compose.onNodeWithTag("wallet_loading").assertDoesNotExist()
        compose.waitForIdle()
    }

    private inner class LocationFixture {
        val manager = GeofenceCoordinator(context)
        val settings = LocationSettings(context)
        val systemNotifications = context.getSystemService(NotificationManager::class.java)
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val card = LocalCardRepository.get(context).cards().first { it.id == "fixture-one" }
        private val store = card.stores.first { it.id == "fixture-one-store" }

        fun atStore() = fix(store.latitude, store.longitude)
        fun outside() = fix(store.latitude + 0.02, store.longitude).also { location ->
            check(settings.stores().none {
                NearbyStores.distanceMeters(
                    location.latitude,
                    location.longitude,
                    it.latitude,
                    it.longitude
                ) <=
                        it.radiusMeters + location.accuracy
            }) { "The test's outside fix must be outside every saved circle" }
        }

        fun moveTo(location: Location) {
            fused.setMockLocation(location).completed()
            await {
                fused.lastLocation.completed()?.let {
                    it.distanceTo(location) < 1f &&
                            it.elapsedRealtimeNanos >= location.elapsedRealtimeNanos
                } == true
            }
        }

        fun walletNotifications() = systemNotifications.activeNotifications
            .filter { it.id == CardNotificationManager.ID }

        fun cardNotification() = walletNotifications().singleOrNull { it.tag == card.id }

        fun withoutBackgroundDelivery(test: () -> Unit) {
            val receiver = android.content.ComponentName(context, GeofenceReceiver::class.java)
            val packageManager = context.packageManager
            val previous = packageManager.getComponentEnabledSetting(receiver)
            // A late native ENTER can legitimately recover a dismissed card. Pause
            // delivery before registration to exercise manual recovery independently.
            packageManager.setComponentEnabledSetting(
                receiver,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            try {
                test()
            } finally {
                packageManager.setComponentEnabledSetting(
                    receiver, previous,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            }
        }

        fun deferBackgroundEvents() {
            // Model a trip whose background events have not arrived yet. Leave the
            // valid local receipt intact so only foreground location reconciliation
            // can fix presence; a coincidental Play services callback cannot pass the test.
            val delivery = checkNotNull(GeofenceCoordinator.deliveryIntent(context, create = false))
            LocationServices.getGeofencingClient(context).removeGeofences(delivery).completed()
            assertTrue(manager.hasCurrentRegistration())
        }
    }

    private fun withLocationFixture(test: LocationFixture.() -> Unit) {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) {
            "NearbyRefreshTest must run on an Android emulator."
        }
        val preferences = context.getSharedPreferences("local_geofences", Context.MODE_PRIVATE)
        val originalPreferences = preferences.all.toMap()
        val homePreferences =
            context.getSharedPreferences("wallet_home_snapshot", Context.MODE_PRIVATE)
        val originalHomePreferences = homePreferences.all.toMap()
        val fixture = LocationFixture()
        val originalNotifications = fixture.walletNotifications()
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val originalMockMode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_MOCK_LOCATION,
            Process.myUid(), context.packageName
        )
        check(
            fixture.manager.hasPreciseLocation() && fixture.manager.hasBackgroundLocation() &&
                    fixture.systemNotifications.areNotificationsEnabled()
        ) {
            "Grant Portemoné precise/background location and notification permissions on the test emulator."
        }
        var mockEnabled = false
        try {
            shell("appops set ${context.packageName} android:mock_location allow")
            fixture.fused.setMockMode(true).completed()
            mockEnabled = true
            fixture.settings.removeTestStore()
            homePreferences.edit().clear().commit()
            CardNotificationManager(context).hide()
            fixture.test()
        } finally {
            restore(homePreferences, originalHomePreferences)
            // Remove test registrations before restoring preferences: an old receipt
            // must never claim the test's Play services fences are the original batch.
            try {
                runBlocking { fixture.manager.disable() }
            } finally {
                try {
                    if (mockEnabled) fixture.fused.setMockMode(false).completed()
                } finally {
                    shell(
                        "appops set ${context.packageName} android:mock_location ${
                            modeName(
                                originalMockMode
                            )
                        }"
                    )
                    restore(preferences, originalPreferences)
                    fixture.settings.invalidateRegistration()
                    try {
                        if (fixture.settings.enabled) runBlocking { fixture.manager.restore() }
                    } finally {
                        CardNotificationManager(context).hide()
                        originalNotifications.forEach {
                            fixture.systemNotifications.notify(it.tag, it.id, it.notification)
                        }
                    }
                }
            }
        }
    }

    private fun fix(latitude: Double, longitude: Double) = Location("fused").apply {
        this.latitude = latitude; this.longitude = longitude
        accuracy = 5f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun <T> Task<T>.completed(): T = Tasks.await(this, 20, TimeUnit.SECONDS)

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        do {
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < end)
        fail("Timed out waiting for the location or notification service")
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                command
            )
        )
            .use { it.readBytes() }
    }

    private fun modeName(mode: Int): String = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "allow"
        AppOpsManager.MODE_IGNORED -> "ignore"
        AppOpsManager.MODE_ERRORED -> "deny"
        AppOpsManager.MODE_FOREGROUND -> "foreground"
        else -> "default"
    }

    private fun restore(preferences: SharedPreferences, original: Map<String, *>) {
        preferences.edit().clear().apply {
            original.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }.commit()
    }
}
