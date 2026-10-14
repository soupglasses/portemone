// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.finnes.portemone.cards.CardStorageCodec
import dev.finnes.portemone.cards.LocalCardRepository
import dev.finnes.portemone.location.LocationSettings
import dev.finnes.portemone.notifications.NotificationCardRenderer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardAppearanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fileLogoSurvivesSourceDeletionRecreationAndSave() {
        val name = "Logo fixture ${UUID.randomUUID()}"
        val repository = LocalCardRepository.get(context)
        val file = File(context.cacheDir, "debug-exports/logo-${UUID.randomUUID()}.png").apply {
            parentFile!!.mkdirs(); writeBytes(logoBytes())
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        val settings = LocationSettings(context)
        val enabled = settings.enabled
        Intents.init()
        try {
            settings.enabled = false
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
                ActivityResult(
                    Activity.RESULT_OK,
                    Intent().setData(uri)
                )
            )
            ActivityScenario.launch<CardManagementActivity>(
                Intent(context, CardManagementActivity::class.java)
                    .putExtra("card_action", "add")
            ).use { scenario ->
                await("Add logo")
                compose.onNodeWithText("Issuer").performScrollTo()
                    .performTextReplacement(name)
                compose.onNodeWithText("Label").performScrollTo()
                    .performTextReplacement("Personal")
                compose.onNodeWithText("Barcode value").performScrollTo()
                    .performTextReplacement("0000012345")
                compose.onNodeWithText("Add logo").performScrollTo().performClick()
                await("Logo")
                compose.waitUntil(8_000) {
                    compose.onAllNodes(
                        hasContentDescription(
                            "Color suggestion 1",
                            substring = true
                        )
                    ).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasContentDescription("Color suggestion 1", substring = true))
                    .performScrollTo().performClick()
                file.delete()
                scenario.recreate()
                await("Logo")
                compose.onNodeWithText("Save", substring = false).performClick()
                compose.waitUntil(8_000) { repository.cards().any { it.brandName == name } }
            }
            val saved = repository.cards().single { it.brandName == name }
            assertNotNull(saved.logoImage)
            assertTrue(saved.shaded)
            assertEquals("0000012345", saved.rawValue)
            assertEquals(
                saved,
                CardStorageCodec.decode(saved.id, CardStorageCodec.encode(saved), saved.stores)
            )
            ActivityScenario.launch<CardManagementActivity>(
                Intent(context, CardManagementActivity::class.java)
                    .putExtra("edit_card_id", saved.id)
            ).use {
                await("Logo")
                // Cancelling replacement must keep the copied logo and selected color.
                intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
                    ActivityResult(
                        Activity.RESULT_CANCELED,
                        null
                    )
                )
                compose.onNodeWithText("Logo", substring = false).performScrollTo().performClick()
                compose.onNodeWithText("Choose another logo").performClick()
                compose.onNodeWithText("Logo", substring = false).performScrollTo().performClick()
                compose.onNodeWithText("Remove logo").assertIsDisplayed().performClick()
                compose.onNodeWithText("Add logo").assertExists()
                compose.onNodeWithText("Save", substring = false).performClick()
                compose.waitUntil(8_000) {
                    repository.cards().single { it.id == saved.id }.logoImage == null
                }
                assertEquals(
                    saved.brandColor,
                    repository.cards().single { it.id == saved.id }.brandColor
                )
            }
        } finally {
            Intents.release()
            repository.cards().filter { it.brandName == name }
                .forEach { repository.deleteCard(it.id) }
            settings.enabled = enabled
            file.delete()
        }
    }

    @Test
    fun existingCardDraftRestoresAfterViewModelLossAndDelayedRepositoryLoad() {
        val repository = LocalCardRepository.get(context)
        val card = TestMemberships.cards.first().copy(
            id = "editor-restoration-${UUID.randomUUID()}", stores = emptyList()
        )
        val settings = LocationSettings(context)
        val enabled = settings.enabled
        val releaseRead = java.util.concurrent.CountDownLatch(1)
        var blocker: Thread? = null
        try {
            settings.enabled = false
            repository.saveCard(card)
            ActivityScenario.launch<CardManagementActivity>(
                Intent(context, CardManagementActivity::class.java).putExtra(
                    "edit_card_id",
                    card.id
                )
            ).use { scenario ->
                await("Edit card")
                compose.onNodeWithText("Label").performScrollTo()
                    .performTextReplacement("Unsaved label")
                // Recreation alone retains the model. Remove it and delay its replacement's
                // disk read to reproduce the empty initial catalog after process death.
                scenario.onActivity { it.viewModelStore.clear() }
                val locked = java.util.concurrent.CountDownLatch(1)
                blocker = Thread {
                    synchronized(repository) {
                        locked.countDown()
                        check(releaseRead.await(15, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }.also { it.start() }
                assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS))
                scenario.recreate()
                compose.waitForIdle()
                releaseRead.countDown()
                await("Edit card")
                compose.onNodeWithText("Label").performScrollTo()
                    .assertTextContains("Unsaved label")
                compose.onNodeWithText("Save", substring = false).performClick()
                compose.waitUntil(8_000) {
                    repository.cards().any { it.id == card.id && it.cardName == "Unsaved label" }
                }
                assertEquals(card.rawValue, repository.cards().single { it.id == card.id }.rawValue)
            }
        } finally {
            releaseRead.countDown()
            blocker?.join(5_000)
            repository.deleteCard(card.id)
            settings.enabled = enabled
        }
    }

    @Test
    fun importedImageIsBoundedAndPreservesTransparency() {
        val image = CardLogoImport.read(logoBytes(2048, 1024).inputStream())
        val bitmap = PassImages.decode(image)
        assertEquals(512, bitmap.width)
        assertEquals(256, bitmap.height)
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        bitmap.recycle()
        for (bytes in listOf("not an image".toByteArray(), ByteArray(10 * 1024 * 1024 + 1))) {
            try {
                CardLogoImport.read(bytes.inputStream()); fail("Invalid/oversized image accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun logoDisplayFitsVisibleArtworkWithoutAnOpaqueBacking() {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(40f, 30f, 160f, 70f, Paint().apply { color = Color.RED })
        bitmap.setPixel(100, 50, Color.TRANSPARENT)
        bitmap.setPixel(101, 50, 0x80ff0000.toInt())
        val bytes = ByteArrayOutputStream().apply {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        bitmap.recycle()
        val image = CardLogoImport.read(bytes.inputStream())
        val displayed = PassImages.decodeLogo(image)
        assertEquals(120, displayed.width)
        assertEquals(40, displayed.height)
        assertEquals(0, Color.alpha(displayed.getPixel(60, 20)))
        assertEquals(128, Color.alpha(displayed.getPixel(61, 20)))
        displayed.recycle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (type in listOf(BarcodeType.CODE_128, BarcodeType.QR_CODE)) {
                val card = TestCard.card.copy(logoImage = image, barcodeType = type)
                val view = NotificationCardRenderer.create(context, card, 1080)
                    .apply(context, FrameLayout(context))
                val logo = view.findViewById<ImageView>(R.id.brand_logo)
                assertEquals(
                    Color.TRANSPARENT,
                    (logo.background as android.graphics.drawable.ColorDrawable).color
                )
                assertEquals(
                    0,
                    logo.paddingLeft + logo.paddingTop + logo.paddingRight + logo.paddingBottom
                )
                assertEquals(3f, logo.layoutParams.width.toFloat() / logo.layoutParams.height, .1f)
                val rendered = (logo.drawable as BitmapDrawable).bitmap
                assertEquals(0, Color.alpha(rendered.getPixel(60, 20)))
            }
        }
    }

    @Test
    fun sparseLogosHaveFiveChoicesWithReadableShading() {
        for (color in listOf(Color.RED, Color.WHITE, Color.BLACK, 0xff789080.toInt())) {
            val image = CardLogoImport.read(logoBytes(color = color).inputStream())
            val choices = CardAppearance.suggestions(image)
            assertEquals(5, choices.size)
            assertEquals(5, choices.map { it.color }.distinct().size)
            assertTrue(choices.any { it.tonal })
            for (choice in choices) {
                val text = CardAppearance.foreground(choice.color)
                assertTrue(ColorUtils.calculateContrast(text, choice.color) >= 4.5)
                assertTrue(
                    ColorUtils.calculateContrast(
                        text,
                        CardAppearance.shade(choice.color)
                    ) >= 4.5
                )
            }
        }
        val transparent = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().apply {
            transparent.compress(
                Bitmap.CompressFormat.PNG,
                100,
                this
            )
        }.toByteArray()
        transparent.recycle()
        assertTrue(CardAppearance.suggestions(CardLogoImport.read(bytes.inputStream())).isEmpty())
    }

    @Test
    fun shadedFacesStillDecodeAllFormatsAndReapplyToFlat() {
        val logo = CardLogoImport.read(logoBytes().inputStream())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (type in BarcodeType.entries.filter { it != BarcodeType.NONE }) {
                val card = TestCard.card.copy(
                    id = "appearance-$type",
                    barcodeType = type,
                    rawValue = barcodeFixture(type),
                    logoImage = logo,
                    shaded = true
                )
                val face = NotificationCardRenderer.create(context, card, 1080)
                val parent = FrameLayout(context)
                val view = face.apply(context, parent)
                assertEquals(
                    android.view.View.VISIBLE,
                    view.findViewById<android.view.View>(R.id.brand_gradient).visibility
                )
                val bitmap =
                    (view.findViewById<ImageView>(R.id.barcode).drawable as BitmapDrawable).bitmap
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val source =
                    com.google.zxing.RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                val decoded = com.google.zxing.MultiFormatReader().decode(
                    com.google.zxing.BinaryBitmap(
                        com.google.zxing.common.HybridBinarizer(source)
                    ), barcodeDecodeHints(type)
                )
                assertEquals(card.rawValue, decoded.text)
                assertEquals(type.format, decoded.barcodeFormat)
                NotificationCardRenderer.create(context, card.copy(shaded = false), 1080)
                    .reapply(context, view)
                assertEquals(
                    android.view.View.GONE,
                    view.findViewById<android.view.View>(R.id.brand_gradient).visibility
                )
            }
        }
    }

    private fun await(text: String) = compose.waitUntil(8_000) {
        compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
    }

    private fun logoBytes(width: Int = 100, height: Int = 50, color: Int = Color.RED): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(
            width * .2f,
            height * .2f,
            width * .8f,
            height * .8f,
            Paint().apply { this.color = color })
        return ByteArrayOutputStream().apply {
            bitmap.compress(
                Bitmap.CompressFormat.PNG,
                100,
                this
            ); bitmap.recycle()
        }.toByteArray()
    }
}
