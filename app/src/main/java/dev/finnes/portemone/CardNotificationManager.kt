// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.notifications.integrations.NotificationBackends

import android.app.NotificationChannel
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

enum class CardNotificationStyle { NATIVE_PICTURE, CUSTOM_CARD, WIDE_EXPANDED, SAMSUNG_NOW_BAR }

class CardNotificationManager(private val context: Context) {
    private val system = context.getSystemService(NotificationManager::class.java)
    private val backends = NotificationBackends(context)
    val channelId: String = run {
        val previous = system.getNotificationChannel(LEGACY_CHANNEL)
        // Android won't raise an existing channel's importance. Move the prototype's
        // untouched low-importance default once, preserving user choices on either channel.
        when {
            system.getNotificationChannel(CHANNEL) != null -> CHANNEL
            previous != null && (previous.hasUserSetImportance() || previous.hasUserSetSound() ||
                    previous.importance == NotificationManager.IMPORTANCE_NONE ||
                    previous.lockscreenVisibility == Notification.VISIBILITY_SECRET ||
                    previous.lockscreenVisibility == Notification.VISIBILITY_PRIVATE ||
                    previous.shouldVibrate()) -> LEGACY_CHANNEL

            else -> CHANNEL
        }
    }

    init {
        if (system.getNotificationChannel(channelId) == null) {
            system.createNotificationChannel(
                NotificationChannel(
                    channelId, context.getString(R.string.cards_at_checkout),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description =
                        context.getString(R.string.membership_cards_near_a_store_with_no_sound_or)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                    setSound(null, null)
                    enableVibration(false)
                })
        }
    }

    fun show(
        card: Card,
        style: CardNotificationStyle = LocationSettings(context).notificationStyle,
        collapsedPreview: Boolean = false,
    ): Boolean {
        if (!notificationsAllowed()) {
            hide(card)
            AppDiagnostics.event("Notification", "Blocked by app/channel settings: ${card.id}")
            return false
        }
        AppDiagnostics.event("Notification", "Building ${card.id}: ${card.barcodeType}, $style")
        return try {
            backends.post(card, notificationTag(card), ID, style) {
                buildStandardNotification(card, style, collapsedPreview)
            }
        } catch (error: SecurityException) {
            AppDiagnostics.event("Notification", "Posting denied: ${card.id}", error)
            false
        }
    }

    private fun buildStandardNotification(
        card: Card, style: CardNotificationStyle,
        collapsedPreview: Boolean
    ): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            CardActivity.scanIntent(context, card),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_card)
            .setContentTitle("${card.brandName} ${card.cardName}")
            .setColor(card.brandColor)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .addAction(R.drawable.ic_card, context.getString(R.string.show_card), open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setAutoCancel(false)
        when (style) {
            // Retain exact compact pixels and delegate expansion to the native picture
            // template. That template still reserves the system's icon column on Pixel;
            // it does not provide edge-to-edge content or guarantee unscaled barcode pixels.
            CardNotificationStyle.WIDE_EXPANDED -> builder
                .setCustomContentView(view(card, false))
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(picture(card))
                        .setContentDescription(codeDescription(card))
                        .showBigPictureWhenCollapsed(false)
                )

            CardNotificationStyle.NATIVE_PICTURE -> builder
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(picture(card))
                        .setContentDescription(codeDescription(card))
                        .showBigPictureWhenCollapsed(collapsedPreview)
                )

            CardNotificationStyle.CUSTOM_CARD, CardNotificationStyle.SAMSUNG_NOW_BAR -> builder
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(view(card, false))
                .setCustomBigContentView(view(card, true))
        }
        return builder.build()
    }

    private fun notificationTag(card: Card): String? =
        if (card.id == SampleCard.card.id) null else card.id

    fun hide(card: Card) {
        hide(card.id)
    }

    internal fun hide(cardId: String) {
        backends.cancel(if (cardId == SampleCard.card.id) null else cardId, ID)
        AppDiagnostics.event("Notification", "Removed $cardId")
    }

    fun hide() {
        AppDiagnostics.event("Notification", "Removing all card notifications")
        LocationSettings(context).cards().forEach { backends.cancel(notificationTag(it), ID) }
        system.activeNotifications.filter { it.id == ID }.forEach { backends.cancel(it) }
    }

    fun syncNearby(cards: List<Card>, onlyMissing: Boolean = false): Boolean {
        AppDiagnostics.event("Notification", "Sync nearby cards: ${cards.map { it.id }}")
        if (!notificationsAllowed()) {
            hide()
            return cards.isEmpty()
        }
        val backend = backends.state()
        if (backend.blocked) {
            hide()
            return cards.isEmpty()
        }
        val activeTags = cards.map(::notificationTag).toSet()
        val current = system.activeNotifications.filter { it.id == ID }
        // OEM surfaces can outlive their entry in activeNotifications. Cancel by
        // saved identity as well, so an EXIT does not depend on that enumeration.
        LocationSettings(context).cards().filter { notificationTag(it) !in activeTags }
            .forEach { hide(it) }
        current.filter { it.tag !in activeTags }
            .forEach { backends.cancel(it) }
        AppDiagnostics.event(
            "Notification",
            "Presence sync: desired=${cards.map { it.id }}, observed=${current.map { it.tag }}, host=${backend.ownerPackage}"
        )
        val host = backend.ownerPackage
        val results = cards.filter { card ->
            !onlyMissing || current.none {
                it.tag == notificationTag(card) && it.packageName == host
            }
        }.map { showSaved(it) }
        return results.all { it }
    }

    // Receivers are recreated independently of the Activity. Use the saved selection so a
    // location event cannot silently replace a native-picture trial with a custom layout.
    fun showSaved(card: Card): Boolean {
        if (!card.isUsable()) {
            hide(card); return false
        }
        val settings = LocationSettings(context)
        return show(card, settings.notificationStyle, settings.collapsedPreview)
    }

    fun usesLowImportance() =
        system.getNotificationChannel(channelId).importance < NotificationManager.IMPORTANCE_DEFAULT

    private fun notificationsAllowed() =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                system.getNotificationChannel(channelId).importance != NotificationManager.IMPORTANCE_NONE

    private fun px(dp: Int) = (dp * context.resources.displayMetrics.density).roundToInt()

    private fun codeDescription(card: Card) = context.getString(
        R.string.membership_code_description, card.brandName,
        context.getString(card.barcodeType.labelRes)
    )

    private fun picture(card: Card): Bitmap {
        // This experiment gives sizing to System UI. Unlike RemoteViews, BigPictureStyle
        // may scale the image; only the full-screen card guarantees native barcode pixels.
        // A wide white canvas keeps the QR square and leaves room around both formats.
        val qr = card.barcodeType.square
        val code = if (card.barcodeType == BarcodeType.NONE) null else runCatching {
            BarcodeRenderer.render(
                card.rawValue, card.barcodeType,
                if (qr) 540 else 880, if (qr) 540 else 320, card.barcodeEncoding
            )
        }.getOrNull()
        return Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
            val canvas = Canvas(this)
            canvas.drawColor(Color.WHITE)
            if (code != null) canvas.drawBitmap(
                code, ((width - code.width) / 2).toFloat(),
                ((height - code.height) / 2).toFloat(), Paint().apply {
                    isAntiAlias = false
                    isFilterBitmap = false
                })
        }
    }

    private fun view(card: Card, expanded: Boolean): RemoteViews {
        if (expanded) return NotificationCardRenderer.create(context, card, ordinaryExpandedWidth())
        val views = RemoteViews(context.packageName, R.layout.notification_compact)
        views.setTextViewText(R.id.title, "${card.brandName} ${card.cardName}")
        val qr = card.barcodeType.square
        if (qr) {
            val titleInk = if (ColorUtils.calculateContrast(Color.WHITE, card.brandColor) >= 4.5)
                Color.WHITE else Color.BLACK
            views.setInt(
                R.id.notification_surface,
                "setBackgroundResource",
                R.drawable.notification_card_background
            )
            views.setInt(R.id.title, "setBackgroundColor", card.brandColor)
            views.setTextColor(R.id.title, titleInk)
            views.setViewLayoutHeight(R.id.title, 48f, android.util.TypedValue.COMPLEX_UNIT_DIP)
            views.setViewPadding(R.id.title, px(12), 0, px(12), 0)
            views.setTextViewTextSize(R.id.title, android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        views.setViewVisibility(R.id.scan_area, if (qr) View.GONE else View.VISIBLE)
        if (!qr) {
            val code = if (card.barcodeType == BarcodeType.NONE) null else runCatching {
                BarcodeRenderer.render(
                    card.rawValue,
                    card.barcodeType,
                    px(240),
                    px(24),
                    card.barcodeEncoding
                )
            }.getOrNull()
            if (code != null) {
                views.setImageViewBitmap(R.id.barcode, code)
                views.setContentDescription(R.id.barcode, codeDescription(card))
            } else views.setViewVisibility(R.id.scan_area, View.GONE)
        }
        return views
    }

    @Suppress("DEPRECATION")
    private fun ordinaryExpandedWidth(): Int {
        // Measure the platform's actual decorated template rather than guessing its
        // icon gutter. Samsung's separate full-width slot does not use this width.
        return runCatching {
            val placeholder = RemoteViews(context.packageName, R.layout.notification_expanded)
            val builder = Notification.Builder(context, channelId).setSmallIcon(R.drawable.ic_card)
                .setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomBigContentView(placeholder)
            val template = builder.createBigContentView().apply(context, FrameLayout(context))
            val panelWidth = context.resources.displayMetrics.widthPixels - px(24)
            template.measure(
                View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            template.findViewById<View>(R.id.notification_surface).measuredWidth
                .coerceIn(px(200), px(396))
        }.getOrElse { px((context.resources.configuration.screenWidthDp - 120).coerceIn(200, 320)) }
    }

    companion object {
        const val CHANNEL = "nearby_cards_checkout"
        private const val LEGACY_CHANNEL = "nearby_cards"
        const val ID = 1001
    }
}
