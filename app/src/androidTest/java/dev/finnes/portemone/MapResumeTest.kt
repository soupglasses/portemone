// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.finnes.portemone.cards.LocalCardRepository
import java.io.FileInputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.MapView

/** Run on an emulator with a swipe-only lock screen. Check rendered pixels, not just View existence. */
@RequiresDeviceSetup("Requires network map tiles and a swipe-only lock screen")
@RunWith(AndroidJUnit4::class)
class MapResumeTest {
    @org.junit.Before
    fun establishMembershipFixtures() {
        val repository =
            LocalCardRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val ids = repository.cards().map { it.id }.toSet()
        TestMemberships.cards.filter { it.id !in ids }.forEach { repository.saveCard(it) }
    }

    @get:Rule
    val compose = createEmptyComposeRule()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Test
    fun previewAndExplorerRenderAfterUnlockWithoutMovingTheCamera() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val card = LocalCardRepository.get(context).cards().first { it.id == "fixture-one" }
        try {
            ActivityScenario.launch<CardManagementActivity>(
                android.content.Intent(
                    context,
                    CardManagementActivity::class.java
                ).putExtra("edit_card_id", card.id)
            ).use { scenario ->
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("Edit card").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("Open store map").performScrollTo()
                val preview = "Saved store area map"
                awaitMapPixels(preview, card.brandColor)
                val previewCamera = camera(preview)
                repeat(2) {
                    lockAndUnlock(scenario)
                    awaitMapPixels(preview, card.brandColor)
                    assertEquals(previewCamera, camera(preview))
                }
                compose.onNodeWithContentDescription("Open store map").performClick()
                val explorer = "Store map. Pan and zoom to explore; tap to select a location."
                awaitMapPixels(explorer, card.brandColor)
                val bounds = mapBounds(explorer)
                // Move the map before locking: a reset to the store's default camera would lose this.
                shell("input swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX() + 70} ${bounds.centerY()} 600")
                compose.waitForIdle()
                SystemClock.sleep(500) // Let the native map's pan inertia settle.
                val explorerCamera = camera(explorer)
                lockAndUnlock(scenario)
                awaitMapPixels(explorer, card.brandColor)
                assertEquals(explorerCamera, camera(explorer))
                compose.onNodeWithText("Close", substring = false).performClick()
                awaitMapPixels(preview, card.brandColor)
            }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
        }
    }

    private fun lockAndUnlock(scenario: ActivityScenario<CardManagementActivity>) {
        shell("input keyevent KEYCODE_SLEEP")
        compose.waitUntil(5_000) { scenario.state == Lifecycle.State.CREATED }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        compose.waitUntil(5_000) { scenario.state == Lifecycle.State.RESUMED }
    }

    private fun mapBounds(description: String): Rect {
        val bounds = Rect()
        onView(withContentDescription(description))
            .inRoot(isDialog()).check { view, error ->
                if (error != null) throw error
                val origin = IntArray(2)
                view.getLocationOnScreen(origin)
                bounds.set(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height)
            }
        return bounds
    }

    private fun camera(description: String): CameraPosition? {
        var camera: CameraPosition? = null
        onView(withContentDescription(description))
            .inRoot(isDialog()).check { view, error ->
                if (error != null) throw error
                (view as MapView).getMapAsync { camera = it.cameraPosition }
            }
        checkNotNull(camera)
        return camera
    }

    private fun awaitMapPixels(description: String, brandColor: Int) {
        val bounds = mapBounds(description)
        // The branded store outline and pin render even if network tiles are unavailable.
        // Limit sampling to the map so the membership card cannot give a false pass.
        compose.waitUntil(8_000) {
            val bitmap = automation.takeScreenshot() ?: return@waitUntil false
            try {
                var brandPixels = 0
                for (y in (bounds.top + 12) until minOf(bounds.bottom - 40, bitmap.height) step 2) {
                    for (x in (bounds.left + 12) until minOf(
                        bounds.right - 12,
                        bitmap.width
                    ) step 2) {
                        val pixel = bitmap.getPixel(x, y)
                        if (abs(Color.red(pixel) - Color.red(brandColor)) < 20 &&
                            abs(Color.green(pixel) - Color.green(brandColor)) < 20 &&
                            abs(Color.blue(pixel) - Color.blue(brandColor)) < 20
                        ) brandPixels++
                    }
                }
                brandPixels > 100
            } finally {
                bitmap.recycle()
            }
        }
        assertTrue(bounds.width() > 0 && bounds.height() > 0)
    }

    private fun shell(command: String) {
        automation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }
}
