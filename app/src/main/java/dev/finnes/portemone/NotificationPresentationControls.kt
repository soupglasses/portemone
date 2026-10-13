// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.finnes.portemone.notifications.integrations.NotificationPresentation
import dev.finnes.portemone.notifications.integrations.NotificationPresentationStatus

@Composable
internal fun NotificationPresentationControls(settings: LocationSettings, revision: Int) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val status = remember(revision) { NotificationPresentationStatus(context) }
    var message by remember { mutableStateOf("") }
    var installHelp by remember { mutableStateOf(false) }
    fun preview() {
        message = if (CardNotificationManager(context).show(SampleCard.card.copy(barcodeType = BarcodeType.QR_CODE)))
            resources.getString(R.string.preview_shown_expand_the_notification_to_see_your_card)
        else resources.getString(R.string.could_not_show_the_preview_check_notification_health)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) preview() else message = resources.getString(R.string.allow_notifications_to_preview_your_card)
    }
    Text(stringResource(R.string.card_appearance), style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
        NotificationPresentation.entries.forEach { preference ->
            Row(Modifier.fillMaxWidth().testTag("notification_presentation_${preference.name}")
                .selectable(selected = status.preference == preference, role = Role.RadioButton, onClick = {
                    val notifications = CardNotificationManager(context)
                    // Samsung and standard cards share an owner, so replace their existing layout too.
                    notifications.hide()
                    settings.notificationPresentation = preference
                    if (settings.enabled) notifications.syncNearby(settings.activeCards())
                    message = ""
                }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = status.preference == preference, onClick = null)
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(stringResource(preference.labelRes))
                    Text(if (preference == NotificationPresentation.AUTOMATIC)
                        resources.getString(R.string.use_full_cards_when_supported_by_this_phone) else resources.getString(R.string.use_regular_notification_layouts),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Text(status.title, modifier = Modifier.testTag("notification_presentation_status"), style = MaterialTheme.typography.titleSmall)
    Text(status.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (status.needsHelper && !status.helperStatus.ready) {
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            if (!status.helperStatus.installed) installHelp = true
            else try { context.startActivity(status.helper.setupIntent()) }
            catch (_: ActivityNotFoundException) { installHelp = true }
        }) { Text(if (status.helperStatus.blocked) resources.getString(R.string.review_card_notification_permissions) else resources.getString(R.string.set_up_full_cards)) }
    }
    TextButton(onClick = {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else preview()
    }) { Text(stringResource(R.string.preview_notification)) }
    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
    if (installHelp) AlertDialog(onDismissRequest = { installHelp = false },
        title = { Text(stringResource(R.string.set_up_full_cards)) },
        text = { Text(stringResource(R.string.install_the_portemon_card_helper_from_the_same_place)) },
        confirmButton = { TextButton(onClick = { installHelp = false }) { Text(stringResource(R.string.ok)) } })
}
