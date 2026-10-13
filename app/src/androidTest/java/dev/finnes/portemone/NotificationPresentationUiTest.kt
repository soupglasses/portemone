// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.finnes.portemone.notifications.integrations.NotificationPresentation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationPresentationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun cardAppearanceIsAnOrdinarySettingWithOneSharedChoice() = withSavedChoice {
        val settings = LocationSettings(context)
        settings.notificationPresentation = NotificationPresentation.STANDARD
        ActivityScenario.launch(SettingsActivity::class.java).use { activity ->
            compose.onNodeWithTag("notification_presentation_STANDARD").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("notification_presentation_AUTOMATIC").performScrollTo().performClick()
            assertEquals(NotificationPresentation.AUTOMATIC, settings.notificationPresentation)
            activity.recreate()
            compose.onNodeWithTag("notification_presentation_AUTOMATIC").performScrollTo().assertIsSelected()
            compose.onNodeWithText("Use compatibility cards").assertDoesNotExist()
            compose.onNodeWithText("Preview notification").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun freshInstallDefaultsToAutomaticAndOldOptOutRemainsExplicit() = withSavedChoice {
        val preferences = context.getSharedPreferences("local_geofences", Context.MODE_PRIVATE)
        preferences.edit().remove("notification_presentation").remove("pixel_notification_compatibility").commit()
        assertEquals(NotificationPresentation.AUTOMATIC, LocationSettings(context).notificationPresentation)
        if (!dev.finnes.portemone.notifications.integrations.SamsungNowBar.isSamsung) {
            preferences.edit().putBoolean("pixel_notification_compatibility", false).commit()
            assertEquals(NotificationPresentation.STANDARD, LocationSettings(context).notificationPresentation)
        }
        LocationSettings(context).notificationPresentation = NotificationPresentation.AUTOMATIC
        assertFalse(preferences.contains("pixel_notification_compatibility"))
    }

    @Test fun developerToolsDoNotContainASecondPresentationSwitch() = withSavedChoice {
        ActivityScenario.launch(DeveloperToolsActivity::class.java).use {
            compose.onNodeWithText("Use compatibility cards").assertDoesNotExist()
            compose.onNodeWithText("Pixel notification compatibility").assertDoesNotExist()
        }
    }

    private fun withSavedChoice(test: () -> Unit) {
        val preferences = context.getSharedPreferences("local_geofences", Context.MODE_PRIVATE)
        val original = preferences.all.filterKeys { it in setOf("notification_presentation", "pixel_notification_compatibility") }
        try { test() } finally {
            preferences.edit().remove("notification_presentation").remove("pixel_notification_compatibility").apply {
                original.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                } }
            }.commit()
        }
    }
}
