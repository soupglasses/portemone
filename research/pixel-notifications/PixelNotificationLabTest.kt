// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.notifications.integrations.SamsungNowBar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

/** Opt-in SystemUI experiments. Never run this on a user's physical phone. */
@RunWith(AndroidJUnit4::class)
class PixelNotificationLabTest {
    @Suppress("DEPRECATION")
    @Test fun postExperiment() {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("pixelNotificationVariant")
        assumeTrue("Explicit emulator experiment only", mode != null && Build.MODEL.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val system = context.getSystemService(NotificationManager::class.java)
        context.stopService(Intent().setClassName(context, "${context.packageName}.PixelLabService"))
        val channel = if (mode?.startsWith("delegated") == true) "pixel_card_lab" else "pixel_notification_lab"
        if (mode == "cleanup") {
            system.cancel("pixel-lab", 4311)
            system.cancel("pixel-lab-summary", 4312)
            system.cancel("pixel-lab-second", 4313)
            if (system.canNotifyAsPackage("dev.finnes.portemone.pixelresearch")) {
                system.cancelAsPackage("dev.finnes.portemone.pixelresearch", "pixel-lab", 4311)
            }
            system.deleteNotificationChannel(channel)
            return
        }
        system.createNotificationChannel(NotificationChannel(channel, "Pixel rendering experiment",
            NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false) })
        // Deliberately use demonstration data; screenshots must not expose a membership.
        val card = SampleCard.card.copy(cardName = "Family · test", barcodeType = BarcodeType.QR_CODE)
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).roundToInt()
        val fullWidth = mode?.startsWith("delegated") == true || mode?.startsWith("public-legacy") == true
        val width = if (fullWidth) context.resources.displayMetrics.widthPixels - px(32)
            else px(if (mode!!.startsWith("translated")) 360 else 288)
        val compact = RemoteViews(context.packageName, R.layout.notification_compact).apply {
            setTextViewText(R.id.title, "Example Two · $mode")
            setViewVisibility(R.id.scan_area, View.GONE)
        }
        val expanded = NotificationCardRenderer.create(context, card, width,
            cornerRadiusDp = if (fullWidth) 28f else 12f,
            fitHostWidth = fullWidth)
        val fixedRoot = mode in setOf("delegated-fixed", "delegated-fixed-sharp", "public-legacy-fixed", "public-legacy-helper", "public-legacy-isolated")
        if (fixedRoot) expanded.setViewLayoutHeight(R.id.notification_surface,
            (width / NotificationCardRenderer.ASPECT_RATIO).roundToInt().toFloat(), TypedValue.COMPLEX_UNIT_PX)
        lateinit var picture: Bitmap
        instrumentation.runOnMainSync {
            val view = expanded.apply(context, FrameLayout(context))
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            picture = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).apply {
                this.density = Bitmap.DENSITY_NONE
                view.draw(Canvas(this))
            }
        }
        val open = PendingIntent.getActivity(context, 4311, CardActivity.intent(context, card),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_card).setContentTitle("Example Two · $mode")
            .setContentIntent(open).setColor(card.brandColor).setShowWhen(false)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        when (mode) {
            "decorated" -> builder.setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomContentView(compact).setCustomBigContentView(expanded)
            "bare", "bare-color", "samsung-extras", "overflow", "promoted-custom", "forged-promotion", "grouped",
            "translated24", "translated48", "translated72", "fgs-color", "delegated", "delegated-full",
            "delegated-fixed", "delegated-fixed-sharp" -> {
                if (mode == "overflow") {
                    expanded.setViewLayoutWidth(R.id.card_face, px(360).toFloat(), TypedValue.COMPLEX_UNIT_PX)
                    expanded.setViewLayoutMargin(R.id.notification_surface, RemoteViews.MARGIN_LEFT, -48f,
                        TypedValue.COMPLEX_UNIT_DIP)
                }
                if (mode.startsWith("translated")) {
                    expanded.setViewLayoutWidth(R.id.notification_surface, width.toFloat(), TypedValue.COMPLEX_UNIT_PX)
                    expanded.setFloat(R.id.notification_surface, "setTranslationX", -px(mode.removePrefix("translated").toInt()).toFloat())
                }
                val small = if (mode == "delegated-full" || fixedRoot) {
                    NotificationCardRenderer.create(context, card, width, 28f, fitHostWidth = true).apply {
                        if (fixedRoot) setViewLayoutHeight(R.id.notification_surface,
                            (width / NotificationCardRenderer.ASPECT_RATIO).roundToInt().toFloat(), TypedValue.COMPLEX_UNIT_PX)
                    }
                } else compact
                if (mode == "delegated-fixed-sharp") builder.addExtras(Bundle().apply {
                    // Isolate host clipping from framework bitmap reduction in this opt-in lab.
                    putBoolean("android.reduced.images", true)
                })
                builder.setCustomContentView(small).setCustomBigContentView(expanded)
                if (mode == "grouped") builder.setGroup("pixel-lab-group")
                if (mode == "bare-color" || mode == "fgs-color") builder.setColorized(true)
                if (mode == "promoted-custom" || mode == "forged-promotion") builder.setRequestPromotedOngoing(true)
            }
            "picture", "picture-no-title", "promoted-picture" -> {
                builder.setStyle(Notification.BigPictureStyle().bigPicture(picture)
                    .setContentDescription("Example Two demonstration membership card"))
                if (mode == "picture-no-title") builder.setContentTitle(null)
                if (mode == "promoted-picture") builder.setRequestPromotedOngoing(true)
            }
            // A control for a different platform template, without claiming an active media session.
            "media-template" -> builder.setStyle(Notification.DecoratedMediaCustomViewStyle())
                .setCustomContentView(compact).setCustomBigContentView(expanded)
            "promoted-large-icon" -> builder.setRequestPromotedOngoing(true).setLargeIcon(picture)
            "promoted-public-picture" -> {
                builder.setRequestPromotedOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(Notification.Builder(context, channel)
                        .setSmallIcon(R.drawable.ic_card).setContentTitle("Example Two")
                        .setStyle(Notification.BigPictureStyle().bigPicture(picture).showBigPictureWhenCollapsed(true))
                        .build())
            }
            "public-legacy", "public-legacy-fixed", "public-legacy-helper", "public-legacy-isolated" -> {
                val public = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_card)
                    .setContentTitle("Example Two · public compatibility test")
                    .setCustomContentView(expanded).addExtras(Bundle().apply {
                        putBoolean("android.reduced.images", true)
                    }).build()
                val publicInfo = if (mode == "public-legacy-helper") {
                    context.packageManager.getApplicationInfo("dev.finnes.portemone.pixelresearch", 0)
                } else ApplicationInfo(context.applicationInfo).apply {
                    targetSdkVersion = 30
                    if (mode == "public-legacy-isolated") packageName += ".publicexperiment"
                }
                public.extras.putParcelable("android.appInfo", publicInfo)
                builder.setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
                    .setContentText("Private main view · the card belongs to the public view")
            }
            "messaging-image" -> {
                val directory = File(context.cacheDir, "debug-exports").apply { mkdirs() }
                val file = File(directory, "pixel-lab-card.png")
                file.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
                builder.setStyle(Notification.MessagingStyle(Person.Builder().setName("Viewer").build())
                    .addMessage(Notification.MessagingStyle.Message("", System.currentTimeMillis(),
                        Person.Builder().setName("Example Two · test").build()).setData("image/png", uri)))
            }
            else -> error("Unknown experiment: $mode")
        }
        val notification = builder.build()
        if (mode == "samsung-extras") notification.extras.putAll(SamsungNowBar.extras(context, card, expanded))
        if (mode == "forged-promotion") {
            notification.flags = notification.flags or Notification.FLAG_PROMOTED_ONGOING
            notification.extras.remove("android.contains.customView")
        }
        system.cancel("pixel-lab", 4311)
        system.cancel("pixel-lab-summary", 4312)
        system.cancel("pixel-lab-second", 4313)
        if (mode == "grouped") {
            system.notify("pixel-lab-summary", 4312,
                Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_card)
                    .setContentTitle("Test cards").setGroup("pixel-lab-group").setGroupSummary(true).build())
            system.notify("pixel-lab-second", 4313,
                Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_card)
                    .setContentTitle("Second test card").setGroup("pixel-lab-group").build())
        }
        if (mode == "fgs-color") {
            PixelLabPreview.notification = notification
            context.startActivity(Intent().setClassName(context, "${context.packageName}.PixelLabActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else if (mode?.startsWith("delegated") == true) {
            check(system.canNotifyAsPackage("dev.finnes.portemone.pixelresearch"))
            system.notifyAsPackage("dev.finnes.portemone.pixelresearch", "pixel-lab", 4311, notification)
        } else system.notify("pixel-lab", 4311, notification)
        Thread.sleep(if (mode == "fgs-color") 3000 else 600)
        if (mode == "fgs-color") {
            instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close()
            Thread.sleep(1000)
            File(context.cacheDir, "pixel-lab-fgs-color.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val posted = system.activeNotifications.first { it.id == 4311 }.notification
        val report = "variant=$mode\nrequestedFlags=${notification.flags}\npostedFlags=${posted.flags}\n" +
            "promotable=${notification.hasPromotableCharacteristics()}\n" +
            "promoted=${posted.flags and Notification.FLAG_PROMOTED_ONGOING != 0}\n" +
            "template=${posted.extras.getString(Notification.EXTRA_TEMPLATE)}\n" +
            "publicTarget=${posted.publicVersion?.extras?.getParcelable("android.appInfo", ApplicationInfo::class.java)?.targetSdkVersion}\n"
        File(context.cacheDir, "pixel-lab-$mode.txt").writeText(report)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", report) })
    }
}
