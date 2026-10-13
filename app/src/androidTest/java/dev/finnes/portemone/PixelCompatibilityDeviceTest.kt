// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.notifications.integrations.PixelNotificationCompat

import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit companion-app integration suite; does not alter a physical phone or normal test run. */
@RequiresDeviceSetup("scripts/test-notification-integrations.sh supplies helper delegation and permission scenarios")
@RunWith(AndroidJUnit4::class)
class PixelCompatibilityDeviceTest {
    @Test
    fun exerciseInstalledHelper() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mode = InstrumentationRegistry.getArguments().getString("pixelCompatibilityTest")
        assumeTrue(mode != null && Build.MODEL.contains("sdk_gphone"))
        val context = instrumentation.targetContext
        val settings = LocationSettings(context)
        val previous = settings.pixelCompatibilityEnabled
        val notifications = CardNotificationManager(context)
        val system = context.getSystemService(NotificationManager::class.java)
        val qr = SampleCard.card.copy(
            id = if (mode == "render") SampleCard.card.id else "pixel-test-qr",
            cardName = "Family · test",
            barcodeType = BarcodeType.QR_CODE
        )
        val bar = TestCard.card.copy(
            id = if (mode == "render") TestCard.card.id else "pixel-test-bar",
            cardName = "Barcode · test"
        )
        val previousTestStore =
            settings.cards().find { it.id == TestCard.card.id }?.stores?.singleOrNull()
        val previousBarcodeType = settings.barcodeType
        fun active(card: Card) =
            system.activeNotifications.filter { it.id == CardNotificationManager.ID && it.tag == if (card.id == SampleCard.card.id) null else card.id }

        fun settle() = Thread.sleep(500)
        try {
            settings.pixelCompatibilityEnabled = true
            if (mode == "main-blocked") {
                assertFalse(system.areNotificationsEnabled())
                assertFalse(notifications.syncNearby(listOf(qr), onlyMissing = true))
                settle()
                assertTrue(
                    "Blocking Portemoné must clear the compatibility card on the next refresh",
                    active(qr).isEmpty()
                )
            } else if (mode == "blocked" || mode == "blocked-revoked") {
                if (mode == "blocked-revoked") assertFalse(
                    system.canNotifyAsPackage(
                        PixelNotificationCompat.PACKAGE
                    )
                )
                assertTrue(PixelNotificationCompat(context).status().blocked)
                assertFalse(notifications.show(qr))
                settle()
                assertTrue(
                    "Blocking helper notifications must not silently post on another channel",
                    active(qr).isEmpty()
                )
            } else if (mode == "fallback" || mode == "fallback-disabled") {
                assertFalse(PixelNotificationCompat(context).status().ready)
                assertTrue(notifications.show(qr))
                settle()
                assertEquals(context.packageName, active(qr).single().packageName)
            } else if (mode == "render") {
                settings.saveTestStore(StoreLocation("test", "Notification test", 0.0, 0.0, 200f))
                settings.barcodeType = BarcodeType.CODE_128
                assertTrue(PixelNotificationCompat(context).status().ready)
                // PendingIntent.send from a background test process lacks SystemUI’s
                // user-interaction privilege. Keep the app visible for this action check.
                val foreground =
                    androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
                try {
                    for (card in listOf(qr, bar)) assertTrue(notifications.show(card))
                    // Give SystemUI time to group cards; ownership and scan data must survive.
                    Thread.sleep(10_000)
                    for (card in listOf(qr, bar)) {
                        val posted = active(card).single()
                        assertEquals(PixelNotificationCompat.PACKAGE, posted.packageName)
                        instrumentation.runOnMainSync {
                            for (remote in listOf(
                                posted.notification.contentView,
                                posted.notification.bigContentView
                            )) {
                                val width =
                                    ((context.resources.configuration.screenWidthDp - 32).coerceIn(
                                        200,
                                        396
                                    ) *
                                            context.resources.displayMetrics.density).toInt()
                                val root =
                                    remote.apply(context, android.widget.FrameLayout(context))
                                root.measure(
                                    android.view.View.MeasureSpec.makeMeasureSpec(
                                        width,
                                        android.view.View.MeasureSpec.EXACTLY
                                    ),
                                    android.view.View.MeasureSpec.makeMeasureSpec(
                                        0,
                                        android.view.View.MeasureSpec.UNSPECIFIED
                                    )
                                )
                                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                                val image =
                                    root.findViewById<android.widget.ImageView>(R.id.barcode)
                                val bitmap =
                                    (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                                assertEquals(bitmap.width, image.width)
                                assertEquals(bitmap.height, image.height)
                                val displayed = android.graphics.Bitmap.createBitmap(
                                    image.width,
                                    image.height,
                                    android.graphics.Bitmap.Config.ARGB_8888
                                )
                                image.draw(android.graphics.Canvas(displayed))
                                val pixels = IntArray(displayed.width * displayed.height)
                                displayed.getPixels(
                                    pixels,
                                    0,
                                    displayed.width,
                                    0,
                                    0,
                                    displayed.width,
                                    displayed.height
                                )
                                assertTrue("Codes must not be smoothed", pixels.all {
                                    it == android.graphics.Color.BLACK || it == android.graphics.Color.WHITE
                                })
                                val decoded = com.google.zxing.MultiFormatReader().decode(
                                    com.google.zxing.BinaryBitmap(
                                        com.google.zxing.common.HybridBinarizer(
                                            com.google.zxing.RGBLuminanceSource(
                                                displayed.width,
                                                displayed.height,
                                                pixels
                                            )
                                        )
                                    )
                                )
                                assertEquals(card.rawValue, decoded.text)
                                assertEquals(card.barcodeType.name, decoded.barcodeFormat.name)
                            }
                        }
                        posted.notification.contentIntent.send()
                        val opened = awaitOpenedCard(card.id)
                        instrumentation.runOnMainSync { opened.finish() }
                    }
                } finally {
                    foreground.close()
                }
            } else {
                assertTrue(
                    PixelNotificationCompat(context).status().toString(),
                    PixelNotificationCompat(context).status().ready
                )
                val card = if (mode == "barcode") bar else qr
                assertTrue(notifications.show(card))
                settle()
                assertEquals(PixelNotificationCompat.PACKAGE, active(card).single().packageName)
                if (mode == "lifecycle") {
                    assertTrue(notifications.show(bar))
                    settle()
                    assertEquals(PixelNotificationCompat.PACKAGE, active(bar).single().packageName)
                    assertTrue(notifications.syncNearby(listOf(bar), onlyMissing = true))
                    settle()
                    assertTrue(
                        "Leaving QR store must remove that delegated card",
                        active(qr).isEmpty()
                    )
                    assertEquals(1, active(bar).size)
                    assertTrue(notifications.syncNearby(emptyList()))
                    settle()
                    assertTrue("Leaving all stores must remove every card", active(bar).isEmpty())
                    assertTrue(notifications.show(qr))
                    settings.pixelCompatibilityEnabled = false
                    assertTrue(notifications.show(qr))
                    settle()
                    assertEquals(
                        "Changing host must leave one standard notification",
                        context.packageName,
                        active(qr).single().packageName
                    )
                }
            }
        } finally {
            if (mode == "render") {
                if (previousTestStore == null) settings.removeTestStore() else settings.saveTestStore(
                    previousTestStore
                )
                settings.barcodeType = previousBarcodeType
            }
            settings.pixelCompatibilityEnabled = previous
            if (mode !in setOf("qr", "barcode")) {
                notifications.hide(qr)
                notifications.hide(bar)
                if (settings.enabled) notifications.syncNearby(settings.activeCards())
            }
        }
    }
}
