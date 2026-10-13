// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.app.KeyguardManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class CardLockPrivacyTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    private fun unlockDevice() {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        shell("input keyevent KEYCODE_WAKEUP")
        compose.waitUntil(8_000) {
            if (keyguard.isKeyguardLocked) {
                // Credential removal resets System UI asynchronously; retry once it accepts input.
                shell("input keyevent KEYCODE_MENU")
                false
            } else true
        }
    }

    private fun lockAndWake(scenario: ActivityScenario<CardActivity>) {
        shell("input keyevent KEYCODE_SLEEP")
        compose.waitUntil(8_000) { scenario.state == androidx.lifecycle.Lifecycle.State.CREATED }
        shell("input keyevent KEYCODE_WAKEUP")
        compose.waitUntil(8_000) { scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED }
        awaitCardFocus(scenario)
        instrumentation.uiAutomation.waitForIdle(500, 8_000)
    }

    private fun awaitCardFocus(scenario: ActivityScenario<CardActivity>) {
        // RESUMED can precede keyguard handing input focus back to the card window.
        compose.waitUntil(8_000) {
            var focused = false
            scenario.onActivity { focused = it.hasWindowFocus() }
            focused
        }
    }

    private fun openCardDetails(scenario: ActivityScenario<CardActivity>) {
        awaitCardFocus(scenario)
        compose.onNodeWithText("Card details").performClick()
    }

    private fun awaitPinEntry() {
        compose.waitUntil(8_000) {
            instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId("com.android.systemui:id/pinEntry")
                ?.any { it.isVisibleToUser && it.isEnabled } == true
        }
        // System UI can expose the PIN node while its entrance transition still owns input.
        instrumentation.uiAutomation.waitForIdle(500, 8_000)
    }

    private fun pressBackFromCard(scenario: ActivityScenario<CardActivity>) {
        awaitCardFocus(scenario)
        shell("input keyevent KEYCODE_BACK")
    }

    @Test
    fun cancelingSecureUnlockKeepsTheCodeAvailableAndDetailsClosed() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(!keyguard.isDeviceSecure) { "Use an emulator without an existing PIN" }
        val wasDisabled = shell("locksettings get-disabled") == "true"
        val repository = LocalCardRepository.get(context)
        val card = TestCard.card.copy(id = UUID.randomUUID().toString())
        val pin = "246813"
        try {
            shell("locksettings set-disabled false")
            unlockDevice()
            repository.saveCard(card)
            ActivityScenario.launch<CardActivity>(CardActivity.scanIntent(context, card))
                .use { scenario ->
                    check(shell("locksettings set-pin $pin").contains("set to"))
                    lockAndWake(scenario)
                    compose.waitUntil(8_000) { keyguard.isKeyguardLocked && keyguard.isDeviceSecure }
                    openCardDetails(scenario)
                    awaitPinEntry()
                    shell("input keyevent KEYCODE_BACK")
                    awaitCardFocus(scenario)
                    compose.waitUntil(8_000) {
                        compose.onAllNodesWithText("Screen brightness increased")
                            .fetchSemanticsNodes(atLeastOneRootRequired = false)
                            .isNotEmpty()
                    }
                    assertTrue(keyguard.isKeyguardLocked)
                    compose.onNodeWithTag("card_details").assertDoesNotExist()
                    compose.onNodeWithText("Screen brightness increased").assertIsDisplayed()
                    scenario.recreate()
                    compose.onNodeWithTag("card_details").assertDoesNotExist()
                    compose.onNodeWithText("Screen brightness increased").assertIsDisplayed()
                    scenario.onActivity { assertEquals(1f, it.window.attributes.screenBrightness) }
                    pressBackFromCard(scenario)
                    compose.waitUntil(8_000) { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
                    assertTrue(keyguard.isKeyguardLocked)
                }
        } finally {
            try {
                if (keyguard.isDeviceSecure) {
                    try {
                        if (keyguard.isKeyguardLocked) {
                            // Authenticate before removing the credential so System UI can finish dismissal.
                            shell("input keyevent KEYCODE_WAKEUP")
                            shell("wm dismiss-keyguard")
                            awaitPinEntry()
                            shell("input text $pin")
                            shell("input keyevent KEYCODE_ENTER")
                            compose.waitUntil(8_000) { !keyguard.isKeyguardLocked }
                        }
                    } finally {
                        check(shell("locksettings clear --old $pin").contains("cleared"))
                    }
                }
            } finally {
                try {
                    // Restoring the lock setting can show keyguard again; dismiss only afterward.
                    shell("locksettings set-disabled $wasDisabled")
                    unlockDevice()
                } finally {
                    repository.deleteCard(card.id)
                }
            }
        }
    }

    @Test
    fun plainNotificationClosesOnBackAfterRecreation() {
        val repository = LocalCardRepository.get(context)
        val card = TestCard.card.copy(
            id = UUID.randomUUID().toString(), barcodeType = BarcodeType.NONE,
            rawValue = "", displayText = ""
        )
        repository.saveCard(card)
        try {
            ActivityScenario.launch<CardActivity>(CardActivity.scanIntent(context, card))
                .use { scenario ->
                    scenario.recreate()
                    compose.onNodeWithText("Screen brightness increased").assertDoesNotExist()
                    scenario.onActivity { assertEquals(-1f, it.window.attributes.screenBrightness) }
                    pressBackFromCard(scenario)
                    compose.waitUntil(8_000) { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
                }
        } finally {
            repository.deleteCard(card.id)
        }
    }

    @Test
    fun lockedNotificationShowsBrightScannerAndDetailsRequireUnlockEvenAfterRelocking() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) {
            "Screen-lock regression requires an isolated emulator"
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(!keyguard.isDeviceSecure) { "Use an emulator without a PIN" }
        val wasDisabled = shell("locksettings get-disabled") == "true"
        val repository = LocalCardRepository.get(context)
        val settings = LocationSettings(context)
        val enabled = settings.enabled
        val card = TestCard.card.copy(
            id = UUID.randomUUID().toString(),
            brandName = "Privacy fixture", description = "Private membership detail",
            stores = listOf(
                StoreLocation(
                    "privacy-${UUID.randomUUID()}",
                    "Private branch",
                    55.0,
                    12.0
                )
            )
        )
        try {
            settings.enabled = false
            shell("locksettings set-disabled false")
            unlockDevice()
            repository.saveCard(card)
            ActivityScenario.launch<CardActivity>(
                CardActivity.scanIntent(context, card)
            ).use { scenario ->
                lockAndWake(scenario)
                compose.waitUntil(8_000) { keyguard.isKeyguardLocked }
                compose.onNodeWithText("Screen brightness increased").assertIsDisplayed()
                scenario.onActivity { assertEquals(1f, it.window.attributes.screenBrightness) }
                assertTrue(keyguard.isKeyguardLocked)
                compose.onNodeWithTag("card_details").assertDoesNotExist()
                compose.onNodeWithText("Private branch").assertDoesNotExist()
                compose.onNodeWithText("Private membership detail").assertDoesNotExist()
                compose.onNodeWithText("Edit", substring = false).assertDoesNotExist()
                openCardDetails(scenario)
                try {
                    compose.waitUntil(8_000) {
                        compose.onAllNodesWithText("Screen brightness increased")
                            .fetchSemanticsNodes()
                            .isEmpty()
                    }
                } catch (failure: ComposeTimeoutException) {
                    val events = runCatching {
                        AppDiagnostics.readEvents().lineSequence()
                            .filter { "[CardUnlock]" in it }.toList().takeLast(20)
                            .joinToString("\n")
                    }.getOrElse { "Unlock diagnostics unavailable: ${it.javaClass.simpleName}" }
                    throw AssertionError(
                        "Card details did not open; locked=${keyguard.isKeyguardLocked}\n$events",
                        failure
                    )
                }
                assertFalse(keyguard.isKeyguardLocked)
                awaitCardFocus(scenario)
                compose.onNodeWithTag("card_details")
                    .performScrollToNode(hasText("Private membership detail"))
                compose.onNodeWithText("Private membership detail").assertIsDisplayed()
                scenario.onActivity { assertEquals(-1f, it.window.attributes.screenBrightness) }
                lockAndWake(scenario)
                compose.waitUntil(8_000) {
                    keyguard.isKeyguardLocked && compose.onAllNodesWithTag("card_details")
                        .fetchSemanticsNodes().isEmpty()
                }
                compose.onNodeWithTag("card_details").assertDoesNotExist()
                compose.onNodeWithText("Screen brightness increased").assertIsDisplayed()
                compose.onNodeWithText("Private branch").assertDoesNotExist()
                compose.onNodeWithText("Private membership detail").assertDoesNotExist()
                openCardDetails(scenario)
                compose.waitUntil(8_000) {
                    !keyguard.isKeyguardLocked && compose.onAllNodesWithTag("card_details")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                awaitCardFocus(scenario)
                compose.onNodeWithTag("card_details")
                    .performScrollToNode(hasText("Private membership detail"))
                compose.onNodeWithText("Private membership detail").assertIsDisplayed()
                lockAndWake(scenario)
                compose.waitUntil(8_000) {
                    keyguard.isKeyguardLocked && compose.onAllNodesWithTag("card_details")
                        .fetchSemanticsNodes().isEmpty()
                }
                pressBackFromCard(scenario)
                compose.waitUntil(8_000) {
                    scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED
                }
                assertTrue(keyguard.isKeyguardLocked)
            }
        } finally {
            // Restoring the lock setting can show keyguard again; dismiss only afterward.
            shell("locksettings set-disabled $wasDisabled")
            unlockDevice()
            repository.deleteCard(card.id)
            settings.enabled = enabled
        }
    }
}
