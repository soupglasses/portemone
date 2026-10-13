// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The compact wallet and the focused scanner have distinct, predictable navigation. */
@RunWith(AndroidJUnit4::class)
class CardNavigationTest {
    @org.junit.Before
    fun establishMembershipFixtures() {
        val repository =
            LocalCardRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val ids = repository.cards().map { it.id }.toSet()
        TestMemberships.cards.filter { it.id !in ids }.forEach { repository.saveCard(it) }
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun choosingCardsOpensOnlySelectedCodeAndBackRestoresTheCompactWallet() =
        withAutomaticCardsOff {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("Example One Card", substring = false).assertIsDisplayed()
                compose.onNodeWithText("Example Two Card", substring = false).assertIsDisplayed()
                onView(withId(R.id.barcode)).check(doesNotExist())
                val firstCardTop = compose.onNodeWithTag("wallet_card_fixture-one")
                    .fetchSemanticsNode().boundsInRoot.top
                compose.onNodeWithText("Example One Card", substring = false).performClick()
                awaitViewer("Example One")
                onView(withId(R.id.barcode)).check(matches(withContentDescription("Example One QR membership code")))
                compose.onNodeWithContentDescription("Back").performClick()
                awaitHome()
                assertEquals(
                    "Back must restore the same home position", firstCardTop,
                    compose.onNodeWithTag("wallet_card_fixture-one")
                        .fetchSemanticsNode().boundsInRoot.top, 1f
                )
                onView(withId(R.id.barcode)).check(doesNotExist())

                compose.onNodeWithText("Example Two Card", substring = false).performClick()
                awaitViewer("Example Two")
                compose.onNodeWithContentDescription("Enlarge Example One code")
                    .assertDoesNotExist()
                onView(withId(R.id.barcode)).check(matches(withContentDescription("Example Two QR membership code")))
                compose.onNodeWithContentDescription("Back").performClick()
                awaitHome()
                onView(withId(R.id.barcode)).check(doesNotExist())
                compose.onNodeWithText("Example One Card").assertIsDisplayed()
                compose.onNodeWithText("Example Two Card").assertIsDisplayed()
            }
        }

    @Test
    fun focusedCardOpensScannerAndLocationsWithoutLosingTheSelectedMembership() =
        withAutomaticCardsOff {
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withId(R.id.barcode)).check(doesNotExist())
                compose.onNodeWithText("Example One Card", substring = false).performClick()
                awaitViewer("Example One")
                compose.onNodeWithText("Details", substring = false).assertDoesNotExist()
                compose.onNodeWithContentDescription("Open store map").assertDoesNotExist()
                compose.onNodeWithContentDescription("Enlarge Example One code").performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("Screen brightness increased")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
                }
                compose.onNodeWithText("Screen brightness increased").assertIsDisplayed()
                compose.onNodeWithText("Card details").performClick()
                awaitViewer("Example One")
                onView(withId(R.id.barcode)).check(matches(withContentDescription("Example One QR membership code")))
                compose.onNodeWithText("Details", substring = false).assertDoesNotExist()
                scrollDetailsTo(hasText("Store locations"))
                compose.onNodeWithText("Store locations").assertIsDisplayed()
                compose.onNodeWithContentDescription("Open store map").assertDoesNotExist()
                scrollDetailsTo(hasContentDescription("Enlarge Example One code"))
                onView(withId(R.id.barcode)).check(matches(withContentDescription("Example One QR membership code")))
                compose.onNodeWithContentDescription("Back").performClick()
                awaitHome()
                onView(withId(R.id.barcode)).check(doesNotExist())
            }
        }

    @Test
    fun notificationScannerAndItsDetailsDoNotReplaceLauncherOverview() = withAutomaticCardsOff {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { home ->
            for (showDetails in listOf(false, true)) {
                launchScannerFromHome(home)
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("Screen brightness increased")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
                }
                if (showDetails) {
                    compose.onNodeWithText("Card details").performClick()
                    awaitViewer("Example One")
                }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
                ).use { it.readBytes() }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(
                        "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                                "-f 0x10200000 -n dev.finnes.portemone/.MainActivity"
                    )
                ).use { it.readBytes() }
                awaitHome()
                compose.onNodeWithText("Screen brightness increased").assertDoesNotExist()
                compose.onNodeWithTag("card_details").assertDoesNotExist()
            }
            launchScannerFromHome(home)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Screen brightness increased")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onNodeWithContentDescription("Back").performClick()
            awaitHome()
        }
    }

    private fun launchScannerFromHome(home: ActivityScenario<MainActivity>) {
        awaitHome()
        // A resumed Activity and its Compose tree can precede the window becoming
        // foreground. Launch only after Android has given the wallet input focus.
        compose.waitUntil(8_000) {
            var focused = false
            home.onActivity { focused = it.hasWindowFocus() }
            focused
        }
        home.onActivity { activity ->
            activity.startActivity(CardActivity.scanIntent(activity, TestMemberships.cards.first()))
        }
    }

    private fun awaitViewer(brand: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Enlarge $brand code")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithContentDescription("Enlarge $brand code").assertIsDisplayed()
    }

    private fun awaitHome() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("wallet_cards")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithTag("wallet_cards").assertIsDisplayed()
    }

    private fun scrollDetailsTo(matcher: SemanticsMatcher) {
        compose.onNodeWithTag("card_details").performScrollToNode(matcher)
    }

    private fun withAutomaticCardsOff(test: () -> Unit) {
        val settings = LocationSettings(InstrumentationRegistry.getInstrumentation().targetContext)
        val wasEnabled = settings.enabled
        try {
            settings.enabled = false
            test()
        } finally {
            settings.enabled = wasEnabled
        }
    }
}
