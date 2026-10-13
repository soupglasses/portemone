// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.notificationcompanion;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * User-controlled delegation; this application never receives location or card data.
 */
public final class SetupActivity extends Activity {
    private static final String TAG = "PortemoneNotificationCompanion";
    private NotificationDelegation delegation;
    private TextView connectionStatus;
    private TextView notificationStatus;
    private TextView actionStatus;
    private Button enableButton;
    private Button revokeButton;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        delegation = new NotificationDelegation(this);
        delegation.createChannel();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(32), dp(24), dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        TextView title = text(body, R.string.setup_title, 30, 0);
        title.setAccessibilityHeading(true);
        text(body, R.string.setup_description, 18, 16);
        text(body, R.string.setup_explanation, 16, 24);
        text(body, R.string.setup_caveat, 15, 16);
        connectionStatus = text(body, R.string.status_disabled, 18, 28);
        notificationStatus = text(body, R.string.status_ready, 15, 8);
        actionStatus = text(body, 0, 15, 8);
        actionStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        actionStatus.setVisibility(View.GONE);
        enableButton = button(body, R.string.enable, 24, this::enableDelegation);
        revokeButton = button(body, R.string.revoke, 8, this::revokeDelegation);
        button(body, R.string.notification_settings, 8, this::openNotificationSettings);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (delegation != null && connectionStatus != null) refreshStatus();
    }

    private void refreshStatus() {
        int problem = delegation.mainPackageProblem();
        boolean enabled = delegation.enabled();
        connectionStatus.setText(problem != 0 ? problem
                : enabled ? R.string.status_enabled : R.string.status_disabled);
        enableButton.setEnabled(problem == 0 && !enabled);
        revokeButton.setEnabled(delegation.canRevoke());
        notificationStatus.setText(delegation.notificationStatus());
    }

    private void enableDelegation() {
        int problem = delegation.mainPackageProblem();
        if (problem != 0) {
            showActionStatus(problem);
            refreshStatus();
            return;
        }
        try {
            boolean confirmed = delegation.enable();
            showActionStatus(confirmed ? R.string.status_enabled : R.string.status_not_verified);
            Log.i(TAG, "User enabled delegation; confirmed=" + confirmed);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to enable notification delegation", error);
            showActionStatus(R.string.status_error);
        }
        refreshStatus();
    }

    private void revokeDelegation() {
        try {
            boolean confirmed = delegation.revoke();
            showActionStatus(confirmed ? R.string.status_disabled : R.string.status_not_verified);
            Log.i(TAG, "User revoked delegation; confirmed=" + confirmed);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to revoke notification delegation", error);
            showActionStatus(R.string.status_error);
        }
        refreshStatus();
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void showActionStatus(int textResource) {
        actionStatus.setText(textResource);
        actionStatus.setVisibility(View.VISIBLE);
    }

    private TextView text(LinearLayout parent, int resource, int sizeSp, int topDp) {
        TextView view = new TextView(this);
        if (resource != 0) view.setText(resource);
        view.setTextSize(sizeSp);
        view.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topDp);
        parent.addView(view, params);
        return view;
    }

    private Button button(LinearLayout parent, int resource, int topDp, Runnable action) {
        Button button = new Button(this);
        button.setText(resource);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topDp);
        parent.addView(button, params);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
