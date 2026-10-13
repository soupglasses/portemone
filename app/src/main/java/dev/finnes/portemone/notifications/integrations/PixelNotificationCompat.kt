// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.notifications.integrations

import dev.finnes.portemone.AppDiagnostics
import dev.finnes.portemone.Card
import dev.finnes.portemone.CardActivity
import dev.finnes.portemone.NotificationCardRenderer
import dev.finnes.portemone.R

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.TypedValue
import kotlin.math.roundToInt

/** Optional notification host. Location and membership data remain in the modern app. */
class PixelNotificationCompat(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    data class Status(
        val installed: Boolean, val ready: Boolean, val description: String,
        val blocked: Boolean = false
    )

    fun status(): Status = try {
        val info = context.packageManager.getApplicationInfo(PACKAGE, 0)
        when {
            Build.VERSION.SDK_INT < 33 ->
                Status(
                    true,
                    false,
                    context.getString(R.string.full_card_notifications_need_android_13_or_later_standard)
                )

            context.packageManager.checkSignatures(
                context.packageName,
                PACKAGE
            ) != PackageManager.SIGNATURE_MATCH ->
                Status(true, false, context.getString(R.string.the_helper_must_be_signed_by_the_same_publisher))

            info.targetSdkVersion != 30 ->
                Status(
                    true,
                    false,
                    context.getString(R.string.this_helper_version_does_not_provide_the_tested_compatibility)
                )

            context.packageManager.checkPermission(
                Manifest.permission.POST_NOTIFICATIONS, PACKAGE
            ) != PackageManager.PERMISSION_GRANTED ->
                Status(true, false, context.getString(R.string.notifications_are_disabled_for_the_helper), blocked = true)

            else -> {
                val channel = context.createPackageContext(PACKAGE, 0)
                    .getSystemService(NotificationManager::class.java)
                    .getNotificationChannel(CHANNEL)
                when {
                    channel == null -> Status(true, false, context.getString(R.string.open_the_helper_to_finish_setup))
                    channel.importance == NotificationManager.IMPORTANCE_NONE ->
                        Status(
                            true,
                            false,
                            context.getString(R.string.the_helper_s_card_notification_channel_is_disabled),
                            blocked = true
                        )

                    !info.enabled -> Status(
                        true,
                        false,
                        context.getString(R.string.enable_the_helper_in_android_app_settings)
                    )

                    !manager.canNotifyAsPackage(PACKAGE) ->
                        Status(true, false, context.getString(R.string.open_the_helper_to_allow_portemon_to_show_cards))

                    else -> Status(
                        true,
                        true,
                        context.getString(R.string.helper_connected_full_card_layouts_are_experimental_on_pixel)
                    )
                }
            }
        }
    } catch (_: PackageManager.NameNotFoundException) {
        Status(
            false,
            false,
            context.getString(R.string.install_the_optional_portemon_notification_helper_to_try_full)
        )
    } catch (error: RuntimeException) {
        AppDiagnostics.event("PixelCompat", "Cannot inspect helper availability", error)
        Status(true, false, context.getString(R.string.the_helper_is_unavailable_standard_notifications_will_be_used))
    }

    fun setupIntent(): Intent = Intent().setClassName(PACKAGE, "$PACKAGE.SetupActivity")

    /** Returns null when unavailable, false when blocked, true when the system accepted the post. */
    fun show(card: Card, tag: String?, id: Int): Boolean? {
        val state = status()
        if (!state.ready) {
            AppDiagnostics.event("PixelCompat", "${card.id}: ${state.description}")
            return if (state.blocked) false else null
        }
        return try {
            val density = context.resources.displayMetrics.density
            val width = ((context.resources.configuration.screenWidthDp - 32).coerceIn(
                200,
                396
            ) * density).roundToInt()
            val height = (width / NotificationCardRenderer.ASPECT_RATIO).roundToInt()
            fun face() =
                NotificationCardRenderer.create(context, card, width, 0f, useBitmapIcon = true)
                    .apply {
                        // SystemUI joins adjacent notifications and changes their inner corner radii.
                        // Let its outline clip the face instead of exposing a second rounded background.
                        setInt(R.id.card_face, "setBackgroundColor", android.graphics.Color.WHITE)
                        // Pixel measures a fixed outer root differently from a wrapping root. Keeping
                        // compact and expanded equal avoids a clipped code before the first expansion.
                        setViewLayoutHeight(
                            R.id.notification_surface,
                            height.toFloat(),
                            TypedValue.COMPLEX_UNIT_PX
                        )
                    }

            val open = PendingIntent.getActivity(
                context, 0, CardActivity.scanIntent(context, card),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_card)
                .setContentTitle("${card.brandName} ${card.cardName}")
                .setContentIntent(open).setCategory(Notification.CATEGORY_STATUS)
                .setColor(card.brandColor).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setAutoCancel(false)
                // Separate instances prevent compact bitmap reduction from changing expansion.
                .setCustomContentView(face()).setCustomBigContentView(face()).build()
            manager.notifyAsPackage(PACKAGE, tag, id, notification)
            val afterPost = status()
            if (!afterPost.ready) {
                cancel(tag, id)
                return if (afterPost.blocked) false else null
            }
            AppDiagnostics.event(
                "PixelCompat",
                "Posted ${card.id}: ${width}x${height}px via helper"
            )
            true
        } catch (error: RuntimeException) {
            AppDiagnostics.event(
                "PixelCompat",
                "Posting failed; use standard notification for ${card.id}",
                error
            )
            if (status().blocked) false else null
        }
    }

    fun cancel(tag: String?, id: Int) {
        try {
            if (manager.canNotifyAsPackage(PACKAGE)) manager.cancelAsPackage(PACKAGE, tag, id)
        } catch (error: RuntimeException) {
            AppDiagnostics.event("PixelCompat", "Could not remove helper notification", error)
        }
    }

    companion object {
        const val PACKAGE = "dev.finnes.portemone.notificationcompanion"
        const val CHANNEL = "nearby_cards_compat"
    }
}
