// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Exercise Android's vector renderer: an SVG preview cannot detect native clipping differences. */
@RunWith(AndroidJUnit4::class)
class IconRenderingTest {
    private data class Icon(val resource: Int, val name: String, val scale: Double, val offsetY: Double = 0.0)
    private data class Point(val x: Double, val y: Double)
    private val icons = listOf(
        Icon(R.drawable.ic_card, "notification", 1.6, 3.275),
        Icon(R.drawable.ic_launcher_monochrome, "themed", 1.0, 3.275),
    )
    private val angle = Math.toRadians(-11.0)

    @Test fun cardCavityRemainsTransparentAtNotificationAndLauncherSizes() {
        render(Icon(R.drawable.ic_launcher_foreground, "foreground", 1.0), 108).recycle()
        render(Icon(R.mipmap.ic_launcher, "adaptive", 1.0), 216).recycle()
        for (icon in icons) for (size in if (icon.name == "notification") listOf(16, 24, 64, 108) else listOf(24, 64, 108)) {
            val bitmap = render(icon, size)
            try {
                val label = "${icon.name} at ${size}px"
                // These gaps are surrounded by artwork, so transparent outer padding cannot hide a filled cavity.
                for (x in listOf(45.75, 56.25)) {
                    val alpha = sampledAlpha(bitmap, cardPoint(icon, size, x, 48.0)).min()
                    assertTrue("$label: the space between stamps must be transparent (alpha=$alpha)", alpha <= 45)
                }
                for (x in if (icon.name == "notification") listOf(40.5, 51.0, 61.5) else listOf(40.5, 51.0)) {
                    val alpha = sampledAlpha(bitmap, cardPoint(icon, size, x, 48.0)).max()
                    assertTrue("$label: each stamp must remain visible (alpha=$alpha)", alpha >= 110)
                }
                if (icon.name == "themed" && size == 108) {
                    val emptyStamp = sampledAlpha(bitmap, cardPoint(icon, size, 61.5, 48.0)).min()
                    assertTrue("The uncollected stamp must retain its transparent center", emptyStamp <= 45)
                }
                val pin = sampledAlpha(bitmap, viewportPoint(icon, size, 54.0, 68.55)).max()
                assertTrue("$label: the pin must remain opaque", pin >= 245)
                for ((x, y) in listOf(0 to 0, size - 1 to 0, 0 to size - 1, size - 1 to size - 1)) {
                    assertEquals("$label: no opaque icon background", 0, Color.alpha(bitmap.getPixel(x, y)))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun theStampRowIsCenteredInsideTheTiltedCard() {
        for (icon in icons.filter { it.name == "notification" }) {
            val bitmap = render(icon, 108)
            try {
                val centers = listOf(40.5, 51.0, 61.5).map { expectedX ->
                    var weight = 0.0
                    var weightedX = 0.0
                    var weightedY = 0.0
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val local = localCardPoint(icon, x + .5, y + .5)
                        if (abs(local.x - expectedX) > 4.0 || abs(local.y - 48.0) > 4.0) continue
                        val alpha = Color.alpha(bitmap.getPixel(x, y)).toDouble()
                        weight += alpha
                        weightedX += local.x * alpha
                        weightedY += local.y * alpha
                    }
                    assertTrue("${icon.name}: stamp at $expectedX must contain visible ink", weight > 1_000.0)
                    Point(weightedX / weight, weightedY / weight).also { center ->
                        assertEquals("${icon.name}: stamp horizontal alignment", expectedX, center.x, .7)
                        assertEquals("${icon.name}: stamp vertical alignment", 48.0, center.y, .7)
                    }
                }
                assertEquals("${icon.name}: stamp row centered in the card", 51.0, centers.map { it.x }.average(), .4)
                assertEquals("${icon.name}: stamp row centered vertically", 48.0, centers.map { it.y }.average(), .4)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun render(icon: Icon, size: Int): Bitmap {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        checkNotNull(context.getDrawable(icon.resource)).mutate().apply {
            setBounds(0, 0, size, size)
            draw(Canvas(bitmap))
        }
        // Keep the actual Android output available for diagnosis when an alpha assertion fails.
        val directory = File(context.cacheDir, "icon-rendering").apply { mkdirs() }
        File(directory, "${icon.name}-$size.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    private fun cardPoint(icon: Icon, size: Int, x: Double, y: Double): Point {
        val dx = x - 51.0
        val dy = y - 48.0
        return viewportPoint(icon, size,
            54.0 + dx * cos(angle) - dy * sin(angle),
            48.55 + dx * sin(angle) + dy * cos(angle))
    }

    private fun viewportPoint(icon: Icon, size: Int, x: Double, y: Double): Point = Point(
        (54.0 + (x - 54.0) * icon.scale) * size / 108.0,
        (54.0 + (y + icon.offsetY - 54.0) * icon.scale) * size / 108.0,
    )

    private fun localCardPoint(icon: Icon, x: Double, y: Double): Point {
        val dx = (x - 54.0) / icon.scale
        val dy = 54.0 + (y - 54.0) / icon.scale - icon.offsetY - 48.55
        return Point(51.0 + dx * cos(angle) + dy * sin(angle),
            48.0 - dx * sin(angle) + dy * cos(angle))
    }

    private fun sampledAlpha(bitmap: Bitmap, point: Point): List<Int> {
        // At 24px, a geometric point can land between pixels; inspect the adjacent pixel centers.
        val left = floor(point.x - .5).toInt()
        val top = floor(point.y - .5).toInt()
        return (top..top + 1).flatMap { y ->
            (left..left + 1).map { x ->
                Color.alpha(bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1)))
            }
        }
    }
}
