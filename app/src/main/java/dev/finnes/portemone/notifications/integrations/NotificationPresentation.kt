// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.notifications.integrations

import dev.finnes.portemone.R
import android.content.Context
import dev.finnes.portemone.LocationSettings

/** User preference is independent of the device-specific notification host. */
enum class NotificationPresentation(@param:androidx.annotation.StringRes val labelRes: Int) {
    AUTOMATIC(R.string.locationengine_automatic), STANDARD(R.string.notificationpresentation_standard);
}

internal enum class NotificationHost { STANDARD, SAMSUNG, HELPER }

internal fun notificationHost(preference: NotificationPresentation, samsungSupported: Boolean, samsungDevice: Boolean): NotificationHost =
    when {
        preference == NotificationPresentation.STANDARD -> NotificationHost.STANDARD
        samsungSupported -> NotificationHost.SAMSUNG
        samsungDevice -> NotificationHost.STANDARD
        else -> NotificationHost.HELPER
    }

/** Presentation settings describe one feature, regardless of where the card is posted. */
internal class NotificationPresentationStatus(context: Context) {
    val helper = PixelNotificationCompat(context)
    val helperStatus = helper.status()
    val preference = LocationSettings(context).notificationPresentation
    val needsHelper = preference == NotificationPresentation.AUTOMATIC && !SamsungNowBar.isSamsung
    val title = when {
        preference == NotificationPresentation.STANDARD -> context.getString(R.string.using_standard_notifications)
        SamsungNowBar.availableForTrial || needsHelper && helperStatus.ready -> context.getString(R.string.using_full_card_notifications)
        needsHelper && helperStatus.blocked -> context.getString(R.string.card_notifications_are_blocked)
        else -> context.getString(R.string.using_standard_notifications)
    }
    val description = when {
        preference == NotificationPresentation.STANDARD -> context.getString(R.string.cards_use_the_regular_android_notification_layout)
        SamsungNowBar.availableForTrial -> context.getString(R.string.full_cards_are_built_into_this_phone_lock_screen)
        needsHelper && helperStatus.ready -> context.getString(R.string.the_card_helper_is_connected_nearby_cards_use_the)
        needsHelper && helperStatus.blocked -> context.getString(R.string.allow_notifications_for_the_card_helper_to_show_nearby)
        needsHelper -> context.getString(R.string.set_up_the_card_helper_for_full_cards_on)
        else -> context.getString(R.string.this_phone_uses_the_standard_card_layout)
    }
}
