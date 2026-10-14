// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.finnes.portemone.cards.LocalCardRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardScreenTest {
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
    fun enlargedCodeCanRotateAndReturnWithTheSameScannableValue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = LocalCardRepository.get(context)
        val card = TestMemberships.cards.first().copy(
            id = "rotation-test",
            brandName = "Synthetic test",
            rawValue = "A7B3C9D2E8F4G6H5J1K0L2M4N6P8Q0R3S5T7U9V1",
            barcodeType = BarcodeType.CODE_128,
            stores = emptyList()
        )
        repository.saveCard(card)
        try {
            ActivityScenario.launch<CardActivity>(CardActivity.intent(context, card)).use { scenario ->
                compose.onNodeWithContentDescription("Enlarge Synthetic test code").performClick()
                compose.waitForIdle()
                fun verifyAndCapture(name: String): Int {
                    var span = 0
                    scenario.onActivity { activity ->
                        val view = activity.findViewById<ImageView>(R.id.barcode)
                        val bitmap = (view.drawable as BitmapDrawable).bitmap
                        assertEquals(view.width, bitmap.width)
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        assertEquals(card.rawValue, MultiFormatReader().decode(BinaryBitmap(
                            HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
                        )).text)
                        val bars = (0 until bitmap.width).filter {
                            bitmap.getPixel(it, bitmap.height / 2) == android.graphics.Color.BLACK
                        }
                        span = bars.last() - bars.first() + 1
                    }
                    java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                        instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    return span
                }
                val initialOrientation = context.resources.configuration.orientation
                val initialSpan = verifyAndCapture("barcode-padding-portrait")
                compose.onNodeWithContentDescription("Rotate").performClick()
                compose.waitUntil(10_000) { context.resources.configuration.orientation != initialOrientation }
                compose.waitForIdle()
                val rotatedSpan = verifyAndCapture("barcode-padding-landscape")
                assertTrue("Rotating should change the available barcode width", initialSpan != rotatedSpan)
                compose.onNodeWithContentDescription("Rotate").performClick()
                compose.waitUntil(10_000) { context.resources.configuration.orientation == initialOrientation }
                compose.waitForIdle()
                assertEquals(initialSpan, verifyAndCapture("barcode-padding-return"))
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitForIdle()
                scenario.onActivity { activity ->
                    assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("barcodeWidths", "initial=$initialSpan rotated=$rotatedSpan")
                })
            }
        } finally {
            repository.deleteCard(card.id)
        }
    }

    @Test
    fun brandedCardCanEnlargeItsCodeAndReturnWithoutChangingMembership() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val card = TestMemberships.cards.first()
        ActivityScenario.launch<CardActivity>(CardActivity.intent(context, card)).use { scenario ->
            compose.waitForIdle()
            fun verifyCode(brightness: Float): Int {
                var size = 0
                scenario.onActivity { activity ->
                    assertEquals(brightness, activity.window.attributes.screenBrightness)
                    val image = activity.findViewById<ImageView>(R.id.barcode)
                    assertEquals(ImageView.ScaleType.CENTER, image.scaleType)
                    val bitmap = (image.drawable as BitmapDrawable).bitmap
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertEquals(
                        card.rawValue, MultiFormatReader().decode(
                            BinaryBitmap(
                                HybridBinarizer(
                                    RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                                )
                            )
                        ).text
                    )
                    size = bitmap.width
                }
                return size
            }

            val initialSize = verifyCode(-1f)
            compose.onNodeWithContentDescription("Enlarge Example One code").performClick()
            compose.waitForIdle()
            assertTrue(verifyCode(1f) > initialSize)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
            assertEquals(initialSize, verifyCode(-1f))
        }
    }
}
