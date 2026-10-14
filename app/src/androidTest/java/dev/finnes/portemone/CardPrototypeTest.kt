// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.notifications.integrations.NotificationPresentation

import dev.finnes.portemone.notifications.integrations.SamsungNowBar

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class CardPrototypeTest {
    @org.junit.Before
    fun establishMembershipFixtures() {
        val repository =
            LocalCardRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val ids = repository.cards().map { it.id }.toSet()
        TestMemberships.cards.filter { it.id !in ids }.forEach { repository.saveCard(it) }
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private var previousPresentation = NotificationPresentation.STANDARD

    @Before
    fun isolateBuiltInNotificationStyles() {
        val settings = LocationSettings(context)
        previousPresentation = settings.notificationPresentation
        settings.notificationPresentation = NotificationPresentation.STANDARD
        CardNotificationManager(context).hide()
        Thread.sleep(1_000)
    }

    @After
    fun restoreNotificationHost() {
        CardNotificationManager(context).hide()
        LocationSettings(context).notificationPresentation = previousPresentation
    }

    private fun postCard(
        card: Card,
        style: CardNotificationStyle,
        collapsedPreview: Boolean = false
    ): Boolean =
        postAndAwait(card) { CardNotificationManager(context).show(card, style, collapsedPreview) }

    private fun postAndAwait(card: Card, post: () -> Boolean): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val tag = if (card.id == SampleCard.card.id) null else card.id
        val previous =
            manager.activeNotifications.firstOrNull { it.id == CardNotificationManager.ID && it.tag == tag }?.postTime
        // Exercise distinct user updates, outside Android 37's duplicate-post rate limit.
        Thread.sleep(500)
        if (!post()) return false
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (manager.activeNotifications.any { it.id == CardNotificationManager.ID && it.tag == tag && it.postTime != previous }) return true
            Thread.sleep(50)
        }
        fail("The updated notification must become observable")
        return false
    }

    private fun awaitActiveTags(expected: Set<String?>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val active = manager.activeNotifications.filter { it.id == CardNotificationManager.ID }
            if (active.size == expected.size && active.map { it.tag }.toSet() == expected) return
            Thread.sleep(50)
        }
        assertEquals(
            expected,
            manager.activeNotifications.filter { it.id == CardNotificationManager.ID }
                .map { it.tag }.toSet()
        )
    }

    private fun postedPicture(notification: Notification): Bitmap {
        notification.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)?.let { return it }
        // Current Android releases can transport the big picture as an Icon.
        val icon = requireNotNull(
            notification.extras.getParcelable<android.graphics.drawable.Icon>(Notification.EXTRA_PICTURE_ICON)
        ) {
            "The posted notification must retain its picture"
        }
        return (icon.loadDrawable(context) as android.graphics.drawable.BitmapDrawable).bitmap
    }

    @Test
    fun codesRemainScannableAtNotificationAndFullScreenSizes() {
        for (density in listOf(1f, 2.625f, 3f)) {
            for ((type, width, height) in listOf(
                Triple(BarcodeType.CODE_128, 240, 24),
                Triple(BarcodeType.CODE_128, 240, 112),
                Triple(BarcodeType.QR_CODE, 216, 216),
                Triple(BarcodeType.CODE_128, 360, 180),
                Triple(BarcodeType.QR_CODE, 360, 360),
            )) {
                val w = (width * density).roundToInt()
                val h = (height * density).roundToInt()
                val bitmap = BarcodeRenderer.render(SampleCard.card.rawValue, type, w, h)
                assertEquals(w, bitmap.width)
                assertEquals(h, bitmap.height)
                assertEquals(Bitmap.DENSITY_NONE, bitmap.density)
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                assertTrue(pixels.all { it == Color.BLACK || it == Color.WHITE })
                for (y in 0 until h) {
                    assertEquals(Color.WHITE, bitmap.getPixel(0, y))
                    assertEquals(Color.WHITE, bitmap.getPixel(w - 1, y))
                }
                val decoded = MultiFormatReader().decode(
                    BinaryBitmap(
                        HybridBinarizer(
                            RGBLuminanceSource(
                                w,
                                h,
                                pixels
                            )
                        )
                    )
                )
                assertEquals(SampleCard.card.rawValue, decoded.text)
                assertEquals(type.name, decoded.barcodeFormat.name)
            }
        }
    }

    @Test
    fun notificationsHaveQuietPublicOngoingFlagsAndInflatableLayouts() {
        // Run with POST_NOTIFICATIONS granted and the default, unmodified channel settings.
        val cards = CardNotificationManager(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            for (type in listOf(BarcodeType.QR_CODE, BarcodeType.CODE_128)) {
                assertTrue(
                    "Grant notification permission before running this test",
                    postCard(
                        SampleCard.card.copy(barcodeType = type),
                        CardNotificationStyle.CUSTOM_CARD
                    )
                )
                val posted =
                    manager.activeNotifications.single { it.id == CardNotificationManager.ID && it.tag == null }.notification
                assertEquals(Notification.VISIBILITY_PUBLIC, posted.visibility)
                assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
                assertTrue(posted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                val channel = manager.getNotificationChannel(cards.channelId)
                assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
                assertNull(channel.sound)
                assertFalse(channel.shouldVibrate())
                assertNotNull(posted.contentIntent)
                assertEquals(context.getString(R.string.show_card), posted.actions.single().title)
                assertEquals(posted.contentIntent, posted.actions.single().actionIntent)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    for ((index, remote) in listOf(
                        posted.contentView,
                        posted.bigContentView
                    ).withIndex()) {
                        val root = remote.apply(context, FrameLayout(context))
                        val image = root.findViewById<ImageView>(R.id.barcode)
                        assertEquals(ImageView.ScaleType.CENTER, image.scaleType)
                        val area = root.findViewById<View>(R.id.scan_area)
                        if (index == 0 && type == BarcodeType.QR_CODE) {
                            assertEquals(View.GONE, area.visibility)
                            continue
                        }
                        val density = context.resources.displayMetrics.density
                        root.measure(
                            View.MeasureSpec.makeMeasureSpec(
                                (320 * density).roundToInt(),
                                View.MeasureSpec.EXACTLY
                            ),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        )
                        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                        // Decode the displayed pixels, including the white surface and padding.
                        val displayed =
                            Bitmap.createBitmap(area.width, area.height, Bitmap.Config.ARGB_8888)
                        val bounds = android.graphics.Rect(0, 0, area.width, area.height)
                        (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(
                            area,
                            bounds
                        )
                        val canvas = Canvas(displayed)
                        canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
                        prepareSoftwareCapture(root)
                        root.draw(canvas)
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
                        val decoded = MultiFormatReader().decode(
                            BinaryBitmap(
                                HybridBinarizer(
                                    RGBLuminanceSource(displayed.width, displayed.height, pixels)
                                )
                            )
                        )
                        assertEquals(SampleCard.card.rawValue, decoded.text)
                        assertEquals(Color.WHITE, displayed.getPixel(displayed.width / 2, 0))
                        assertEquals(
                            Color.WHITE,
                            displayed.getPixel(displayed.width / 2, displayed.height - 1)
                        )
                    }
                }
            }
            cards.hide()
            awaitActiveTags(emptySet())
        } finally {
            cards.hide()
        }
    }

    @Test
    fun nativePicturesUseStandardTemplateAndContainDecodableCodes() {
        val cards = CardNotificationManager(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            for (type in listOf(BarcodeType.QR_CODE, BarcodeType.CODE_128)) {
                for (preview in listOf(false, true)) {
                    assertTrue(
                        "Grant notification permission before running this test",
                        postCard(
                            SampleCard.card.copy(barcodeType = type),
                            CardNotificationStyle.NATIVE_PICTURE, collapsedPreview = preview
                        )
                    )
                    val posted =
                        manager.activeNotifications.single { it.id == CardNotificationManager.ID && it.tag == null }.notification
                    assertEquals(
                        Notification.BigPictureStyle::class.java.name,
                        posted.extras.getString(Notification.EXTRA_TEMPLATE)
                    )
                    assertNull(posted.contentView)
                    assertNull(posted.bigContentView)
                    assertEquals(
                        preview,
                        posted.extras.getBoolean(Notification.EXTRA_SHOW_BIG_PICTURE_WHEN_COLLAPSED)
                    )
                    assertEquals(Notification.VISIBILITY_PUBLIC, posted.visibility)
                    assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
                    val picture = postedPicture(posted)
                    val pixels = IntArray(picture.width * picture.height)
                    picture.getPixels(pixels, 0, picture.width, 0, 0, picture.width, picture.height)
                    val decoded = MultiFormatReader().decode(
                        BinaryBitmap(
                            HybridBinarizer(
                                RGBLuminanceSource(picture.width, picture.height, pixels)
                            )
                        )
                    )
                    assertEquals(SampleCard.card.rawValue, decoded.text)
                    assertEquals(type.name, decoded.barcodeFormat.name)
                    assertNotNull(posted.actions.single().actionIntent)
                }
            }
        } finally {
            cards.hide()
        }
    }

    @Test
    fun wideExpandedKeepsCompactCodeAndUsesNativePictureTemplate() {
        val cards = CardNotificationManager(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            for (type in listOf(BarcodeType.QR_CODE, BarcodeType.CODE_128)) {
                assertTrue(
                    "Grant notification permission before running this test",
                    postCard(
                        SampleCard.card.copy(barcodeType = type),
                        CardNotificationStyle.WIDE_EXPANDED
                    )
                )
                val posted =
                    manager.activeNotifications.single { it.id == CardNotificationManager.ID && it.tag == null }.notification
                assertEquals(
                    Notification.BigPictureStyle::class.java.name,
                    posted.extras.getString(Notification.EXTRA_TEMPLATE)
                )
                assertNotNull(posted.contentView)
                assertNull(
                    "Expanded layout must come from the system picture template",
                    posted.bigContentView
                )
                assertFalse(posted.extras.getBoolean(Notification.EXTRA_SHOW_BIG_PICTURE_WHEN_COLLAPSED))
                assertNull(posted.getLargeIcon())
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val root = posted.contentView.apply(context, FrameLayout(context))
                    val image = root.findViewById<ImageView>(R.id.barcode)
                    assertEquals(ImageView.ScaleType.CENTER, image.scaleType)
                    val area = root.findViewById<View>(R.id.scan_area)
                    if (type == BarcodeType.QR_CODE) {
                        assertEquals(View.GONE, area.visibility)
                    } else {
                        val bitmap =
                            (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                        val density = context.resources.displayMetrics.density
                        assertEquals((240 * density).roundToInt(), bitmap.width)
                        assertEquals((24 * density).roundToInt(), bitmap.height)
                        assertDecodesSample(bitmap)
                    }
                }
                assertDecodesSample(postedPicture(posted))
            }
        } finally {
            cards.hide()
        }
    }

    // RemoteViews may return hardware bitmaps after notification IPC on newer Android builds.
    // Copy their pixels without scaling so software captures still test the displayed geometry.
    private fun prepareSoftwareCapture(view: View) {
        if (view is ImageView) {
            val bitmap = (view.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bitmap?.config == Bitmap.Config.HARDWARE) view.setImageBitmap(
                bitmap.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )
            )
        }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) prepareSoftwareCapture(view.getChildAt(index))
        }
    }

    private fun assertDecodesSample(bitmap: Bitmap, expected: String = SampleCard.card.rawValue) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(
            Bitmap.Config.ARGB_8888,
            false
        ) else bitmap
        readable.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        if (readable !== bitmap) readable.recycle()
        val decoded = MultiFormatReader().decode(
            BinaryBitmap(
                HybridBinarizer(
                    RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                )
            )
        )
        assertEquals(expected, decoded.text)
    }

    @Test
    fun floatingCardDrawsSharpScannableCodesAfterResizing() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val density = context.resources.displayMetrics.density
            for (type in listOf(BarcodeType.QR_CODE, BarcodeType.CODE_128)) {
                val view = FloatingBarcodeView(context, SampleCard.card.copy(barcodeType = type))
                // Reuse the same view to exercise regeneration after a window size change.
                for ((width, height) in listOf(312 to 144, 512 to 240, 280 to 96)) {
                    val w = (width * density).roundToInt()
                    val h = (height * density).roundToInt()
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                    )
                    view.layout(0, 0, w, h)
                    val displayed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(displayed))
                    assertDecodesSample(displayed)
                    val pixels = IntArray(w * h)
                    displayed.getPixels(pixels, 0, w, 0, 0, w, h)
                    assertTrue(
                        "The displayed code must have no interpolated gray pixels",
                        pixels.all { it == Color.BLACK || it == Color.WHITE })
                    for (x in 0 until w) {
                        assertEquals(Color.WHITE, displayed.getPixel(x, 0))
                        assertEquals(Color.WHITE, displayed.getPixel(x, h - 1))
                    }
                    for (y in 0 until h) {
                        assertEquals(Color.WHITE, displayed.getPixel(0, y))
                        assertEquals(Color.WHITE, displayed.getPixel(w - 1, y))
                    }
                }
            }
        }
    }

    @Test
    fun widgetCodesFitReportedSizesAndHideOutsideStores() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val density = context.resources.displayMetrics.density
            for (type in listOf(BarcodeType.QR_CODE, BarcodeType.CODE_128)) {
                for ((width, height) in listOf(220 to 240, 300 to 340, 560 to 260, 840 to 220)) {
                    val card = SampleCard.card.copy(barcodeType = type)
                    val size = android.util.SizeF(width.toFloat(), height.toFloat())
                    val remote = CardWidgetRenderer.render(context, card, size, true)
                    val root = remote.apply(context, FrameLayout(context))
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(
                            (width * density).roundToInt(),
                            View.MeasureSpec.EXACTLY
                        ),
                        View.MeasureSpec.makeMeasureSpec(
                            (height * density).roundToInt(),
                            View.MeasureSpec.EXACTLY
                        )
                    )
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    val area = root.findViewById<View>(R.id.widget_scan_area)
                    val displayed =
                        Bitmap.createBitmap(area.width, area.height, Bitmap.Config.ARGB_8888)
                    area.draw(Canvas(displayed))
                    assertDecodesSample(displayed)
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
                    assertTrue(pixels.all { it == Color.BLACK || it == Color.WHITE })
                    assertEquals(View.GONE, root.findViewById<View>(R.id.widget_message).visibility)
                    assertEquals(Color.WHITE, displayed.getPixel(0, displayed.height / 2))
                    assertEquals(Color.WHITE, displayed.getPixel(displayed.width / 2, 0))
                    if (type == BarcodeType.CODE_128) {
                        assertEquals(
                            "The scanning area must use the whole card width",
                            root.width,
                            area.width
                        )
                        val image = root.findViewById<ImageView>(R.id.widget_code)
                        val bitmap =
                            (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                        assertEquals(
                            "Generate for the displayed width, without a fixed pixel cap",
                            area.width,
                            bitmap.width
                        )
                        val row = displayed.height / 2
                        val first = (0 until displayed.width).first {
                            displayed.getPixel(
                                it,
                                row
                            ) == Color.BLACK
                        }
                        val last = (0 until displayed.width).last {
                            displayed.getPixel(
                                it,
                                row
                            ) == Color.BLACK
                        }
                        assertTrue(
                            "Bars should dominate the card, leaving only quiet zones and integer-module rounding",
                            last - first + 1 >= root.width * (if (root.width >= 400) 0.75f else 0.5f)
                        )
                    }

                    val outside = CardWidgetRenderer.render(context, card, size, false)
                        .apply(context, FrameLayout(context))
                    outside.measure(
                        View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY)
                    )
                    outside.layout(0, 0, root.width, root.height)
                    val hidden =
                        Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    outside.draw(Canvas(hidden))
                    val hiddenPixels = IntArray(hidden.width * hidden.height)
                    hidden.getPixels(
                        hiddenPixels,
                        0,
                        hidden.width,
                        0,
                        0,
                        hidden.width,
                        hidden.height
                    )
                    assertTrue(
                        "Outside a store there must be no visible widget pixels",
                        hiddenPixels.all { Color.alpha(it) == 0 })
                    assertFalse("No invisible card-opening tap target", outside.isClickable)
                    assertNull(outside.findViewById<View>(R.id.widget_code))
                    assertNull(outside.findViewById<View>(R.id.widget_message))
                }
            }
        }
    }

    @Test
    fun legacyWidgetHostUsesWideBarcodeInLandscape() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val options = android.os.Bundle().apply {
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280)
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 600)
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 320)
            }
            val remote = CardWidgetRenderer.renderForHost(context, SampleCard.card, options, true)
            for ((orientation, size) in listOf(
                android.content.res.Configuration.ORIENTATION_PORTRAIT to (280 to 320),
                android.content.res.Configuration.ORIENTATION_LANDSCAPE to (600 to 180),
            )) {
                val configuration =
                    android.content.res.Configuration(context.resources.configuration).apply {
                        this.orientation = orientation
                    }
                val host = context.createConfigurationContext(configuration)
                val root = remote.apply(host, FrameLayout(host))
                val w = (size.first * host.resources.displayMetrics.density).roundToInt()
                val h = (size.second * host.resources.displayMetrics.density).roundToInt()
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                )
                root.layout(0, 0, w, h)
                val image = root.findViewById<ImageView>(R.id.widget_code)
                val bitmap = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                assertEquals(
                    "The host orientation must select a barcode matching its full width",
                    w,
                    bitmap.width
                )
                assertDecodesSample(bitmap)
            }
        }
    }

    @Test
    fun recreatedNotificationManagerRetainsSelectedAppearanceForAutomaticPosts() {
        val settings = LocationSettings(context)
        val originalStyle = settings.notificationStyle
        val originalPreview = settings.collapsedPreview
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            for (style in CardNotificationStyle.entries) {
                settings.notificationStyle = style
                settings.collapsedPreview = true
                val recreatedSettings = LocationSettings(context)
                assertEquals(style, recreatedSettings.notificationStyle)
                assertTrue(recreatedSettings.collapsedPreview)
                assertTrue(
                    "Grant notification permission before running this test",
                    postAndAwait(SampleCard.card) {
                        CardNotificationManager(context).showSaved(
                            SampleCard.card
                        )
                    })
                val posted =
                    manager.activeNotifications.single { it.id == CardNotificationManager.ID && it.tag == null }.notification
                val nativePicture =
                    style == CardNotificationStyle.NATIVE_PICTURE || style == CardNotificationStyle.WIDE_EXPANDED
                val template = posted.extras.getString(Notification.EXTRA_TEMPLATE)
                if (style == CardNotificationStyle.SAMSUNG_NOW_BAR && SamsungNowBar.isSupported) {
                    assertTrue(
                        template in setOf(
                            Notification.DecoratedCustomViewStyle::class.java.name,
                            "android.app.Notification\$OngoingActivityStyle"
                        )
                    )
                    assertNotNull(
                        posted.extras.getParcelable<android.widget.RemoteViews>(
                            "android.ongoingActivityNoti.expandedRemoteView"
                        )
                    )
                } else {
                    assertEquals(
                        if (nativePicture) Notification.BigPictureStyle::class.java.name
                        else Notification.DecoratedCustomViewStyle::class.java.name, template
                    )
                }
                assertEquals(
                    style == CardNotificationStyle.NATIVE_PICTURE,
                    posted.extras.getBoolean(Notification.EXTRA_SHOW_BIG_PICTURE_WHEN_COLLAPSED)
                )
                assertEquals(
                    style != CardNotificationStyle.NATIVE_PICTURE,
                    posted.contentView != null
                )
                assertEquals(!nativePicture, posted.bigContentView != null)
                if (nativePicture) assertDecodesSample(postedPicture(posted))
            }
        } finally {
            settings.notificationStyle = originalStyle
            settings.collapsedPreview = originalPreview
            CardNotificationManager(context).hide()
        }
    }

    @Test
    fun realMembershipNotificationsOpenTheirOwnQrAndExitIndependently() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val notifications = CardNotificationManager(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val foreground = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue(
                "Grant notification permission before running this test",
                notifications.syncNearby(TestMemberships.cards)
            )
            awaitActiveTags(TestMemberships.cards.map { it.id }.toSet())
            val posted = manager.activeNotifications.filter { it.id == CardNotificationManager.ID }
            assertEquals(TestMemberships.cards.map { it.id }.toSet(), posted.map { it.tag }.toSet())
            assertNotEquals(
                posted[0].notification.contentIntent,
                posted[1].notification.contentIntent
            )
            for (card in TestMemberships.cards) {
                posted.single { it.tag == card.id }.notification.contentIntent.send()
                val activity = awaitOpenedCard(card.id)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertEquals(card.id, activity.intent.getStringExtra(CardActivity.CARD_ID))
                    val image = activity.findViewById<ImageView>(R.id.barcode)
                    val bitmap = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                    assertDecodesSample(bitmap, card.rawValue)
                    activity.finish()
                }
            }
            notifications.syncNearby(listOf(TestMemberships.cards.last()))
            awaitActiveTags(setOf(TestMemberships.cards.last().id))
            notifications.syncNearby(emptyList())
            awaitActiveTags(emptySet())
        } finally {
            foreground.close()
            notifications.syncNearby(LocationSettings(context).activeCards())
        }
    }

    @Test
    fun samsungCardKeepsCompactNameSeparateFromScannableExpansion() {
        if (SamsungNowBar.isSupported) LocationSettings(context).notificationPresentation = NotificationPresentation.AUTOMATIC
        val notifications = CardNotificationManager(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            for (card in TestMemberships.cards + TestCard.card) {
                assertTrue(
                    "Grant notification permission before running this test",
                    postCard(card, CardNotificationStyle.SAMSUNG_NOW_BAR)
                )
                val posted = manager.activeNotifications.single {
                    it.id == CardNotificationManager.ID && it.tag == card.id
                }.notification
                // On a Pixel this exercises the Samsung payload separately; only
                // a Samsung run can verify the actual native-style integration.
                val extras = if (SamsungNowBar.isSupported) posted.extras
                else SamsungNowBar.extras(
                    context,
                    card,
                    NotificationCardRenderer.createSamsung(context, card)
                )
                assertFalse(extras.containsKey("android.ongoingActivityNoti.chronometerRemoteView"))
                assertFalse(extras.containsKey("android.ongoingActivityNoti.nowbarRemoteView"))
                assertEquals(
                    "${card.brandName} ${card.cardName}",
                    extras.getString("android.ongoingActivityNoti.nowbarPrimaryInfo")
                )
                assertEquals(
                    card.brandColor,
                    extras.getInt("android.ongoingActivityNoti.chipBgColor")
                )
                val expandedViews = mutableListOf(posted.bigContentView)
                for (key in listOf(
                    "android.ongoingActivityNoti.expandedRemoteView",
                    "android.ongoingActivityNoti.subScreenExpandedRemoteView"
                )) {
                    expandedViews += requireNotNull(
                        extras.getParcelable<android.widget.RemoteViews>(
                            key
                        )
                    )
                }
                if (posted.extras.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$OngoingActivityStyle") {
                    for (key in listOf(
                        "android.ongoingActivityCustomExpandedCardView",
                        "android.ongoingActivityExpandedNowBarView"
                    )) {
                        expandedViews += requireNotNull(
                            extras.getParcelable<android.widget.RemoteViews>(
                                key
                            )
                        )
                    }
                }
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val compact = posted.contentView.apply(context, FrameLayout(context))
                    assertEquals(
                        "${card.brandName} ${card.cardName}",
                        compact.findViewById<android.widget.TextView>(R.id.title).text.toString()
                    )
                    val compactCode = compact.findViewById<ImageView>(R.id.barcode)
                    if (card.barcodeType == BarcodeType.QR_CODE) {
                        assertEquals(
                            View.GONE,
                            compact.findViewById<View>(R.id.scan_area).visibility
                        )
                        assertNull(compactCode.drawable)
                    } else {
                        assertEquals(
                            View.VISIBLE,
                            compact.findViewById<View>(R.id.scan_area).visibility
                        )
                        assertDecodesSample(
                            (compactCode.drawable as android.graphics.drawable.BitmapDrawable).bitmap,
                            card.rawValue
                        )
                    }

                    for (remote in expandedViews) {
                        val expanded = remote.apply(context, FrameLayout(context))
                        val width = NotificationCardRenderer.samsungWidth(context)
                        expanded.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        )
                        expanded.layout(0, 0, expanded.measuredWidth, expanded.measuredHeight)
                        assertEquals(
                            if (card.barcodeType == BarcodeType.QR_CODE) "${card.brandName}\n${card.cardName}" else "${card.brandName} ${card.cardName}",
                            expanded.findViewById<android.widget.TextView>(R.id.title).text.toString()
                        )
                        val header = expanded.findViewById<View>(R.id.brand_header)
                        val branding = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                        val brandingCanvas = Canvas(branding)
                        brandingCanvas.translate(-header.width / 2f, -header.height / 2f)
                        val originalBounds = android.graphics.Rect(header.background.bounds)
                        header.background.setBounds(0, 0, header.width, header.height)
                        header.background.draw(brandingCanvas)
                        header.background.bounds = originalBounds
                        assertEquals(
                            "The displayed brand background must keep its color",
                            card.brandColor,
                            branding.getPixel(0, 0)
                        )
                        val image = expanded.findViewById<ImageView>(R.id.barcode)
                        val bitmap =
                            (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                        assertEquals(bitmap.width, image.width)
                        assertEquals(bitmap.height, image.height)
                        val displayed =
                            Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                        prepareSoftwareCapture(image)
                        image.draw(Canvas(displayed))
                        assertDecodesSample(displayed, card.rawValue)
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
                        // Notification IPC/readback on the emulator shifts endpoints by one
                        // level. Reject intermediate shades (resampling), while direct renderer
                        // tests still require exact black/white and this view must decode.
                        assertTrue(
                            "Posted code must remain opaque with only black/white endpoints",
                            pixels.all { pixel ->
                                Color.alpha(pixel) == 255 &&
                                        listOf(
                                            Color.red(pixel),
                                            Color.green(pixel),
                                            Color.blue(pixel)
                                        ).let { rgb ->
                                            rgb.all { it <= 1 } || rgb.all { it >= 254 }
                                        }
                            })
                    }
                }
            }
        } finally {
            notifications.syncNearby(LocationSettings(context).activeCards())
        }
    }
}
