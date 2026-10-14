// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.Intent
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import dev.finnes.portemone.cards.LocalCardRepository
import dev.finnes.portemone.location.LocationSettings
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Ean13RenderingTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun renderedEan13DecodesWithQuietZonesAndLeadingZeroes() {
        for (value in listOf("5901234123457", "0123456789012")) {
            for (width in listOf(117, 320, 640)) {
                val bitmap = BarcodeRenderer.render(value, BarcodeType.EAN_13, width, 80)
                try {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, bitmap.height)
                    assertTrue(pixels.all { it == Color.BLACK || it == Color.WHITE })
                    val result = MultiFormatReader().decode(
                        BinaryBitmap(
                            HybridBinarizer(
                                RGBLuminanceSource(
                                    width,
                                    bitmap.height,
                                    pixels
                                )
                            )
                        ),
                        mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13))
                    )
                    assertEquals(BarcodeFormat.EAN_13, result.barcodeFormat)
                    assertEquals(value, result.text)
                    val row = pixels.take(width)
                    val left = row.indexOfFirst { it == Color.BLACK }
                    val module = row.drop(left).takeWhile { it == Color.BLACK }.size
                    assertTrue(left >= 11 * module)
                    assertTrue(width - 1 - row.indexOfLast { it == Color.BLACK } >= 7 * module)
                } finally {
                    bitmap.recycle()
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BarcodeRenderer.render("590123412345", BarcodeType.EAN_13, 320, 80)
        }
    }

    @Test
    fun existingCardCanSwitchToEan13WithoutLosingDigits() {
        val repository = LocalCardRepository.get(context)
        val settings = LocationSettings(context)
        val enabled = settings.enabled
        val card = TestCard.card.copy(
            id = UUID.randomUUID().toString(),
            brandName = "EAN fixture",
            rawValue = "0123456789012",
            stores = emptyList()
        )

        fun edit() = ActivityScenario.launch<CardManagementActivity>(
            Intent(
                context,
                CardManagementActivity::class.java
            ).putExtra("edit_card_id", card.id)
        )
        try {
            settings.enabled = false
            repository.saveCard(card)
            edit().use {
                compose.waitUntil(8_000) {
                    compose.onAllNodesWithText("Code format").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Code format").performScrollTo()
                compose.onNodeWithText("EAN-13", substring = false).performScrollTo().performClick()
                compose.onNodeWithText("Save", substring = false).performClick()
                compose.waitUntil(8_000) {
                    repository.cards().single { it.id == card.id }.barcodeType == BarcodeType.EAN_13
                }
            }
            assertEquals(card.rawValue, repository.cards().single { it.id == card.id }.rawValue)
            edit().use {
                compose.waitUntil(8_000) {
                    compose.onAllNodesWithText("Code format").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Code format").performScrollTo()
                compose.onNodeWithText("EAN-13", substring = false).performScrollTo()
                    .assertIsSelected()
                compose.onNodeWithText("Barcode value").performScrollTo()
                    .assertTextContains(card.rawValue)
            }
        } finally {
            repository.deleteCard(card.id)
            settings.enabled = enabled
        }
    }
}
