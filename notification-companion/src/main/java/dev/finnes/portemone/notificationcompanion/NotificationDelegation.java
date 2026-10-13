// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.notificationcompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Owns Android delegation and channel state; the setup screen only presents it.
 */
final class NotificationDelegation {
    static final String MAIN_PACKAGE = "dev.finnes.portemone";
    static final String CHANNEL_ID = "nearby_cards_compat";
    private final Context context;
    private final NotificationManager notifications;

    NotificationDelegation(Context context) {
        this.context = context.getApplicationContext();
        notifications = context.getSystemService(NotificationManager.class);
    }

    void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.channel_description));
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // Recreating the channel preserves any explicit user choices in Android Settings.
        notifications.createNotificationChannel(channel);
    }

    int mainPackageProblem() {
        try {
            context.getPackageManager().getApplicationInfo(MAIN_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException ignored) {
            return R.string.status_main_missing;
        }
        return context.getPackageManager().checkSignatures(context.getPackageName(), MAIN_PACKAGE)
                == PackageManager.SIGNATURE_MATCH ? 0 : R.string.status_signature_mismatch;
    }

    boolean enabled() {
        return MAIN_PACKAGE.equals(notifications.getNotificationDelegate());
    }

    boolean canRevoke() {
        return notifications.getNotificationDelegate() != null;
    }

    int notificationStatus() {
        NotificationChannel channel = notifications.getNotificationChannel(CHANNEL_ID);
        return !notifications.areNotificationsEnabled() ? R.string.status_notifications_blocked
                : channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE
                ? R.string.status_channel_blocked : R.string.status_ready;
    }

    boolean enable() {
        if (mainPackageProblem() != 0) return false;
        notifications.setNotificationDelegate(MAIN_PACKAGE);
        return enabled();
    }

    boolean revoke() {
        notifications.setNotificationDelegate(null);
        // Only the owning app can guarantee removal after its delegate loses access.
        notifications.cancelAll();
        return notifications.getNotificationDelegate() == null;
    }
}
