// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.finnes.portemone.ui.theme.WalletTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NearbyCardDeckTest {
    @get:Rule
    val compose = createComposeRule()
    private val cards = TestMemberships.cards.take(2)

    @Test
    fun singleCardHasNoCarouselControlsAndOpensItsOwnMembership() {
        val card = cards.first()
        var opened: Card? = null
        compose.setContent {
            WalletTheme {
                Box(Modifier.width(360.dp)) {
                    NearbyCardDeck(
                        listOf(card), card.id, onSelect = {}, onOpen = { opened = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        assertCode(card)
        compose.onNodeWithTag("previous_nearby_card").assertDoesNotExist()
        compose.onNodeWithTag("next_nearby_card").assertDoesNotExist()
        compose.onAllNodesWithTag("deck_preview_${card.id}").assertCountEquals(0)
        compose.onNodeWithTag("nearby_card_${card.id}").performClick()
        compose.runOnIdle { assertEquals(card, opened) }
    }

    @Test
    fun swipingKeepsCardSizeAndReusesItsNativeFace() {
        showDeck()
        val initialBounds = compose.onNodeWithTag("nearby_card_${cards[0].id}")
            .fetchSemanticsNode().boundsInRoot
        val pagerBounds =
            compose.onNodeWithTag("nearby_card_pager").fetchSemanticsNode().boundsInRoot
        val density =
            InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertEquals(
            "The card must leave equal 24 dp gutters for adjacent cards",
            pagerBounds.width - 48f * density, initialBounds.width, 1f
        )
        assertEquals("The card must be centered", pagerBounds.center.x, initialBounds.center.x, 1f)
        val deckBounds = compose.onNodeWithTag("nearby_card_deck").fetchSemanticsNode().boundsInRoot
        assertCode(cards[0])
        val initialViews = barcodeViews()
        screenshot("nearby-deck-two-cards.png")
        compose.onNodeWithTag("nearby_card_pager").performTouchInput { swipeLeft() }
        assertTrue(
            "The incoming page must retain its existing rendered face",
            initialViews.contains(assertCode(cards[1]))
        )
        assertEquals(
            initialBounds, compose.onNodeWithTag("nearby_card_${cards[1].id}")
                .fetchSemanticsNode().boundsInRoot
        )
        compose.onNodeWithTag("nearby_card_pager").performTouchInput { swipeRight() }
        assertCode(cards[0])
        assertEquals(
            deckBounds,
            compose.onNodeWithTag("nearby_card_deck").fetchSemanticsNode().boundsInRoot
        )
    }

    @Test
    fun visibleSidePeeksSelectAdjacentCardsWithoutOpeningDetails() {
        showDeck()
        assertCode(cards[0])
        compose.onNodeWithTag("nearby_card_pager").performTouchInput {
            click(Offset(width - 4f, height * .5f))
        }
        assertCode(cards[1])
        compose.onNodeWithTag("nearby_card_pager").performTouchInput {
            click(Offset(4f, height * .5f))
        }
        assertCode(cards[0])
    }

    @Test
    fun horizontalSwipesStopAtFirstAndLastCardWithoutWrapping() {
        showDeck()
        compose.onNodeWithTag("nearby_card_deck").performTouchInput { swipeRight() }
        assertCode(cards[0])
        compose.onNodeWithTag("nearby_card_deck").performTouchInput { swipeLeft() }
        assertCode(cards[1])
        compose.onNodeWithTag("nearby_card_deck").performTouchInput { swipeLeft() }
        assertCode(cards[1])
        compose.onNodeWithTag("nearby_card_deck").performTouchInput { swipeRight() }
        assertCode(cards[0])
        compose.onNodeWithTag("nearby_card_deck").performTouchInput { swipeRight() }
        assertCode(cards[0])
    }

    @Test
    fun accessibilityPagingSelectsTheDisplayedMembership() {
        var selectedId by mutableStateOf(cards.first().id)
        compose.setContent {
            WalletTheme {
                Box(Modifier.width(360.dp)) {
                    NearbyCardDeck(
                        cards, selectedId, onSelect = { selectedId = it }, onOpen = {},
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        compose.onNodeWithTag("nearby_card_pager")
            .performSemanticsAction(SemanticsActions.PageRight) { it() }
        assertCode(cards[1])
        compose.runOnIdle { assertEquals(cards[1].id, selectedId) }
        compose.onNodeWithTag("nearby_card_pager")
            .performSemanticsAction(SemanticsActions.PageLeft) { it() }
        assertCode(cards[0])
        compose.runOnIdle { assertEquals(cards[0].id, selectedId) }
    }

    @Test
    fun threeCardsKeepSameBrandAccountsDistinctAndSwitchBarcodeLayouts() {
        val first = cards[0]
        val otherAccount = first.copy(
            id = "second-firstCard-account",
            rawValue = "000000000000000003",
            displayText = null
        )
        val barCard = SampleCard.card
        val three = listOf(first, otherAccount, barCard)
        compose.setContent {
            var selectedId by remember { mutableStateOf(first.id) }
            WalletTheme {
                Box(Modifier.width(360.dp)) {
                    NearbyCardDeck(
                        three, selectedId, onSelect = { selectedId = it }, onOpen = {},
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        assertCode(first)
        compose.onNodeWithTag("nearby_card_pager").performTouchInput { swipeLeft() }
        assertCode(otherAccount)
        compose.onNodeWithTag("nearby_card_${otherAccount.id}")
            .assertContentDescriptionContains("ending 0003", substring = true)
        compose.onNodeWithTag("nearby_card_pager").performTouchInput { swipeLeft() }
        assertCode(barCard)
        screenshot("nearby-deck-three-cards-code128.png")
        compose.onNodeWithTag("nearby_card_pager").performTouchInput { swipeRight() }
        assertCode(otherAccount)
        compose.onNodeWithTag("nearby_card_pager").performTouchInput { swipeRight() }
        assertCode(first)
    }

    @Test
    fun draggingKeepsOriginalSharpBitmapsAndNativeCardViews() {
        showDeck()
        val original = assertCode(cards[0])
        val originalViews = barcodeViews()
        val originalBitmaps = originalViews.associateWith { (it.drawable as BitmapDrawable).bitmap }
        val initialFaces = cards.associate { card ->
            val tag =
                if (card == cards.first()) "nearby_card_${card.id}" else "deck_preview_${card.id}"
            card.id to compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        }
        val initialCaptions = cards.associate { card ->
            card.id to compose.onNodeWithTag("nearby_caption_${card.id}").getUnclippedBoundsInRoot()
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("nearby_card_deck").performTouchInput {
                down(Offset(width * .85f, height * .35f))
                moveTo(Offset(width * .50f, height * .35f), delayMillis = 200)
            }
            compose.mainClock.advanceTimeByFrame()
            cards.forEach { card ->
                val face =
                    compose.onNodeWithTag("deck_preview_${card.id}").getUnclippedBoundsInRoot()
                val captionNode = compose.onNodeWithTag("nearby_caption_${card.id}")
                val caption = captionNode.getUnclippedBoundsInRoot()
                val faceMovement = face.left.value - initialFaces.getValue(card.id).left.value
                val captionMovement =
                    caption.left.value - initialCaptions.getValue(card.id).left.value
                assertTrue("The card should be visibly moving", kotlin.math.abs(faceMovement) > 5f)
                assertEquals(
                    "${card.brandName}'s caption must move with its own card",
                    faceMovement, captionMovement, 1f
                )
                captionNode.assert(hasAnyDescendant(hasText("${card.brandName} ${card.cardName}")))
                captionNode.assert(hasAnyDescendant(hasText(storeLabel(card))))
            }
            val movingViews = barcodeViews()
            assertTrue(
                "The outgoing face must move continuously rather than be replaced",
                movingViews.contains(original)
            )
            assertTrue(
                "Selection settles only when the gesture finishes",
                movingViews.none { it.isActivated })
            movingViews.forEach { image ->
                val bitmap = (image.drawable as BitmapDrawable).bitmap
                originalBitmaps[image]?.let { before ->
                    assertSame(
                        "Swiping must not replace the original image with a blurred texture",
                        before,
                        bitmap
                    )
                }
                assertTrue(
                    "The code texture remains sharp during movement",
                    decodeOrNull(bitmap) in cards.map { it.rawValue })
            }
            screenshot("nearby-deck-mid-drag.png")
            compose.onNodeWithTag("nearby_card_deck").performTouchInput {
                moveTo(Offset(width * .10f, height * .35f), delayMillis = 200)
                up()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertTrue(
            "The adjacent face must become active without reinflation",
            originalViews.contains(assertCode(cards[1]))
        )
        compose.onNodeWithTag("nearby_card_pager").performTouchInput { swipeRight() }
        assertCode(cards[0])
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream()
            .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun storeLabel(card: Card) = "${card.brandName} store · about 20 m"

    private fun showDeck() {
        compose.setContent {
            var selectedId by remember { mutableStateOf(cards.first().id) }
            WalletTheme {
                Box(Modifier.width(360.dp)) {
                    NearbyCardDeck(
                        cards, selectedId, onSelect = { selectedId = it }, onOpen = {},
                        modifier = Modifier.fillMaxWidth(), proximityLabel = ::storeLabel
                    )
                }
            }
        }
    }

    private fun assertCode(card: Card): ImageView {
        compose.waitForIdle()
        compose.onNodeWithTag("nearby_card_${card.id}").assertIsDisplayed()
        compose.onNodeWithTag("previous_nearby_card").assertDoesNotExist()
        compose.onNodeWithTag("next_nearby_card").assertDoesNotExist()
        val images = barcodeViews()
        val active = images.filter { it.isActivated }
        assertEquals("Exactly one settled front card must be selected", 1, active.size)
        val image = active.single()
        assertEquals(ImageView.ScaleType.CENTER, image.scaleType)
        assertEquals(card.rawValue, decodeOrNull((image.drawable as BitmapDrawable).bitmap))
        // Decode the physical screen crop as well: a correct source bitmap is not
        // sufficient if carousel transforms blur, scale, or occlude the checkout code.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var lastScreen: Bitmap? = null
        var scanned = false
        try {
            // Compose can be idle before the native window's opening animation
            // and its first physical frame have reached the screen compositor.
            compose.waitUntil(5_000) {
                val visible = Rect()
                instrumentation.runOnMainSync {
                    assertTrue(
                        "The checkout code must be visible",
                        image.getGlobalVisibleRect(visible)
                    )
                    // Visible bounds use root coordinates; screenshots use screen coordinates.
                    val rootOnScreen = IntArray(2)
                    image.rootView.getLocationOnScreen(rootOnScreen)
                    visible.offset(rootOnScreen[0], rootOnScreen[1])
                }
                lastScreen?.recycle()
                lastScreen = null
                val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                lastScreen = screen
                val crop = Bitmap.createBitmap(
                    screen, visible.left, visible.top, visible.width(), visible.height()
                )
                try {
                    scanned = decodeOrNull(crop) == card.rawValue
                    scanned
                } finally {
                    crop.recycle()
                }
            }
        } finally {
            lastScreen?.let { screen ->
                try {
                    if (!scanned) {
                        val file = java.io.File(
                            instrumentation.targetContext.getExternalFilesDir(null),
                            "nearby-deck-code-failure.png"
                        )
                        file.outputStream()
                            .use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                } finally {
                    screen.recycle()
                }
            }
        }
        return image
    }

    private fun barcodeViews(): List<ImageView> {
        val images = mutableListOf<ImageView>()
        onView(isRoot()).check { view, error ->
            if (error != null) throw error
            fun visit(node: View) {
                if (node is ImageView && node.id == R.id.barcode) images += node
                if (node is ViewGroup) repeat(node.childCount) { visit(node.getChildAt(it)) }
            }
            visit(view)
        }
        return images
    }

    private fun decodeOrNull(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return try {
            MultiFormatReader().decode(
                BinaryBitmap(
                    HybridBinarizer(
                        RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                    )
                )
            ).text
        } catch (_: com.google.zxing.ReaderException) {
            null
        }
    }
}
