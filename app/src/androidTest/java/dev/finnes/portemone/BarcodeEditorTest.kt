// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BarcodeEditorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun incompatibleFormatsExplainFailureAndKeepOriginalEanNumber() {
        val original = TestCard.card.copy(
            id = "ean-editor", barcodeType = BarcodeType.EAN_13, rawValue = "5901234123457"
        )
        var saved: Card? = null
        compose.setContent {
            MaterialTheme {
                CardEditor(original, false, null, {}, { saved = it })
            }
        }
        for ((type, message) in listOf(
            BarcodeType.EAN_8 to "EAN-8 needs exactly 8 digits, including the check digit.",
            BarcodeType.UPC_A to "UPC-A needs exactly 12 digits, including the check digit.",
            BarcodeType.UPC_E to "UPC-E needs exactly 8 digits, including the check digit.",
            BarcodeType.ITF to "ITF needs an even number of digits.",
            BarcodeType.ITF_14 to "ITF-14 needs exactly 14 digits, including the check digit.",
            BarcodeType.CODABAR to "Codabar needs digits or - $ : / . + between start and end letters A, B, C or D."
        )) {
            compose.onNodeWithText(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(type.labelRes)).performScrollTo().performClick()
            compose.onNodeWithText("Barcode value").performScrollTo()
                .assertTextContains(original.rawValue)
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithText("Save").assertIsNotEnabled()
            compose.runOnIdle { assertNull(saved) }
        }
        compose.onNodeWithText("EAN-13").performScrollTo().performClick()
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(original, saved) }
    }

    @Test
    fun correctingValueEnablesSaveWithoutChangingSelectedFormat() {
        var saved: Card? = null
        compose.setContent {
            MaterialTheme {
                CardEditor(
                    TestCard.card.copy(
                        id = "ean-editor",
                        barcodeType = BarcodeType.EAN_8,
                        rawValue = "5901234123457"
                    ),
                    false, null, {}, { saved = it }
                )
            }
        }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Barcode value").performScrollTo().performTextReplacement("96385074")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(BarcodeType.EAN_8, saved?.barcodeType)
            assertEquals("96385074", saved?.rawValue)
        }
    }
}
