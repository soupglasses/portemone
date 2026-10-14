// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import dev.finnes.portemone.R
import dev.finnes.portemone.notifications.integrations.SamsungNowBar

internal data class SettingCheck(val ready: Boolean, val detail: String, val unknown: Boolean = false)

/** Read-only probes. Missing, restricted, or changed OEM keys never mean “enabled”. */
internal class NotificationSettingsProbe(private val context: Context) {
    // AOSP marks these lock-screen keys @Readable, but they are not stable SDK constants.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/provider/Settings.java
    private fun secureBoolean(key: String): Boolean? = try {
        when (Settings.Secure.getString(context.contentResolver, key)) {
            "0" -> false
            "1" -> true
            else -> null
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun lockScreen(channel: NotificationChannel?): SettingCheck {
        if (channel == null) return SettingCheck(false, context.getString(R.string.the_card_notification_category_is_unavailable))
        if (channel.importance == NotificationManager.IMPORTANCE_NONE ||
            channel.group?.let { context.getSystemService(NotificationManager::class.java).getNotificationChannelGroup(it)?.isBlocked } == true)
            return SettingCheck(false, context.getString(R.string.the_card_notification_category_is_blocked))
        if (channel.lockscreenVisibility in setOf(Notification.VISIBILITY_PRIVATE, Notification.VISIBILITY_SECRET))
            return SettingCheck(false, context.getString(R.string.the_card_category_hides_lock_screen_content))
        val show = secureBoolean("lock_screen_show_notifications")
        if (show == false) return SettingCheck(false, context.getString(R.string.lock_screen_notifications_are_switched_off))
        if (show == null) return SettingCheck(false, context.getString(R.string.android_did_not_expose_the_lock_screen_notification_setting), unknown = true)
        if (channel.importance < NotificationManager.IMPORTANCE_DEFAULT) {
            when (secureBoolean("lock_screen_show_silent_notifications")) {
                false -> return SettingCheck(false, context.getString(R.string.silent_notifications_are_hidden_on_the_lock_screen))
                null -> return SettingCheck(false, context.getString(R.string.could_not_verify_whether_silent_cards_appear_on_the), unknown = true)
                true -> Unit
            }
        }
        return SettingCheck(true, if (SamsungNowBar.isSamsung)
            context.getString(R.string.global_notifications_and_card_category_allow_display_see_samsung)
        else context.getString(R.string.global_lock_screen_notifications_and_card_category_allow_display))
    }

    fun samsungLive(): SettingCheck {
        if (!SamsungNowBar.isSupported)
            return SettingCheck(false, context.getString(R.string.this_samsung_option_is_unavailable_on_this_phone), unknown = true)
        // Verified in One UI 8's NotificationNowBarPreferenceController and read from
        // an ordinary app UID on Fold4. This is separate from per-app Now Bar access.
        return when (secureBoolean("enable_notification_nowbar_test")) {
            true -> SettingCheck(true, context.getString(R.string.developer_option_is_on_read_from_samsung_settings))
            false -> SettingCheck(false, context.getString(R.string.live_notifications_for_all_apps_is_switched_off))
            null -> SettingCheck(false, context.getString(R.string.samsung_did_not_expose_the_developer_option_state), unknown = true)
        }
    }
}
