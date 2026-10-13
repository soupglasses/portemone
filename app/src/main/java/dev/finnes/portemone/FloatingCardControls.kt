// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
fun FloatingCardControls(type: BarcodeType) {
    val resources = LocalResources.current
    val context = LocalContext.current
    var status by rememberSaveable { mutableStateOf("") }
    fun show() {
        try {
            context.startForegroundService(Intent(context, FloatingCardService::class.java)
                .setAction(FloatingCardService.SHOW).putExtra(CardActivity.TYPE, type.name))
            status = resources.getString(R.string.floating_card_requested_press_home_or_switch_apps_to)
        } catch (_: SecurityException) {
            status = resources.getString(R.string.android_did_not_allow_the_floating_card_check_display)
        } catch (_: IllegalStateException) {
            status = resources.getString(R.string.return_to_portemon_and_tap_show_floating_card_again)
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) show() else status = resources.getString(R.string.allow_notifications_to_use_the_floating_card_test_with)
    }
    val overlayAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        status = if (Settings.canDrawOverlays(context)) resources.getString(R.string.access_enabled_tap_show_floating_card_to_begin)
            else resources.getString(R.string.display_over_other_apps_is_still_off)
    }
    HorizontalDivider()
    Text(stringResource(R.string.floating_card_test), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.show_the_selected_code_over_other_apps_drag_the),
        style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
        if (!Settings.canDrawOverlays(context)) {
            overlayAccess.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")))
        } else if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else show()
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.show_floating_card)) }
    TextButton(onClick = {
        context.stopService(Intent(context, FloatingCardService::class.java))
        status = resources.getString(R.string.floating_card_closed)
    }) { Text(stringResource(R.string.close_floating_card)) }
    Text(stringResource(R.string.android_shows_a_running_app_notification_while_this_test),
        style = MaterialTheme.typography.bodySmall)
    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
}
