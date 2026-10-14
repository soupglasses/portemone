// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.notifications

import android.content.Context
import androidx.core.content.edit
import dev.finnes.portemone.diagnostics.AppDiagnostics
import dev.finnes.portemone.notifications.integrations.NotificationPresentation
import dev.finnes.portemone.notifications.integrations.SamsungNowBar

/** Keep the existing backing file and keys so upgrades retain presentation choices. */
class NotificationPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("local_geofences", Context.MODE_PRIVATE)

    var notificationStyle: CardNotificationStyle
        get() = if (SamsungNowBar.isSupported) CardNotificationStyle.SAMSUNG_NOW_BAR
        else runCatching {
            CardNotificationStyle.valueOf(
                preferences.getString(
                    "notification_style",
                    null
                ).orEmpty()
            )
        }
            .getOrDefault(CardNotificationStyle.CUSTOM_CARD)
        set(value) {
            if (notificationStyle != value) AppDiagnostics.event(
                "Settings",
                "notificationStyle=$value"
            )
            preferences.edit { putString("notification_style", value.name) }
        }
    var collapsedPreview: Boolean
        get() = preferences.getBoolean("collapsed_preview", false)
        set(value) {
            preferences.edit { putBoolean("collapsed_preview", value) }
        }
    var notificationPresentation: NotificationPresentation
        get() = preferences.getString("notification_presentation", null)?.let {
            runCatching { NotificationPresentation.valueOf(it) }.getOrDefault(
                NotificationPresentation.AUTOMATIC
            )
        }
            ?: if (!SamsungNowBar.isSamsung && preferences.contains("pixel_notification_compatibility") &&
                !preferences.getBoolean("pixel_notification_compatibility", false)
            ) NotificationPresentation.STANDARD
            else NotificationPresentation.AUTOMATIC
        set(value) {
            preferences.edit {
                putString("notification_presentation", value.name)
                    .remove("pixel_notification_compatibility")
            }
        }

}
