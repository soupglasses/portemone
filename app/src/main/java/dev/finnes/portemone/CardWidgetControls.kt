// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
fun CardWidgetControls() {
    val resources = LocalResources.current
    val context = LocalContext.current
    var status by rememberSaveable { mutableStateOf("") }
    HorizontalDivider()
    Text(stringResource(R.string.card_widget_test), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.on_samsung_open_good_lock_lockstar_and_look_for), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.the_widget_is_completely_blank_unless_automatic_cards_are),
        style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
        val manager = AppWidgetManager.getInstance(context)
        status = if (manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(
                ComponentName(context, CardWidgetProvider::class.java), null, null))
            resources.getString(R.string.home_screen_request_sent_lock_screen_placement_must_be)
            else resources.getString(R.string.add_portemon_card_from_your_home_screen_s_widget)
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_home_screen_test_widget)) }
    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
}
