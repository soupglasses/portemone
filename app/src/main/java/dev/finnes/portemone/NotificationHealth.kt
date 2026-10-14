// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.notifications.integrations.SamsungNowBar
import dev.finnes.portemone.notifications.integrations.CompanionNotificationClient

import dev.finnes.portemone.location.LocationEngine

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

internal enum class HealthDestination { APP_NOTIFICATIONS, CHANNEL, NOTIFICATIONS, PERMISSIONS, LOCATION, AUTOMATIC, SAMSUNG, DEVELOPER, PIXEL_HELPER }

internal data class NotificationHealthItem(
    val id: String,
    val title: String,
    val ready: Boolean,
    val detail: String,
    val steps: List<String>,
    val destination: HealthDestination,
    val unknown: Boolean = false,
    val allAppsHelp: Boolean = false,
) {
    val requiredForAutomatic: Boolean get() = id in setOf("notifications", "channel", "precise", "background", "location")
    // System text toasts allow only two lines; keep the destination's next action short.
    fun settingsHint(resources: android.content.res.Resources): String = when (id) {
        resources.getString(R.string.health_hint_notifications) -> resources.getString(R.string.health_hint_turn_on_allow_notifications_for_portemon)
        "channel" -> resources.getString(R.string.health_hint_turn_on_cards_at_checkout_notifications)
        "lock_screen" -> resources.getString(R.string.health_hint_show_notifications_and_their_content_on_the)
        "precise" -> resources.getString(R.string.health_hint_permissions_location_use_precise_location)
        "background" -> resources.getString(R.string.health_hint_permissions_location_allow_all_the_time)
        "location" -> resources.getString(R.string.health_hint_turn_on_use_location)
        "automatic" -> resources.getString(R.string.health_hint_turn_on_automatic_cards_or_refresh_if)
        resources.getString(R.string.health_hint_samsung_privacy) -> resources.getString(R.string.health_hint_allow_portemon_content_while_the_phone_is)
        "samsung_live" -> resources.getString(R.string.health_hint_apps_live_notifications_for_all_apps_on)
        "pixel_helper" -> resources.getString(R.string.health_hint_allow_helper_notifications_and_enable_portemon_in)
        else -> resources.getString(R.string.health_hint_lock_screen_and_aod_now_bar_live)
    }
}

/** Green means measured state; unavailable platform/OEM information stays unverified. */
internal class NotificationHealth(private val context: Context) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val channelId = CardNotificationManager(context).channelId
    private val appName = context.getString(R.string.app_name)
    private val probes = NotificationSettingsProbe(context)

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun basic(): List<NotificationHealthItem> {
        val channel = notifications.getNotificationChannel(channelId)
        val channelAllowed = channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE &&
            channel.group?.let { notifications.getNotificationChannelGroup(it)?.isBlocked != true } != false
        val lockScreen = probes.lockScreen(channel)
        val precise = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val location = context.getSystemService(LocationManager::class.java).isLocationEnabled
        val locations = LocationSettings(context)
        val engine = GeofenceCoordinator(context).selectedEngine()
        val automatic = locations.enabled && precise && background && location && GeofenceCoordinator(context).hasCurrentRegistration()
        return listOf(
            NotificationHealthItem("notifications", context.getString(R.string.app_notifications), notifications.areNotificationsEnabled(),
                if (notifications.areNotificationsEnabled()) context.getString(R.string.allowed_by_android) else context.getString(R.string.notifications_are_blocked),
                listOf(context.getString(R.string.turn_on_allow_notifications_for, appName), context.getString(R.string.return_here_this_check_updates_automatically)), HealthDestination.APP_NOTIFICATIONS),
            NotificationHealthItem("channel", context.getString(R.string.cards_at_checkout), channelAllowed,
                if (channelAllowed) context.getString(R.string.card_notifications_are_allowed) else context.getString(R.string.the_card_notification_category_is_blocked),
                listOf(context.getString(R.string.open_the_cards_at_checkout_notification_category_and_allow),
                    context.getString(R.string.use_default_or_alert_if_silent_notifications_are_hidden),
                    context.getString(R.string.on_samsung_missing_categories_can_be_shown_from_notifications)), HealthDestination.CHANNEL),
            NotificationHealthItem("lock_screen", context.getString(R.string.lock_screen_notifications), lockScreen.ready,
                lockScreen.detail,
                listOf(context.getString(R.string.allow_notifications_on_the_lock_screen),
                    if (SamsungNowBar.isSamsung) context.getString(R.string.in_hide_content_while_locked_choose_show_always_or, appName) else context.getString(R.string.allow_lock_screen_notifications_if_your_card_category_is),
                    context.getString(R.string.in_s_cards_at_checkout_category_allow_lock_screen, appName),
                    context.getString(R.string.your_membership_code_will_be_visible_while_the_phone)),
                HealthDestination.NOTIFICATIONS, unknown = lockScreen.unknown),
            NotificationHealthItem("precise", context.getString(R.string.precise_location), precise,
                if (precise) context.getString(R.string.precise_location_is_allowed) else context.getString(R.string.needed_to_match_your_saved_store_circles),
                listOf(context.getString(R.string.open_permissions_location), context.getString(R.string.allow_location_and_turn_on_use_precise_location)), HealthDestination.PERMISSIONS),
            NotificationHealthItem("background", context.getString(R.string.arrival_while_the_app_is_closed), background,
                if (background) context.getString(R.string.location_is_allowed_all_the_time) else context.getString(R.string.background_location_needs_permission),
                listOf(context.getString(R.string.open_permissions_location), context.getString(R.string.choose_allow_all_the_time_store_matching_stays_on)), HealthDestination.PERMISSIONS),
            NotificationHealthItem("location", context.getString(R.string.device_location), location,
                if (location) context.getString(R.string.location_is_switched_on) else context.getString(R.string.device_location_is_switched_off),
                listOf(context.getString(R.string.turn_on_use_location), if (engine == LocationEngine.PLAY_SERVICES)
                        context.getString(R.string.under_location_services_turn_on_google_location_accuracy_if)
                    else context.getString(R.string.check_the_device_s_location_providers_if_store_arrivals)), HealthDestination.LOCATION),
            NotificationHealthItem("automatic", context.getString(R.string.automatic_cards), automatic,
                if (automatic) context.getString(R.string.registration_succeeded_for_this_app_version_and_boot, context.getString(engine.labelRes)) else if (locations.enabled) context.getString(R.string.store_monitoring_needs_a_refresh) else context.getString(R.string.turn_on_automatic_cards_in, appName),
                listOf(context.getString(R.string.complete_the_app_notifications_card_category_precise_location_background),
                    context.getString(R.string.then_turn_on_automatic_cards_here_to_monitor_your),
                    context.getString(R.string.check_the_lock_screen_and_samsung_settings_to_choose)), HealthDestination.AUTOMATIC),
        ) + if (locations.notificationPresentation == dev.finnes.portemone.notifications.integrations.NotificationPresentation.AUTOMATIC && !SamsungNowBar.isSamsung) {
            val helper = CompanionNotificationClient(context).status()
            listOf(NotificationHealthItem(
                "pixel_helper", context.getString(R.string.full_card_notifications), helper.ready,
                helper.description,
                listOf(
                    if (helper.installed) context.getString(R.string.open_the_helper_and_allow_portemon_to_post_cards)
                    else context.getString(R.string.open_settings_notifications_card_appearance_to_set_up_full),
                    if (helper.blocked) context.getString(R.string.allow_notifications_for_the_helper_and_its_nearby_cards)
                    else context.getString(R.string.keep_notifications_allowed_for_both_portemon_and_the_helper),
                    context.getString(R.string.this_checks_helper_setup_and_notification_access_check_a),
                ), HealthDestination.PIXEL_HELPER,
            ))
        } else emptyList()
    }

    // Samsung's per-app Now Bar permission is a privileged binder API, distinct from
    // Android's promoted-notification permission. Never infer it from our own extras.
    fun samsung(): List<NotificationHealthItem> {
        val live = probes.samsungLive()
        val locations = LocationSettings(context)
        return listOf(
            NotificationHealthItem("samsung_integration", context.getString(R.string.full_card_notifications), SamsungNowBar.isSupported &&
                    locations.notificationPresentation == dev.finnes.portemone.notifications.integrations.NotificationPresentation.AUTOMATIC,
                if (SamsungNowBar.isSupported && locations.notificationPresentation != dev.finnes.portemone.notifications.integrations.NotificationPresentation.AUTOMATIC) context.getString(R.string.standard_notifications_selected_choose_automatic_in_card_appearance_for)
                else if (SamsungNowBar.isSupported) context.getString(R.string.samsung_card_views_configured_automatically) else context.getString(R.string.requires_a_samsung_phone_with_android_15_one_ui),
                listOf(context.getString(R.string.automatically_includes_samsung_s_compact_and_expanded_card_views, appName),
                    context.getString(R.string.this_checks_our_app_configuration_not_samsung_s_permission)), HealthDestination.SAMSUNG),
            NotificationHealthItem("samsung_live", context.getString(R.string.live_notifications_for_all_apps), live.ready,
                live.detail,
                listOf(context.getString(R.string.in_developer_options_scroll_to_the_apps_section),
                    context.getString(R.string.turn_on_live_notifications_for_all_apps_if_your),
                    context.getString(R.string.that_option_allows_other_apps_to_use_live_notifications),
                    context.getString(R.string.return_here_to_recheck_the_actual_setting_if_unavailable)),
                HealthDestination.DEVELOPER, unknown = live.unknown, allAppsHelp = true),
            NotificationHealthItem("samsung_privacy", context.getString(R.string.card_content_while_locked), false,
                context.getString(R.string.not_verified_samsung_does_not_let_portemon_read_this),
                listOf(context.getString(R.string.open_notifications_for_and_choose_to_show_content_while, appName),
                    context.getString(R.string.a_global_show_content_setting_does_not_prove_that),
                    context.getString(R.string.this_check_stays_unverified_because_ordinary_apps_cannot_read)),
                HealthDestination.APP_NOTIFICATIONS, unknown = true),
            NotificationHealthItem("samsung_nowbar", context.getString(R.string.show_in_the_now_bar), false,
                context.getString(R.string.not_verified_samsung_does_not_expose_this_app_s),
                listOf(context.getString(R.string.open_settings_lock_screen_and_aod_now_bar),
                    context.getString(R.string.under_live_notifications_or_live_information_tap_see_all, appName),
                    context.getString(R.string.keep_lock_screen_notifications_and_content_visible_a_card),
                    context.getString(R.string.samsung_keeps_this_per_app_switch_private_this_item)),
                HealthDestination.SAMSUNG, unknown = true),
        )
    }

    fun intent(destination: HealthDestination): Intent = when (destination) {
        HealthDestination.APP_NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).highlightSettingsPreference("block")
        HealthDestination.CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            .highlightSettingsPreference("block")
        HealthDestination.NOTIFICATIONS -> Intent("android.settings.LOCK_SCREEN_NOTIFICATIONS_SETTINGS")
        HealthDestination.PERMISSIONS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        HealthDestination.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        // Keep the checklist in the back stack so returning can verify this task too.
        HealthDestination.AUTOMATIC -> Intent(context, SettingsActivity::class.java)
        HealthDestination.SAMSUNG -> Intent("android.settings.LOCK_SCREEN_SETTINGS")
        // Preference key verified in Fold4 One UI 8 SecSettings, distinct from its storage key.
        HealthDestination.DEVELOPER -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .highlightSettingsPreference("notification_nowbar_test")
        HealthDestination.PIXEL_HELPER -> CompanionNotificationClient(context).let { helper ->
            if (helper.status().installed) helper.setupIntent()
            else Intent(context, SettingsActivity::class.java)
        }
    }
}
