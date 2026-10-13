// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.notificationcompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Opt-in tests against Android's real owning package, delegate and notification channel.
 */
@RunWith(AndroidJUnit4.class)
public final class NotificationDelegationTest {
    private NotificationDelegation delegation;
    private NotificationManager notifications;
    private boolean previousDelegate;

    @Before
    public void prepare() {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("notificationIntegrationTests"))
                && Build.MODEL.contains("sdk_gphone"));
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        delegation = new NotificationDelegation(context);
        notifications = context.getSystemService(NotificationManager.class);
        previousDelegate = delegation.enabled();
        delegation.createChannel();
        assertEquals("Install a matching main APK before running helper tests", 0, delegation.mainPackageProblem());
    }

    @After
    public void restoreRequestedDelegate() {
        if (delegation == null) return;
        notifications.cancelAll();
        String requested = InstrumentationRegistry.getArguments().getString("helperDelegate");
        if ("true".equals(requested) || "restore".equals(requested) && previousDelegate)
            delegation.enable();
        else delegation.revoke();
    }

    @Test
    public void reportConnection() {
        android.os.Bundle state = new android.os.Bundle();
        state.putBoolean("helperConnected", delegation.enabled());
        InstrumentationRegistry.getInstrumentation().sendStatus(0, state);
    }

    @Test
    public void explicitConsentEnablesAndRevokesTheActualDelegate() {
        assertTrue(delegation.revoke());
        assertFalse(delegation.enabled());
        assertTrue(delegation.enable());
        assertEquals(NotificationDelegation.MAIN_PACKAGE, notifications.getNotificationDelegate());
        assertTrue(delegation.revoke());
        assertNull(notifications.getNotificationDelegate());
    }

    @Test
    public void revocationRemovesCardsOwnedByTheHelper() throws Exception {
        assertTrue(delegation.enable());
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        notifications.notify("delegation-test", 42, new Notification.Builder(context, NotificationDelegation.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome).setContentTitle("Synthetic card").build());
        awaitCount(1);
        assertTrue(delegation.revoke());
        awaitCount(0);
    }

    @Test
    public void reopeningPreservesTheChannelAndConsent() {
        assertTrue(delegation.enable());
        NotificationChannel before = notifications.getNotificationChannel(NotificationDelegation.CHANNEL_ID);
        delegation.createChannel();
        NotificationChannel after = notifications.getNotificationChannel(NotificationDelegation.CHANNEL_ID);
        assertEquals(before.getImportance(), after.getImportance());
        assertEquals(before.getSound(), after.getSound());
        assertEquals(before.shouldVibrate(), after.shouldVibrate());
        assertEquals(before.getLockscreenVisibility(), after.getLockscreenVisibility());
        assertTrue(delegation.enabled());
    }

    @Test
    public void setupButtonsControlRealDelegationAcrossRecreation() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNull("The companion should be opened through Portemoné settings",
                context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
        delegation.revoke();
        try (ActivityScenario<SetupActivity> activity = ActivityScenario.launch(SetupActivity.class)) {
            onView(withText(R.string.enable)).perform(scrollTo(), click());
            assertTrue(delegation.enabled());
            activity.recreate();
            assertTrue(delegation.enabled());
            onView(withText(R.string.revoke)).perform(scrollTo(), click());
            assertFalse(delegation.enabled());
        }
    }

    private void awaitCount(int count) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (notifications.getActiveNotifications().length == count) return;
            Thread.sleep(50);
        }
        assertEquals(count, notifications.getActiveNotifications().length);
    }
}
