// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.finnes.portemone.diagnostics.AppDiagnostics
import dev.finnes.portemone.location.GeofenceCoordinator
import dev.finnes.portemone.location.LocationSettings
import dev.finnes.portemone.notifications.HealthDestination
import dev.finnes.portemone.notifications.NotificationHealth
import dev.finnes.portemone.notifications.NotificationHealthItem
import dev.finnes.portemone.notifications.integrations.CompanionNotificationClient
import dev.finnes.portemone.notifications.integrations.SamsungNowBar
import dev.finnes.portemone.ui.theme.WalletTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class NotificationHealthActivity : ComponentActivity() {
    companion object { const val EXTRA_TASK = "notification_health_task" }
    private var guidanceToast: Toast? = null

    private fun showGuidance(message: String, duration: Int = Toast.LENGTH_LONG) {
        guidanceToast?.cancel()
        guidanceToast = Toast.makeText(this, message, duration).also { it.show() }
    }

    override fun onDestroy() {
        guidanceToast?.cancel()
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val health = NotificationHealth(this)
        val locations = GeofenceCoordinator(this)
        setContent {
            WalletTheme {
                var revision by remember { mutableIntStateOf(0) }
                var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                var helpId by rememberSaveable { mutableStateOf(intent.getStringExtra(EXTRA_TASK)) }
                var pendingTaskId by rememberSaveable { mutableStateOf<String?>(null) }
                var openError by remember { mutableStateOf<String?>(null) }
                var returnAdvice by remember { mutableStateOf<String?>(null) }
                var enabling by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                DisposableEffect(lifecycle) {
                    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
                    val unsubscribe = LocationSettings(this@NotificationHealthActivity).observe { revision++ }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer); unsubscribe() }
                }
                val basic = remember(revision) { health.basic() }
                val samsung = remember(revision) { health.samsung() }
                val rows = if (selectedTab == 1 && SamsungNowBar.isSamsung) samsung else basic
                val help = (basic + samsung).find { it.id == helpId }
                val missingRequired = basic.firstOrNull { it.requiredForAutomatic && !it.ready }
                val automaticReady = basic.first { it.id == "automatic" }.ready
                val locationResolution = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                    revision++
                    returnAdvice = getString(R.string.location_settings_reviewed_tap_enable_automatic_cards_to_try)
                }
                fun enableAutomaticCards() {
                    if (enabling) return
                    // Re-read before enabling: permissions can change while a helper is open.
                    val missing = health.basic().firstOrNull { it.requiredForAutomatic && !it.ready }
                    openError = null
                    returnAdvice = null
                    if (missing != null) {
                        helpId = missing.id
                        returnAdvice = getString(R.string.finish_this_required_check_before_enabling_automatic_cards)
                        return
                    }
                    helpId = "automatic"
                    enabling = true
                    scope.launch {
                        try {
                            locations.enable()
                            revision++
                            helpId = null
                            showGuidance(getString(R.string.automatic_cards_are_on), Toast.LENGTH_SHORT)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            val resolution = GeofenceCoordinator.settingsResolution(error)
                            if (resolution != null) {
                                returnAdvice = getString(R.string.review_android_s_location_settings_then_tap_enable_automatic)
                                locationResolution.launch(IntentSenderRequest.Builder(resolution).build())
                            } else {
                                openError = GeofenceCoordinator.errorMessage(error, resources)
                                AppDiagnostics.event("NotificationHealth", "Could not enable automatic cards", error)
                            }
                        } finally {
                            enabling = false
                            revision++
                        }
                    }
                }
                val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    // Settings commonly returns RESULT_CANCELED even after a successful
                    // change. Read the real state instead of trusting the result code.
                    val id = pendingTaskId
                    pendingTaskId = null
                    revision++
                    val checked = (health.basic() + health.samsung()).find { it.id == id }
                    if (checked != null) {
                        when {
                            checked.unknown -> {
                                returnAdvice = getString(R.string.not_verified_returning_from_settings_does_not_mark_this, checked.detail)
                                showGuidance(getString(R.string.this_setting_is_still_not_verifiable_by_portemon))
                            }
                            checked.ready -> {
                                helpId = null
                                returnAdvice = null
                                showGuidance(getString(R.string.is_ready, checked.title), Toast.LENGTH_SHORT)
                            }
                            else -> {
                                openError = getString(R.string.still_needs_attention, checked.detail)
                                showGuidance(checked.settingsHint(resources))
                            }
                        }
                        AppDiagnostics.event("NotificationHealth", "Returned from settings: ${checked.id}; ready=${checked.ready}; unknown=${checked.unknown}")
                    }
                }
                fun open(intent: Intent, hint: String = help?.settingsHint(resources).orEmpty()) {
                    openError = null
                    returnAdvice = null
                    pendingTaskId = helpId
                    // OEM lock-screen actions are optional. Fall back to an honest broader
                    // destination with the same instructions, never a private Settings fragment.
                    val fallbacks = when (help?.destination) {
                        HealthDestination.NOTIFICATIONS -> listOf(Intent("android.settings.NOTIFICATION_SETTINGS"), Intent(android.provider.Settings.ACTION_SETTINGS))
                        HealthDestination.SAMSUNG, HealthDestination.DEVELOPER -> listOf(Intent(android.provider.Settings.ACTION_SETTINGS))
                        HealthDestination.PIXEL_HELPER -> listOf(Intent(this@NotificationHealthActivity, DeveloperToolsActivity::class.java))
                        else -> emptyList()
                    }
                    for (candidate in listOf(intent) + fallbacks) {
                        try {
                            settingsLauncher.launch(candidate)
                            showGuidance(hint)
                            AppDiagnostics.event("NotificationHealth", "Opened ${help?.id}: ${candidate.action}")
                            return
                        } catch (error: RuntimeException) {
                            if (error !is ActivityNotFoundException && error !is SecurityException) throw error
                            AppDiagnostics.event("NotificationHealth", "Settings shortcut unavailable: ${candidate.action}", error)
                        }
                    }
                    pendingTaskId = null
                    openError = getString(R.string.that_shortcut_is_unavailable_open_android_settings_and_follow)
                }
                Scaffold(topBar = {
                    TopAppBar(title = { Text(stringResource(R.string.notification_health), fontWeight = FontWeight.SemiBold) },
                        navigationIcon = { BackButton(onClick = { finish() }) })
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (SamsungNowBar.isSamsung) PrimaryTabRow(selectedTabIndex = selectedTab, modifier = Modifier.widthIn(max = 560.dp)) {
                            listOf(getString(R.string.basic_support), getString(R.string.samsung)).forEachIndexed { index, title ->
                                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
                            }
                        }
                        LazyColumn(Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("notification_health_list"),
                            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item {
                                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (selectedTab == 1) getString(R.string.now_bar_setup) else getString(R.string.notification_setup),
                                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.of_verified, rows.count { it.ready }, rows.size), style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text(stringResource(R.string.tap_an_item_for_help_checks_read_the_current),
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            items(rows, key = { it.id }) { item ->
                                HealthRow(item) { helpId = item.id; openError = null; returnAdvice = null }
                            }
                            if (selectedTab == 0 && !automaticReady) item {
                                Button(onClick = { enableAutomaticCards() }, enabled = !enabling, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (enabling) getString(R.string.enabling) else if (missingRequired != null) getString(R.string.continue_setup) else getString(R.string.enable_automatic_cards))
                                }
                            }
                            item {
                                Text(stringResource(R.string.settings_names_can_vary_by_phone_and_software_version),
                                    modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (help != null) ModalBottomSheet(onDismissRequest = { helpId = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Column(Modifier.fillMaxWidth().testTag("notification_health_help").verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(help.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(help.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        help.steps.forEachIndexed { index, step ->
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.step_number, index + 1), style = MaterialTheme.typography.labelLarge) }
                                }
                                Text(step, modifier = Modifier.weight(1f).padding(top = 3.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        openError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        returnAdvice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                        if (help.destination == HealthDestination.AUTOMATIC) {
                            Button(onClick = { enableAutomaticCards() }, enabled = !enabling, modifier = Modifier.fillMaxWidth()) {
                                Text(if (enabling) getString(R.string.enabling) else if (missingRequired != null) getString(R.string.review_required_checks) else if (automaticReady) getString(R.string.refresh_store_monitoring) else getString(R.string.enable_automatic_cards))
                            }
                        } else Button(onClick = { open(health.intent(help.destination)) }, modifier = Modifier.fillMaxWidth()) {
                            Text(when (help.destination) {
                                HealthDestination.DEVELOPER -> getString(R.string.open_developer_options)
                                HealthDestination.PIXEL_HELPER -> if (CompanionNotificationClient(this@NotificationHealthActivity).status().installed) getString(R.string.set_up_full_cards) else getString(R.string.open_notification_settings)
                                else -> getString(R.string.open_android_settings)
                            })
                        }
                        if (help.allAppsHelp) {
                            Text(stringResource(R.string.if_developer_options_is_hidden_settings_about_phone_software),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (help.unknown) Text(stringResource(R.string.this_setting_cannot_be_verified_on_this_phone_it),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { helpId = null }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthRow(item: NotificationHealthItem, onClick: () -> Unit) {
    val resources = LocalResources.current
    val status = if (item.ready) resources.getString(R.string.verified) else if (item.unknown) resources.getString(R.string.not_verified) else resources.getString(R.string.needs_attention)
    val green = if (isSystemInDarkTheme()) Color(0xFF8DDBAE) else Color(0xFF176B40)
    val ink = if (item.ready) green else if (item.unknown) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("health_${item.id}").semantics { stateDescription = status }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = CircleShape, color = ink.copy(alpha = .12f)) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Text(if (item.ready) "✓" else if (item.unknown) "?" else "×", color = ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status, style = MaterialTheme.typography.labelSmall, color = ink)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
