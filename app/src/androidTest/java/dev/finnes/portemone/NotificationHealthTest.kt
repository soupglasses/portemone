// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.notifications.integrations.SamsungNowBar
import dev.finnes.portemone.notifications.integrations.PixelNotificationCompat

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.location.LocationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.Role
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
import java.io.File

/** Checks the setup journey against Android's real notification state, including return from Settings. */
@RequiresDeviceSetup("Changes Android settings and requires separate granted/blocked permission runs")
@RunWith(AndroidJUnit4::class)
class NotificationHealthTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Test
    fun optionalPixelHelperHealthFollowsRealSetupAndOpensItsHelp() {
        emulatorOnly()
        val settings = LocationSettings(context)
        val previous = settings.pixelCompatibilityEnabled
        try {
            settings.pixelCompatibilityEnabled = false
            ActivityScenario.launch(NotificationHealthActivity::class.java).use {
                compose.onNodeWithTag("health_pixel_helper").assertDoesNotExist()
            }
            settings.pixelCompatibilityEnabled = true
            val measured = PixelNotificationCompat(context).status()
            ActivityScenario.launch(NotificationHealthActivity::class.java).use {
                compose.onNodeWithTag("notification_health_list")
                    .performScrollToNode(hasTestTag("health_pixel_helper"))
                compose.onNodeWithTag("health_pixel_helper")
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.StateDescription,
                            if (measured.ready) "Verified" else "Needs attention"
                        )
                    )
                    .performClick()
                compose.onNode(
                    hasText(measured.description) and
                            hasAnyAncestor(hasTestTag("notification_health_help"))
                ).assertIsDisplayed()
                compose.onNodeWithText(if (measured.installed) "Set up full cards" else "Open notification settings")
                    .performScrollTo().assertIsDisplayed()
                val destination = NotificationHealth(context).intent(HealthDestination.PIXEL_HELPER)
                assertEquals(
                    if (measured.installed) PixelNotificationCompat.PACKAGE else context.packageName,
                    destination.component?.packageName
                )
                assertNotNull(
                    "Helper guidance must lead to an installed Activity",
                    context.packageManager.resolveActivity(
                        destination,
                        PackageManager.MATCH_DEFAULT_ONLY
                    )
                )
            }
        } finally {
            settings.pixelCompatibilityEnabled = previous
        }
    }

    /** Safe to select alone on a physical phone: no Activity, shell identity, or settings writes. */
    @Test
    fun readOnlySettingsProbeFromNormalApplicationUid() {
        val uid = android.os.Process.myUid()
        check(uid >= 10_000) { "The probe must run as an ordinary application UID, found $uid" }
        android.util.Log.i(
            "NotificationHealthProbe",
            "package=${context.packageName} uid=$uid manufacturer=${Build.MANUFACTURER} sdk=${Build.VERSION.SDK_INT}"
        )
        listOf(
            "enable_notification_nowbar_test", "lock_screen_show_notifications",
            "lock_screen_allow_private_notifications", "lock_screen_show_silent_notifications"
        ).forEach { key ->
            val result = runCatching { Settings.Secure.getString(context.contentResolver, key) }
                .fold({ it ?: "ABSENT" }, { "DENIED:${it.javaClass.simpleName}:${it.message}" })
            android.util.Log.i("NotificationHealthProbe", "Secure.$key=$result")
        }
        val result = runCatching {
            Settings.System.getString(
                context.contentResolver,
                "lockscreen_minimizing_notification"
            )
        }
            .fold({ it ?: "ABSENT" }, { "DENIED:${it.javaClass.simpleName}:${it.message}" })
        android.util.Log.i(
            "NotificationHealthProbe",
            "System.lockscreen_minimizing_notification=$result"
        )
        val ongoing = runCatching {
            notifications.javaClass.getMethod(
                "isOngoingActivityAllowed",
                String::class.java,
                Int::class.javaPrimitiveType
            )
                .invoke(notifications, context.packageName, uid)
        }.fold({ it?.toString() ?: "NULL" }, {
            val cause = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
            "UNAVAILABLE:${cause.javaClass.simpleName}:${cause.message}"
        })
        android.util.Log.i(
            "NotificationHealthProbe",
            "NotificationManager.isOngoingActivityAllowed=$ongoing"
        )
        if (SamsungNowBar.availableForTrial) {
            val raw = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    "enable_notification_nowbar_test"
                )
            }.getOrNull()
            val actual = NotificationSettingsProbe(context).samsungLive()
            assertEquals(
                "Samsung Live status must match the actual readable developer setting",
                raw == "1",
                actual.ready
            )
            assertEquals(
                "Missing or restricted settings must remain unverified",
                raw !in setOf("0", "1"),
                actual.unknown
            )
        }
        // Constructing the health model can create a missing notification channel. Skip
        // those probes on a brand-new installation to keep this selected test read-only.
        if (notifications.getNotificationChannel(CardNotificationManager.CHANNEL) != null) {
            val health = NotificationHealth(context)
            val basic = health.basic()
            assertEquals(
                notifications.areNotificationsEnabled(),
                basic.single { it.id == "notifications" }.ready
            )
            assertEquals(
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                basic.single { it.id == "precise" }.ready
            )
            assertEquals(
                context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED,
                basic.single { it.id == "background" }.ready
            )
            assertEquals(
                context.getSystemService(LocationManager::class.java).isLocationEnabled,
                basic.single { it.id == "location" }.ready
            )
            (basic + health.samsung()).forEach { item ->
                val intent = health.intent(item.destination)
                val target = context.packageManager.resolveActivity(
                    intent,
                    PackageManager.MATCH_DEFAULT_ONLY
                )?.activityInfo
                android.util.Log.i(
                    "NotificationHealthProbe",
                    "route.${item.id}: ${intent.action} -> ${target?.packageName}/${target?.name}; ready=${item.ready}; unknown=${item.unknown}"
                )
            }
        } else android.util.Log.i(
            "NotificationHealthProbe",
            "route probes skipped: card channel has not been created"
        )
    }

    @Test
    fun homeUsesPortemoneTitleAndSettingsCogLeadingToNotificationHealth() {
        val settings = LocationSettings(context)
        val enabled = settings.enabled
        try {
            settings.enabled = false
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("Portemoné", substring = false).assertIsDisplayed()
                compose.onNodeWithText("Wallet", substring = false).assertDoesNotExist()
                compose.onNodeWithText("Settings", substring = false).assertDoesNotExist()
                screenshot("portemone-home-settings-cog")
                compose.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
                compose.onNodeWithText("Notification health").performScrollTo().performClick()
                compose.onNodeWithTag("notification_health_list").assertIsDisplayed()
                compose.onNodeWithTag("health_notifications").assertIsDisplayed()
                screenshot("notification-health")
            }
        } finally {
            settings.enabled = enabled
        }
    }

    @Test
    fun automaticCardSetupInSettingsOpensChecklistBeforeEnabling() {
        val settings = LocationSettings(context)
        val enabled = settings.enabled
        try {
            settings.enabled = false
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("Set up automatic cards").assertDoesNotExist()
                compose.onNodeWithText("Automatic cards are on").assertDoesNotExist()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
                    .assertIsOff().performClick()
                compose.onNodeWithTag("notification_health_list").assertIsDisplayed()
                assertFalse(
                    "The Settings switch must enter setup before enabling",
                    settings.enabled
                )
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
                    .assertIsOff()
            }
        } finally {
            settings.enabled = enabled
        }
    }

    @Test
    fun settingsNotificationShortcutOpensThisAppsNotificationControls() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            compose.onNodeWithText("Android notification settings").performScrollTo().performClick()
            awaitSettings()
            await("Portemoné notification settings to load") { "Portemoné" in systemTexts() }
            assertTrue(
                "Shortcut must open the app notification switch",
                systemTexts().any { it.contains("Portemoné notifications") })
            shell("input keyevent KEYCODE_BACK")
            compose.onNodeWithText("Notification health").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun manufacturerControlsSamsungTabWithoutHidingBasicChecks() {
        ActivityScenario.launch(NotificationHealthActivity::class.java).use {
            compose.onNodeWithTag("health_notifications").assertIsDisplayed()
            if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                compose.onNodeWithText("Basic support").assertIsSelected()
                compose.onNodeWithText("Samsung", substring = false).performClick()
                    .assertIsSelected()
                compose.onNodeWithTag("health_samsung_integration").assertIsDisplayed()
                compose.onNodeWithText("Basic support").performClick()
                compose.onNodeWithTag("health_notifications").assertIsDisplayed()
            } else {
                compose.onNodeWithText("Samsung", substring = false).assertDoesNotExist()
                compose.onNodeWithTag("health_samsung_integration").assertDoesNotExist()
            }
        }
    }

    @Test
    fun lockScreenCheckReadsActualSettingsAndIgnoresLegacyConfirmation() {
        emulatorOnly()
        val key = "lock_screen_show_notifications"
        val original = secureValue(key)
        val silentKey = "lock_screen_show_silent_notifications"
        val originalSilent = secureValue(silentKey)
        val prefs = context.getSharedPreferences("notification_health", Context.MODE_PRIVATE)
        val originalConfirmation = prefs.getString("lock_screen", null)
        try {
            prefs.edit().putString("lock_screen", Build.FINGERPRINT).commit()
            shell("settings put secure $key 0")
            shell("settings put secure $silentKey 1")
            ActivityScenario.launch(NotificationHealthActivity::class.java).use {
                row("lock_screen").assert(status("Needs attention")).performClick()
                assertNoManualCertification()
                compose.onNodeWithText("Open Android settings").performScrollTo().performClick()
                awaitSettings()
                assertTrue(
                    "Lock-screen help must reach notification visibility, not the app list",
                    systemTexts().any {
                        it.contains("lock screen", ignoreCase = true)
                    })
                shell("settings put secure $key 1")
                shell("input keyevent KEYCODE_BACK")
                awaitHelpClosed()
                row("lock_screen").assert(status("Verified"))
            }
        } finally {
            restoreSecure(key, original)
            restoreSecure(silentKey, originalSilent)
            prefs.edit().apply {
                if (originalConfirmation == null) remove("lock_screen") else putString(
                    "lock_screen",
                    originalConfirmation
                )
            }.commit()
        }
    }

    @Test
    fun samsungLiveSetupOpensDeveloperOptionsWithoutPretendingToVerifyUnreadableState() {
        emulatorOnly()
        val originalDeveloperOptions =
            shell("settings get global development_settings_enabled").toString(Charsets.UTF_8)
                .trim()
        val prefs = context.getSharedPreferences("notification_health", Context.MODE_PRIVATE)
        val previousLegacy = prefs.getString("samsung_live", null)
        val previousAllApps = prefs.getString("samsung_live_all_apps", null)
        try {
            shell("settings put global development_settings_enabled 1")
            prefs.edit().putString("samsung_live", Build.FINGERPRINT)
                .putString("samsung_live_all_apps", Build.FINGERPRINT).commit()
            launchTask("samsung_live").use {
                compose.onNodeWithText("Live notifications for all apps", substring = false)
                    .assertIsDisplayed()
                compose.onNodeWithText("In Developer options, scroll to the Apps section.")
                    .assertIsDisplayed()
                compose.onNodeWithText("Allow notifications", substring = true).assertDoesNotExist()
                compose.onNodeWithText("Open Android settings").assertDoesNotExist()
                assertNoManualCertification()
                compose.onNodeWithText("Open Developer options").performScrollTo().performClick()
                awaitSettings()
                await("Developer options screen") { systemTexts().any { it == "Developer options" } }
                screenshot("samsung-live-developer-hint")
                shell("input keyevent KEYCODE_BACK")
                awaitUnverifiedAdvice()
                assertNoManualCertification()
            }
        } finally {
            prefs.edit().apply {
                if (previousLegacy == null) remove("samsung_live") else putString(
                    "samsung_live",
                    previousLegacy
                )
                if (previousAllApps == null) remove("samsung_live_all_apps") else putString(
                    "samsung_live_all_apps",
                    previousAllApps
                )
            }.commit()
            if (originalDeveloperOptions == "null") shell("settings delete global development_settings_enabled")
            else shell("settings put global development_settings_enabled $originalDeveloperOptions")
        }
    }

    @Test
    fun bothLocationPermissionHelpersOpenThisAppsSettingsAndRecheckActualGrants() {
        emulatorOnly()
        val arguments = InstrumentationRegistry.getArguments()
        listOf(
            Triple(
                "precise",
                Manifest.permission.ACCESS_FINE_LOCATION,
                arguments.getString("expectPreciseBlocked", "false").toBoolean()
            ),
            Triple(
                "background",
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                arguments.getString("expectBackgroundBlocked", "false").toBoolean()
            ),
        ).forEach { (id, permission, blocked) ->
            ActivityScenario.launch(NotificationHealthActivity::class.java).use {
                row(id).assert(status(if (blocked) "Needs attention" else "Verified"))
                    .performClick()
                compose.onNodeWithText("Open Android settings").performScrollTo().performClick()
                awaitSettings()
                await("Portemoné app settings to load") { "Portemoné" in systemTexts() }
                val texts = systemTexts()
                assertTrue(
                    "Permission help must target this app, not general location settings: $texts",
                    "Portemoné" in texts
                )
                assertTrue(
                    "The destination must expose the app's permissions: $texts",
                    "Permissions" in texts || "Location" in texts
                )
                if (blocked) {
                    // Granting does not kill the process. Initial revocations are performed by the host before this test starts.
                    shell("pm grant ${context.packageName} $permission")
                    await("permission grant applied") { context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
                }
                shell("input keyevent KEYCODE_BACK")
                awaitHelpClosed()
                row(id).assert(status("Verified"))
            }
        }
    }

    @Test
    fun deviceLocationHelperOpensLocationAndTracksItsRealSwitch() {
        emulatorOnly()
        val manager = context.getSystemService(LocationManager::class.java)
        val original = manager.isLocationEnabled
        try {
            shell("cmd location set-location-enabled false")
            await("device location off") { !manager.isLocationEnabled }
            ActivityScenario.launch(NotificationHealthActivity::class.java).use {
                row("location").assert(status("Needs attention")).performClick()
                compose.onNodeWithText("Open Android settings").performScrollTo().performClick()
                awaitSettings()
                assertTrue(
                    "Location help must open the device's Use location setting",
                    "Use location" in systemTexts()
                )
                toggleSystemChannel()
                await("device location on") { manager.isLocationEnabled }
                shell("input keyevent KEYCODE_BACK")
                awaitHelpClosed()
                row("location").assert(status("Verified"))
            }
        } finally {
            shell("cmd location set-location-enabled $original")
        }
    }

    @Test
    fun samsungSpecificHelpersHaveAWorkingSettingsFallbackAndNoManualGreenCheck() {
        emulatorOnly()
        val prefs = context.getSharedPreferences("notification_health", Context.MODE_PRIVATE)
        val original = prefs.getString("samsung_nowbar", null)
        try {
            prefs.edit().putString("samsung_nowbar", Build.FINGERPRINT).commit()
            listOf("samsung_integration", "samsung_nowbar", "samsung_privacy").forEach { id ->
                launchTask(id).use {
                    assertNoManualCertification()
                    compose.onNodeWithText("Open Android settings").performScrollTo().performClick()
                    awaitSettings()
                    if (id == "samsung_privacy") {
                        assertTrue(
                            "Samsung privacy guidance must target this app's notifications",
                            "Portemoné" in systemTexts()
                        )
                    } else assertTrue(
                        "Samsung-only helper must have a navigable Settings fallback on other phones",
                        systemTexts().any {
                            it in setOf(
                                "Settings",
                                "Network & internet",
                                "Connected devices",
                                "Lock screen"
                            )
                        })
                    shell("input keyevent KEYCODE_BACK")
                    if (id != "samsung_integration") awaitUnverifiedAdvice()
                    else awaitHelpError("Still needs attention. Requires a Samsung phone with Android 15 / One UI 7 or later.")
                    assertNoManualCertification()
                    if (id == "samsung_nowbar") {
                        compose.onNodeWithText("Not verified · Samsung does not expose this app’s Now Bar permission")
                            .performScrollTo().assertIsDisplayed()
                    }
                }
            }
        } finally {
            prefs.edit().apply {
                if (original == null) remove("samsung_nowbar") else putString(
                    "samsung_nowbar",
                    original
                )
            }.commit()
        }
    }

    @Test
    fun appNotificationPermissionMatchesAndroidAndHelpOpensAppSettings() {
        // Run this once with host-side pm revoke before instrumentation, then again after pm grant.
        // Revoking POST_NOTIFICATIONS inside the test kills the instrumented process on Android 17.
        val blocked =
            InstrumentationRegistry.getArguments().getString("expectNotificationsBlocked", "false")
                .toBoolean()
        ActivityScenario.launch(NotificationHealthActivity::class.java).use {
            if (blocked) {
                val settings = LocationSettings(context)
                val enabled = settings.enabled
                try {
                    settings.enabled = false
                    compose.onNodeWithTag("notification_health_list")
                        .performScrollToNode(hasText("Continue setup"))
                    compose.onNodeWithText("Continue setup").performClick()
                    compose.onNodeWithText("Finish this required check before enabling automatic cards.")
                        .performScrollTo().assertIsDisplayed()
                    compose.onNodeWithText("Turn on Allow notifications for Portemoné.")
                        .performScrollTo().assertIsDisplayed()
                    assertFalse(
                        "A blocked required permission must prevent monitoring",
                        settings.enabled
                    )
                    compose.onNodeWithText("Done").performScrollTo().performClick()
                } finally {
                    settings.enabled = enabled
                }
            }
            row("notifications").assert(status(if (blocked) "Needs attention" else "Verified"))
            compose.onNodeWithText(if (blocked) "Notifications are blocked" else "Allowed by Android")
                .assertIsDisplayed()
            row("notifications").performClick()
            compose.onNodeWithText("Turn on Allow notifications for Portemoné.").assertIsDisplayed()
            compose.onNodeWithText("Open Android settings").performScrollTo().performClick()
            awaitSettings()
            assertTrue(
                "App help must open Portemoné's notification settings",
                nodes(instrumentation.uiAutomation.rootInActiveWindow).any {
                    it.text?.toString() == "Portemoné"
                })
            screenshot("notification-settings-toast")
            shell("input keyevent KEYCODE_BACK")
            if (blocked) {
                awaitHelpError("Still needs attention. Notifications are blocked.")
                compose.onNodeWithText("Open Android settings").assertIsDisplayed()
                compose.onNodeWithText("Done").performScrollTo().performClick()
            } else awaitHelpClosed()
            row("notifications").assert(status(if (blocked) "Needs attention" else "Verified"))
        }
    }

    @Test
    fun categoryHelpStaysOpenWhenBlockedThenClosesAutomaticallyAfterRepair() {
        emulatorOnly()
        val channelId = CardNotificationManager(context).channelId
        val originallyAllowed = channelAllowed(channelId)
        try {
            ActivityScenario.launch(NotificationHealthActivity::class.java).use {
                row("channel").performClick()
                compose.onNodeWithText("Open Android settings").performScrollTo().performClick()
                awaitSettings()
                val root = instrumentation.uiAutomation.rootInActiveWindow
                assertTrue(
                    "Channel help must open the Cards at checkout system category",
                    nodes(root).any {
                        it.text?.toString() == "Cards at checkout"
                    })
                screenshot("notification-channel-toast")
                if (channelAllowed(channelId)) toggleSystemChannel()
                await("channel blocked") { !channelAllowed(channelId) }
                shell("input keyevent KEYCODE_BACK")
                awaitHelpError("Still needs attention. The card notification category is blocked.")
                compose.onNodeWithText("Done").performScrollTo().performClick()
                row("lock_screen").assert(status("Needs attention"))
                row("channel").assert(status("Needs attention")).performClick()
                compose.onNodeWithText("Open Android settings").performScrollTo().performClick()
                awaitSettings()
                toggleSystemChannel()
                await("channel allowed") { channelAllowed(channelId) }
                shell("input keyevent KEYCODE_BACK")
                awaitHelpClosed()
                row("channel").assert(status("Verified"))
            }
        } finally {
            if (channelAllowed(channelId) != originallyAllowed) {
                context.startActivity(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                awaitSettings()
                toggleSystemChannel()
                await("channel switch restored") { channelAllowed(channelId) == originallyAllowed }
                shell("input keyevent KEYCODE_BACK")
            }
        }
    }

    private fun launchTask(id: String) = ActivityScenario.launch<NotificationHealthActivity>(
        Intent(
            context,
            NotificationHealthActivity::class.java
        ).putExtra(NotificationHealthActivity.EXTRA_TASK, id)
    )

    private fun awaitUnverifiedAdvice() {
        val advice = hasText(
            "Not verified.",
            substring = true
        ) and hasAnyAncestor(hasTestTag("notification_health_help"))
        compose.waitUntil(5_000) {
            compose.onAllNodes(advice).fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
        compose.onNode(advice).performScrollTo().assertIsDisplayed()
    }

    private fun assertNoManualCertification() {
        compose.onNodeWithText("I’ve checked this").assertDoesNotExist()
        compose.onNodeWithText("Mark as needing another check").assertDoesNotExist()
        compose.onNodeWithText("Confirmed by you", substring = true).assertDoesNotExist()
    }

    private fun systemTexts() =
        nodes(instrumentation.uiAutomation.rootInActiveWindow).mapNotNull { it.text?.toString() }

    private fun secureValue(key: String) =
        shell("settings get secure $key").toString(Charsets.UTF_8).trim()

    private fun restoreSecure(key: String, value: String) {
        if (value == "null") shell("settings delete secure $key") else shell("settings put secure $key $value")
    }

    private fun awaitHelpClosed() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Open Android settings")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() &&
                    compose.onAllNodesWithTag("notification_health_list")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithText("Done").assertDoesNotExist()
    }

    private fun awaitHelpError(message: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(message).fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
        compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
    }

    private fun row(id: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("notification_health_list")
            .performScrollToNode(hasTestTag("health_$id"))
        return compose.onNodeWithTag("health_$id")
    }

    private fun status(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun channelAllowed(id: String) =
        notifications.getNotificationChannel(id).importance != NotificationManager.IMPORTANCE_NONE

    private fun awaitSettings() = await("Android Settings to open") {
        instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings"
    }

    private fun toggleSystemChannel() {
        var switch: AccessibilityNodeInfo? = null
        await("notification category switch") {
            switch = nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
                it.isCheckable && it.className?.toString()?.endsWith("Switch") == true
            }
            switch != null
        }
        var target = checkNotNull(switch)
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue(
            "The real Android category switch must accept the click",
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        )
    }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "$name.png").outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun emulatorOnly() =
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) {
            "System-setting mutation tests run only on an emulator."
        }

    private fun await(description: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        do {
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < end)
        fail("Timed out waiting for $description")
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).use { it.readBytes() }
}
