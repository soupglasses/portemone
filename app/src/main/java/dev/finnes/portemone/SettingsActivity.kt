// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.finnes.portemone.location.GeofenceCoordinator
import dev.finnes.portemone.location.LocationSettings
import dev.finnes.portemone.notifications.CardNotificationManager
import dev.finnes.portemone.notifications.NotificationHealth
import dev.finnes.portemone.notifications.NotificationPresentationControls
import dev.finnes.portemone.notifications.integrations.SamsungNowBar
import dev.finnes.portemone.ui.theme.WalletTheme

class SettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val health = NotificationHealth(this)
        val locations = GeofenceCoordinator(this)
        val settings = LocationSettings(this)
        setContent {
            WalletTheme {
                var revision by remember { mutableIntStateOf(0) }
                DisposableEffect(settings) {
                    val unsubscribe = settings.observe { revision++ }
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            revision++
                            if (settings.enabled) CardNotificationManager(this@SettingsActivity)
                                .syncNearby(settings.activeCards(), onlyMissing = true)
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose { unsubscribe(); lifecycle.removeObserver(observer) }
                }
                val healthItems =
                    remember(revision) { health.basic() + if (SamsungNowBar.isSamsung) health.samsung() else emptyList() }
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.SemiBold) },
                        navigationIcon = { BackButton(onClick = { finish() }) })
                }) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        SettingsGroup(getString(R.string.automatic_cards)) {
                            LocationControls(locations, settings, lifecycle)
                        }
                        SettingsGroup(getString(R.string.notifications)) {
                            Text(
                                stringResource(R.string.nearby_card_notifications),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.show_a_scannable_card_near_a_saved_store_android),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                onClick = {
                                    startActivity(
                                        Intent(
                                            this@SettingsActivity,
                                            NotificationHealthActivity::class.java
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.notification_health),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        stringResource(R.string.of_verified_setup_and_help, healthItems.count { it.ready }, healthItems.size),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            NotificationPresentationControls(settings, revision)
                            OutlinedButton(
                                onClick = {
                                    try {
                                        startActivity(
                                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                                .highlightSettingsPreference("block")
                                        )
                                        Toast.makeText(
                                            this@SettingsActivity,
                                            getString(R.string.turn_on_allow_notifications_for_portemon),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } catch (_: ActivityNotFoundException) {
                                        Toast.makeText(
                                            this@SettingsActivity,
                                            getString(R.string.open_android_settings_apps_portemon_notifications),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.android_notification_settings)) }
                        }
                        SettingsGroup(getString(R.string.advanced)) {
                            Surface(
                                onClick = {
                                    startActivity(
                                        Intent(
                                            this@SettingsActivity,
                                            DeveloperToolsActivity::class.java
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Column(
                                    Modifier.padding(vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.developer_tools),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        stringResource(R.string.demo_cards_test_locations_experiments_and_debug_logs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.app_build_identity),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}
