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
