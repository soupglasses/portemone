// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.finnes.portemone.imports.localizedPassNotice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalizedTextTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun resources(language: String): Resources {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(language))
        return context.createConfigurationContext(configuration).resources
    }

    @Test fun countsAndFormattingKeepMembershipValuesIntact() {
        val english = resources("en")
        assertEquals("1 saved store", english.getQuantityString(R.plurals.saved_stores, 1, 1))
        assertEquals("0 saved stores", english.getQuantityString(R.plurals.saved_stores, 0, 0))
        assertEquals("2 saved stores", english.getQuantityString(R.plurals.saved_stores, 2, 2))
        val value = "0000123400"
        val rtl = resources("ar-XB")
        assertTrue(rtl.getString(R.string.membership_code_description, value, "QR").contains(value))
        assertEquals("QR", rtl.getString(BarcodeType.QR_CODE.labelRes))
    }

    @Test fun validationAndSavedWarningsResolveInTheDisplayLanguage() {
        val failure = assertThrows(UserFacingException::class.java) {
            BarcodeType.EAN_13.validateValue("123")
        }
        val english = resources("en")
        val expanded = resources("en-XA")
        val fallback = R.string.could_not_save_this_card_try_again
        assertTrue(failure.userMessage(english, fallback).contains("13 digits"))
        assertNotEquals(failure.userMessage(english, fallback), failure.userMessage(expanded, fallback))
        val savedWarning = "Unsigned pass. The issuer and contents cannot be authenticated."
        assertEquals(savedWarning, savedWarning.localizedPassNotice(english))
        assertNotEquals(savedWarning, savedWarning.localizedPassNotice(expanded))
    }

    @Test fun accessibilityLabelChangesWithResourcesAndStillNavigates() {
        val current = mutableStateOf(resources("en"))
        var clicks = 0
        compose.setContent {
            CompositionLocalProvider(LocalResources provides current.value) {
                BackButton { clicks++ }
            }
        }
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        val expanded = resources("en-XA")
        compose.runOnIdle { current.value = expanded }
        compose.onNodeWithContentDescription(expanded.getString(R.string.back))
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
