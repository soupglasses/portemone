// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.notifications.NotificationPreferences

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.finnes.portemone.ui.theme.WalletTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class DeveloperToolsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val notifications = CardNotificationManager(this)
        val locations = GeofenceCoordinator(this)
        val settings = LocationSettings(this)
        val presentation = NotificationPreferences(this)
        setContent {
            WalletTheme {
                var revision by remember { mutableIntStateOf(0) }
                DisposableEffect(settings) {
                    val unsubscribe = settings.observe { revision++ }
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) revision++
                    }
                    lifecycle.addObserver(observer)
                    onDispose { unsubscribe(); lifecycle.removeObserver(observer) }
                }
                val type = remember(revision) { settings.barcodeType }
                val demo = SampleCard.card.copy(barcodeType = type)
                var status by rememberSaveable { mutableStateOf("") }
                var experiments by rememberSaveable { mutableStateOf(false) }
                var changing by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                fun showDemo() {
                    status = if (notifications.show(
                            demo,
                            presentation.notificationStyle
                        )
                    ) getString(R.string.demo_notification_shown)
                    else getString(R.string.notifications_are_disabled_enable_them_in_android_notification_settings)
                }

                val permission =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        AppDiagnostics.event("Permission", "Demo notifications allowed=$granted")
                        if (granted) showDemo() else status =
                            getString(R.string.notification_permission_was_declined)
                    }
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.developer_tools), fontWeight = FontWeight.SemiBold) },
                        navigationIcon = { BackButton(onClick = { finish() }) })
                }) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Text(
                            stringResource(R.string.tools_for_testing_how_cards_appear_on_this_device),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SettingsGroup(getString(R.string.diagnostics)) {
                            Text(stringResource(R.string.debug_logs), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.inspect_app_activity_or_share_a_zip_with_logs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                startActivity(
                                    Intent(
                                        this@DeveloperToolsActivity,
                                        DebugActivity::class.java
                                    )
                                )
                            }) { Text(stringResource(R.string.open_debug_logs)) }
                        }
                        SettingsGroup(getString(R.string.demo_notification)) {
                            Text(
                                stringResource(R.string.example_card_demo),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.preview_the_current_notification_style_without_visiting_a_store),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    BarcodeType.CODE_128,
                                    BarcodeType.QR_CODE
                                ).forEach { option ->
                                    FilterChip(
                                        selected = type == option,
                                        enabled = !changing,
                                        onClick = {
                                            scope.launch {
                                                changing = true
                                                try {
                                                    locations.changePresentation(
                                                        option,
                                                        presentation.notificationStyle,
                                                        presentation.collapsedPreview
                                                    )
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (error: Exception) {
                                                    status = error.userMessage(resources, R.string.could_not_change_the_demo_code)
                                                } finally {
                                                    changing = false
                                                }
                                            }
                                        },
                                        label = { Text(if (option == BarcodeType.CODE_128) getString(R.string.barcode) else getString(R.string.qr_code)) })
                                }
                            }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !changing,
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    else showDemo()
                                }) { Text(stringResource(R.string.show_demo_notification)) }
                            TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                notifications.hide(demo); status = getString(R.string.demo_notification_removed)
                            }) { Text(stringResource(R.string.hide_demo_notification)) }
                            if (status.isNotEmpty()) Text(
                                status,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        SettingsGroup(getString(R.string.test_location)) {
                            LocationControls(locations, settings, lifecycle, testOnly = true)
                        }
                        SettingsGroup(getString(R.string.experiments)) {
                            TextButton(
                                onClick = { experiments = !experiments },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (experiments) getString(R.string.hide_widget_and_floating_card_tests) else getString(R.string.widget_and_floating_card_tests))
                            }
                            if (experiments) {
                                CardWidgetControls()
                                FloatingCardControls(type)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}
