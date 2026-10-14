// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.finnes.portemone.notifications.NotificationCardRenderer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationCardRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun landscapeFacesPreserveCodePixelsAndCreditCardProportions() {
        instrumentation.runOnMainSync {
            for (widthDp in listOf(240, 280, 324, 396)) {
                for (card in TestMemberships.cards + SampleCard.card) {
                    val width = (widthDp * context.resources.displayMetrics.density).roundToInt()
                    val root = NotificationCardRenderer.create(context, card, width)
                        .apply(context, FrameLayout(context))
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    val face = root.findViewById<View>(R.id.card_face)
                    assertEquals(
                        NotificationCardRenderer.ASPECT_RATIO.toDouble(),
                        face.width.toDouble() / face.height,
                        0.01
                    )
                    val image = root.findViewById<ImageView>(R.id.barcode)
                    val code = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                    assertEquals(image.width, code.width)
                    assertEquals(image.height, code.height)
                    assertEquals(ImageView.ScaleType.CENTER, image.scaleType)
                    val pixels = IntArray(code.width * code.height)
                    code.getPixels(pixels, 0, code.width, 0, 0, code.width, code.height)
                    assertTrue(pixels.all { it == Color.WHITE || it == Color.BLACK })
                    assertEquals(
                        card.rawValue, MultiFormatReader().decode(
                            BinaryBitmap(
                                HybridBinarizer(
                                    RGBLuminanceSource(code.width, code.height, pixels)
                                )
                            )
                        ).text
                    )
                }
            }
        }
    }

    @Test
    fun whiteSurfaceFadesUniformlyAndCornersStayTransparent() {
        assertUniformFade(12f)
    }

    @Test
    fun samsungFaceLeavesNoPaintOutsideHostCornersDuringSwipe() {
        assertUniformFade(36f)
    }

    @Test
    fun narrowerNowBarKeepsTheFaceCornersAndCodeInsideItsBounds() {
        assertUniformFade(36f, hostWidthDp = 310, card = SampleCard.card)
    }

    private fun assertUniformFade(
        radiusDp: Float, hostWidthDp: Int = 324,
        card: Card = TestMemberships.cards.first()
    ) {
        ActivityScenario.launch(DebugActivity::class.java).use { scenario ->
            lateinit var face: View
            lateinit var image: ImageView
            lateinit var window: android.view.Window
            val painted = CountDownLatch(1)
            val background = Color.rgb(32, 48, 64)
            scenario.onActivity { activity ->
                window = activity.window
                val root = FrameLayout(activity).apply { setBackgroundColor(background) }
                val width = (324 * activity.resources.displayMetrics.density).roundToInt()
                val remote = NotificationCardRenderer.create(
                    activity,
                    card,
                    width,
                    radiusDp,
                    fitHostWidth = true
                )
                    .apply(activity, root)
                face = remote.findViewById(R.id.card_face)
                image = remote.findViewById(R.id.barcode)
                root.addView(
                    remote, FrameLayout.LayoutParams(
                        (hostWidthDp * activity.resources.displayMetrics.density).roundToInt(), -2
                    ).apply {
                        leftMargin = 30; topMargin = 160
                    })
                face.alpha = 0.5f
                remote.translationX = 24f
                activity.setContentView(root)
                root.postOnAnimation { root.postOnAnimation { painted.countDown() } }
            }
            assertTrue(painted.await(5, TimeUnit.SECONDS))
            val screenshot = copy(window)
            val faceAt = IntArray(2)
            val imageAt = IntArray(2)
            scenario.onActivity {
                face.getLocationInWindow(faceAt); image.getLocationInWindow(
                imageAt
            )
            }
            assertEquals(
                (hostWidthDp * context.resources.displayMetrics.density).roundToInt(),
                face.width
            )
            assertTrue(imageAt[0] >= faceAt[0])
            assertTrue(imageAt[0] + image.width <= faceAt[0] + face.width)
            val panelWhite = screenshot.getPixel(imageAt[0] - 2, imageAt[1] + image.height / 2)
            val bitmapWhite = screenshot.getPixel(imageAt[0] + 2, imageAt[1] + image.height / 2)
            assertEquals(
                "The bitmap's white quiet zone must not fade separately",
                panelWhite,
                bitmapWhite
            )
            // A 12dp face inside a 36dp host also has transparent extreme corners.
            // Sample farther inward to catch the crescent that only appears on Samsung.
            val inset = (radiusDp * context.resources.displayMetrics.density * 0.2f).roundToInt()
            for (x in listOf(inset, face.width - inset - 1)) for (y in listOf(
                inset,
                face.height - inset - 1
            )) {
                assertEquals(
                    "No white corners outside the card during translation", background,
                    screenshot.getPixel(faceAt[0] + x, faceAt[1] + y)
                )
            }
            File(context.cacheDir, "card-fade-${radiusDp.toInt()}-test.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test
    fun captureCardPreview() {
        ActivityScenario.launch(DebugActivity::class.java).use { scenario ->
            lateinit var window: android.view.Window
            val painted = CountDownLatch(1)
            scenario.onActivity { activity ->
                window = activity.window
                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    setBackgroundColor(Color.rgb(29, 38, 51))
                }
                val density = activity.resources.displayMetrics.density
                val width = (324 * density).roundToInt()
                for (card in TestMemberships.cards + SampleCard.card) {
                    val view =
                        NotificationCardRenderer.create(activity, card, width).apply(activity, root)
                    root.addView(
                        view,
                        LinearLayout.LayoutParams(width, -2)
                            .apply { bottomMargin = (20 * density).roundToInt() })
                }
                activity.setContentView(root)
                root.postOnAnimation { root.postOnAnimation { painted.countDown() } }
            }
            assertTrue(painted.await(5, TimeUnit.SECONDS))
            File(context.cacheDir, "card-preview.png").outputStream().use {
                copy(window).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun copy(window: android.view.Window): Bitmap {
        lateinit var screenshot: Bitmap
        instrumentation.runOnMainSync {
            screenshot = Bitmap.createBitmap(
                window.decorView.width,
                window.decorView.height,
                Bitmap.Config.ARGB_8888
            )
        }
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(
            window, Rect(0, 0, screenshot.width, screenshot.height), screenshot,
            { result = it; done.countDown() }, Handler(Looper.getMainLooper())
        )
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return screenshot
    }
}
