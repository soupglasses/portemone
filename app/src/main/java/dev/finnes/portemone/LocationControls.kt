// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource

import android.Manifest
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.finnes.portemone.location.LocationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LocationControls(manager: GeofenceCoordinator, settings: LocationSettings, lifecycle: Lifecycle, testOnly: Boolean = false) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(settings, lifecycle) {
        val unsubscribe = settings.observe { revision++ }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycle.addObserver(observer)
        onDispose { unsubscribe(); lifecycle.removeObserver(observer) }
    }
    val status = remember(revision) { settings.status }
    val lastUpdatedAt = remember(revision) { settings.lastNearbyUpdateAtMillis }
    val enabled = remember(revision) { settings.enabled }
    val testStore = remember(revision) { settings.stores().find { it.id == "test" } }
    val precise = remember(revision) { manager.hasPreciseLocation() }
    val background = remember(revision) { manager.hasBackgroundLocation() }

    val locationResolution = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        settings.status = if (it.resultCode == Activity.RESULT_OK)
            resources.getString(R.string.location_settings_updated_tap_the_automatic_cards_button_to)
        else resources.getString(R.string.location_settings_were_unchanged_open_device_location_settings_to)
        revision++
    }

    fun run(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                AppDiagnostics.event("Location", "Location control failed", error)
                val resolution = GeofenceCoordinator.settingsResolution(error)
                if (resolution != null) {
                    settings.status = resources.getString(R.string.review_android_s_location_settings_prompt_then_retry)
                    locationResolution.launch(IntentSenderRequest.Builder(resolution).build())
                } else settings.status = GeofenceCoordinator.errorMessage(error, resources)
            }
            finally { busy = false; revision++ }
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        AppDiagnostics.event("Permission", "Location permission result: $it")
        revision++
        settings.status = if (manager.hasPreciseLocation())
            resources.getString(R.string.precise_location_allowed_try_your_location_action_again)
        else resources.getString(R.string.precise_location_is_needed_to_detect_a_store_you)
    }
    fun openSetup(task: String? = null) {
        context.startActivity(Intent(context, NotificationHealthActivity::class.java)
            .putExtra(NotificationHealthActivity.EXTRA_TASK, task))
    }
    if (testOnly) {
        Text(stringResource(R.string.temporary_test_card), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.create_a_test_card_with_code_1234567890_and_a),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            if (!precise) locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            else run { manager.addTestStore() }
        }) { Text(if (busy) resources.getString(R.string.updating) else if (testStore == null) resources.getString(R.string.create_test_card_here) else resources.getString(R.string.move_test_card_here)) }
        testStore?.let { store ->
            Text(stringResource(R.string.test_store_coordinates, store.latitude.toString(), store.longitude.toString(), store.radiusMeters.toInt()), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = {
                    settings.cards().find { it.id == TestCard.card.id }?.let { card ->
                        context.startActivity(CardActivity.intent(context, card))
                    }
                }) { Text(stringResource(R.string.open_test_card)) }
                TextButton(enabled = !busy, onClick = { run { manager.removeTestStore() } }) { Text(stringResource(R.string.delete_test_card)) }
            }
        }
        if (!enabled) {
            Text(stringResource(R.string.automatic_cards_are_off_enable_them_to_test_arrival), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = { openSetup() }) { Text(stringResource(R.string.enable_automatic_cards)) }
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (enabled) resources.getString(R.string.automatic_cards_are_on) else resources.getString(R.string.show_cards_at_nearby_stores), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.cards_appear_near_saved_stores_and_disappear_after_you), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, enabled = !busy, onCheckedChange = { turnOn ->
                if (turnOn) openSetup() else run { manager.disable() }
            })
        }
        Text(stringResource(R.string.location_engine), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            LocationEngine.entries.forEach { engine ->
                Row(Modifier.fillMaxWidth().testTag("location_engine_${engine.name}")
                    .selectable(selected = settings.locationEngine == engine, enabled = !busy,
                        role = Role.RadioButton, onClick = { run { manager.changeEngine(engine) } }),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = settings.locationEngine == engine, enabled = !busy, onClick = null)
                    Text(stringResource(engine.labelRes))
                }
            }
        }
        if (settings.locationEngine == LocationEngine.AUTOMATIC) {
            Text(stringResource(R.string.using, resources.getString(manager.selectedEngine().labelRes)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.location, if (precise && background) resources.getString(R.string.always_allowed_precise) else if (precise) resources.getString(R.string.precise_background_access_needed) else resources.getString(R.string.precise_access_needed)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (enabled) OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            run { manager.refreshNearby(force = true) }
        }) { Text(if (busy) resources.getString(R.string.refreshing) else resources.getString(R.string.refresh_nearby_stores)) }
        if (!enabled && precise && !background) TextButton(enabled = !busy, onClick = { openSetup() }) {
            Text(if (busy) resources.getString(R.string.updating) else resources.getString(R.string.finish_setting_up_automatic_cards))
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NearbyUpdateLabel(lastUpdatedAt, lifecycle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { openSetup(if (!precise) "precise" else "background") }) { Text(stringResource(R.string.permissions)) }
            TextButton(onClick = { openSetup("location") }) { Text(stringResource(R.string.location_settings)) }
        }
        Text(stringResource(R.string.store_matching_stays_on_this_device_arrival_and_departure),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
