// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.finnes.portemone.cards.LocalCardRepository
import dev.finnes.portemone.location.LocationSettings
import dev.finnes.portemone.notifications.NotificationCardRenderer
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PKPassLifecycleTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun homeAddsCardsFromSectionPlusWithoutStatusBanner() {
        val settings = LocationSettings(context)
        val enabled = settings.enabled
        try {
            settings.enabled = false
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(8_000) {
                    compose.onAllNodesWithTag("wallet_cards").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("wallet_cards").performScrollToNode(hasTestTag("add_card"))
                compose.onNodeWithText("Manage cards").assertDoesNotExist()
                compose.onNodeWithText("Automatic cards are on").assertDoesNotExist()
                compose.onNodeWithTag("add_card").performScrollTo().performClick()
                compose.onNodeWithText("Import .pkpass").assertIsDisplayed()
                compose.onNodeWithText("Add a card").performClick()
                compose.waitUntil(8_000) {
                    compose.onAllNodesWithText("Issuer").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Label").performScrollTo().assertIsDisplayed()
                compose.onAllNodesWithText("Cancel", substring = false).onLast().performClick()
            }
        } finally {
            settings.enabled = enabled
        }
    }

    @Test
    fun openingUnsignedPassRequiresOverrideAndReviewSurvivesRecreation() =
        withPass(Intent.ACTION_VIEW) { scenario, bytes, serial ->
            awaitText("Review pass")
            compose.onNodeWithText("Accept risk and import").assertIsDisplayed()
            compose.onNodeWithText("Import card", substring = false).assertDoesNotExist()
            scenario.recreate()
            awaitText("Review pass")
            compose.onNodeWithText("Accept risk and import").performClick()
            compose.waitUntil(8_000) {
                LocalCardRepository.get(context).cards().any { it.source?.recordId == serial } &&
                        compose.onAllNodesWithText("Review pass").fetchSemanticsNodes().isEmpty()
            }
            val saved =
                LocalCardRepository.get(context).cards().single { it.source?.recordId == serial }
            assertEquals("0000012345", saved.rawValue)
            assertArrayEquals(bytes, LocalCardRepository.get(context).originalPackage(saved.id))
        }

    @Test
    fun sharingPassUsesSameReviewAndCancelDoesNotSave() =
        withPass(Intent.ACTION_SEND) { _, _, serial ->
            awaitText("Review pass")
            compose.onNodeWithText("Cancel", substring = false).performClick()
            awaitText("Cards")
            assertTrue(
                LocalCardRepository.get(context).cards().none { it.source?.recordId == serial })
        }

    @Test
    fun archiveRestoreAndConfirmedDeletionAreAvailableAfterImport() =
        withPass(Intent.ACTION_SEND) { _, _, serial ->
            awaitText("Review pass")
            compose.onNodeWithText("Accept risk and import").performClick()
            compose.waitUntil(8_000) {
                LocalCardRepository.get(context).cards().any { it.source?.recordId == serial } &&
                        compose.onAllNodesWithText("Review pass").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("Import fixture Personal").performScrollTo().performClick()
            awaitText("Edit")
            compose.onNodeWithText("Manage card").assertDoesNotExist()
            compose.onNodeWithText("Edit", substring = false).performClick()
            awaitText("Edit card")
            compose.onNode(hasText("Test pass\n\nIssuer instructions") and hasSetTextAction())
                .assertExists()
            compose.onNodeWithText("Text beneath code (optional)").assertDoesNotExist()
            compose.onNodeWithText("Label").performTextReplacement("Work")
            compose.onNodeWithText("Description (optional)").performScrollTo()
                .performTextReplacement("Checkout instructions")
            compose.onNodeWithText("Background", substring = false).performScrollTo()
            compose.onNodeWithContentDescription("Custom background color").performScrollTo()
                .performClick()
            compose.onNodeWithText("Background color").assertIsDisplayed()
            compose.onNodeWithText("Hex color").performScrollTo().performTextReplacement("invalid")
            compose.onNodeWithText("Use color").assertIsNotEnabled()
            compose.onNodeWithText("Hex color").performTextReplacement("A12BC3")
            compose.onNodeWithText("Use color").performClick()
            compose.onNodeWithText("Save", substring = false).performClick()
            compose.waitUntil(8_000) {
                LocalCardRepository.get(context).cards()
                    .any { it.source?.recordId == serial && it.cardName == "Work" } &&
                        compose.onAllNodesWithText("Edit card").fetchSemanticsNodes().isEmpty()
            }
            val edited =
                LocalCardRepository.get(context).cards().single { it.source?.recordId == serial }
            assertEquals(0xFFA12BC3.toInt(), edited.brandColor)
            assertEquals("Checkout instructions", edited.description)
            assertEquals(null, edited.displayText)
            assertEquals("0000012345", edited.rawValue)
            compose.onNodeWithTag("card_details")
                .performScrollToNode(hasText("Checkout instructions"))
            compose.onNodeWithText("Checkout instructions").assertIsDisplayed()

            compose.onNodeWithTag("card_details")
                .performScrollToNode(hasText("Archive", substring = false))
            compose.onNodeWithText("Archive", substring = false).performClick()
            compose.waitUntil(5_000) {
                LocalCardRepository.get(context).cards()
                    .any { it.source?.recordId == serial && it.archived }
            }
            awaitText("Restore")
            compose.onAllNodesWithText("Restore", substring = false).onLast().performScrollTo()
                .performClick()
            compose.waitUntil(5_000) {
                LocalCardRepository.get(context).cards()
                    .any { it.source?.recordId == serial && !it.archived }
            }
            compose.onNodeWithText("More", substring = false).performClick()
            awaitText("Delete")
            compose.onAllNodesWithText("Delete", substring = false).onLast().performScrollTo()
                .performClick()
            compose.onNodeWithText("Keep card").assertIsDisplayed()
            compose.onAllNodesWithText("Delete", substring = false).onLast().performClick()
            compose.waitUntil(5_000) {
                LocalCardRepository.get(context).cards().none { it.source?.recordId == serial }
            }
        }

    @Test
    fun newBarcodeFormatsDecodeAtTheirRenderedPixelSize() {
        for (type in listOf(BarcodeType.PDF_417, BarcodeType.AZTEC)) {
            val card = Card(
                "render-$type",
                "Example",
                "Pass",
                R.drawable.ic_card,
                0,
                -1,
                type,
                "0000012345"
            )
            val bitmap = if (type.square) BarcodeRenderer.fitSquare(
                card,
                400
            ).bitmap else BarcodeRenderer.render(card.rawValue, type, 600, 260)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = com.google.zxing.RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val decoded = com.google.zxing.MultiFormatReader()
                .decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)))
            assertEquals("0000012345", decoded.text)
            assertEquals(type.format, decoded.barcodeFormat)
            bitmap.recycle()
        }
    }

    @Test
    fun importedNotificationFacesRenderCodesAndHideAbsentCodes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (type in BarcodeType.entries) {
                val card = Card(
                    "face-$type",
                    "Example",
                    "Pass",
                    R.drawable.ic_card,
                    0xff146c64.toInt(),
                    -1,
                    type,
                    barcodeFixture(type),
                    displayText = "Issuer instructions",
                    source = CardSource("pkpass", "pass.example", "face")
                )
                val width = (320 * context.resources.displayMetrics.density).toInt()
                val face = NotificationCardRenderer.create(context, card, width)
                    .apply(context, android.widget.FrameLayout(context))
                val image = face.findViewById<android.widget.ImageView>(R.id.barcode)
                assertEquals(android.widget.ImageView.ScaleType.CENTER, image.scaleType)
                if (type == BarcodeType.NONE) {
                    assertEquals(android.view.View.GONE, image.visibility)
                    val widget = CardWidgetRenderer.render(
                        context,
                        card,
                        android.util.SizeF(300f, 340f),
                        true
                    )
                        .apply(context, android.widget.FrameLayout(context))
                    assertEquals(
                        android.view.View.GONE,
                        widget.findViewById<android.view.View>(R.id.widget_code).visibility
                    )
                    assertTrue(
                        widget.findViewById<android.widget.TextView>(R.id.widget_message).text.contains(
                            "no barcode"
                        )
                    )
                } else {
                    assertEquals(android.view.View.VISIBLE, image.visibility)
                    assertEquals(
                        card.rawValue,
                        face.findViewById<android.widget.TextView>(R.id.card_number).text.toString()
                    )
                    val bitmap = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
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
                }
            }
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun withPass(
        action: String,
        test: (ActivityScenario<CardManagementActivity>, ByteArray, String) -> Unit
    ) {
        val repository = LocalCardRepository.get(context)
        val settings = LocationSettings(context)
        val enabled = settings.enabled
        val serial = UUID.randomUUID().toString()
        val json = JSONObject().put("formatVersion", 1).put("organizationName", "Import fixture")
            .put("description", "Test pass")
            .put("logoText", "Personal").put("passTypeIdentifier", "pass.portemone.fixture")
            .put("serialNumber", serial)
            .put("teamIdentifier", "FIXTURE123").put("storeCard", JSONObject())
            .put(
                "barcode",
                JSONObject().put("format", "PKBarcodeFormatQR").put("message", "0000012345")
                    .put("messageEncoding", "UTF-8").put("altText", "Issuer instructions")
            )
        val bytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use {
                it.putNextEntry(ZipEntry("pass.json")); it.write(
                json.toString().toByteArray()
            ); it.closeEntry()
            }
        }.toByteArray()
        val file = java.io.File(context.cacheDir, "debug-exports/import-fixture-$serial.pkpass")
            .apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        val intent = Intent(action).setClass(context, CardManagementActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (action == Intent.ACTION_VIEW) intent.setDataAndType(uri, "application/vnd.apple.pkpass")
        else intent.setType("application/vnd.apple.pkpass").putExtra(Intent.EXTRA_STREAM, uri)
        val resolved = Intent(intent).setComponent(null).setPackage(context.packageName)
            .resolveActivity(context.packageManager)
        assertEquals(
            "Open/Share must resolve to the pass import screen",
            CardManagementActivity::class.java.name,
            resolved?.className
        )
        try {
            settings.enabled = false
            ActivityScenario.launch<CardManagementActivity>(intent).use { test(it, bytes, serial) }
        } finally {
            repository.cards().filter { it.source?.recordId == serial }
                .forEach { repository.deleteCard(it.id) }
            settings.enabled = enabled; file.delete()
        }
    }
}
