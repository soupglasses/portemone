// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.finnes.portemone.diagnostics.AppDiagnostics
import dev.finnes.portemone.ui.theme.WalletTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DebugActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WalletTheme {
                val revision by AppDiagnostics.revision.collectAsStateWithLifecycle()
                var refresh by remember { mutableIntStateOf(0) }
                var state by remember { mutableStateOf("") }
                var events by remember { mutableStateOf("") }
                var query by rememberSaveable { mutableStateOf("") }
                var showState by rememberSaveable { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }
                var confirmClear by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(revision, refresh) {
                    try {
                        val snapshot = withContext(Dispatchers.IO) {
                            AppDiagnostics.deviceState(this@DebugActivity) to AppDiagnostics.readEvents()
                        }
                        state = snapshot.first
                        events = snapshot.second
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        snackbar.showSnackbar(getString(R.string.could_not_read_diagnostics_tap_refresh_to_retry))
                    }
                }
                fun action(block: suspend () -> Unit) {
                    scope.launch {
                        busy = true
                        try {
                            block()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            AppDiagnostics.event("Debug", "Diagnostic action failed", error)
                            snackbar.showSnackbar(getString(R.string.could_not_complete_that_action_try_again))
                        } finally {
                            busy = false
                        }
                    }
                }
                Scaffold(topBar = {
                    TopAppBar(title = { Text(stringResource(R.string.debug_logs)) }, navigationIcon = {
                        BackButton(onClick = { finish() })
                    })
                }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.live_app_events_retained_across_restarts_zip_also_includes),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                                action {
                                    val report =
                                        withContext(Dispatchers.IO) { AppDiagnostics.report(this@DebugActivity) }
                                    getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                        ClipData.newPlainText(getString(R.string.portemon_diagnostics), report)
                                    )
                                    snackbar.showSnackbar(getString(R.string.copied_all_retained_app_events_and_device_settings))
                                }
                            }) { Text(stringResource(R.string.copy_all)) }
                            FilledTonalButton(
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    action {
                                        val archive = withContext(Dispatchers.IO) {
                                            AppDiagnostics.createArchive(this@DebugActivity)
                                        }
                                        startActivity(
                                            Intent.createChooser(
                                                AppDiagnostics.shareIntent(
                                                    this@DebugActivity,
                                                    archive
                                                ), getString(R.string.share_portemon_debug_zip)
                                            )
                                        )
                                    }
                                }) { Text(stringResource(R.string.share_zip)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !busy, onClick = { refresh++ }) { Text(stringResource(R.string.refresh)) }
                            TextButton(onClick = {
                                showState = !showState
                            }) { Text(if (showState) getString(R.string.hide_device_info) else getString(R.string.device_info)) }
                            TextButton(
                                enabled = !busy,
                                onClick = { confirmClear = true }) { Text(stringResource(R.string.clear_logs)) }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.filter_events)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        val entries = remember(events, query) {
                            events.split(Regex("(?m)(?=^\\d{4}-\\d{2}-\\d{2}T)"))
                                .filter { it.isNotBlank() && it.contains(query, ignoreCase = true) }
                                .asReversed().take(300)
                        }
                        Text(
                            stringResource(R.string.latest_300_events_newest_first_exports_include_all_saved),
                            style = MaterialTheme.typography.labelSmall
                        )
                        LazyColumn(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (showState) item {
                                SelectionContainer {
                                    Text(
                                        state,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                HorizontalDivider()
                            }
                            if (entries.isEmpty()) item { Text(stringResource(R.string.no_matching_events_use_portemon_to_record_new_activity)) }
                            items(entries) { entry ->
                                SelectionContainer {
                                    Text(
                                        entry.trimEnd(),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                HorizontalDivider(Modifier.padding(top = 8.dp))
                            }
                            item { Spacer(Modifier.height(12.dp)) }
                        }
                    }
                }
                if (confirmClear) AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text(stringResource(R.string.clear_saved_logs)) },
                    text = { Text(stringResource(R.string.this_removes_the_local_event_history_new_events_will)) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmClear = false
                            action { withContext(Dispatchers.IO) { AppDiagnostics.clear() } }
                        }) { Text(stringResource(R.string.clear)) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            confirmClear = false
                        }) { Text(stringResource(R.string.cancel)) }
                    })
            }
        }
    }
}
