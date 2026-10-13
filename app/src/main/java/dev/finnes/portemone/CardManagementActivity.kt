// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.finnes.portemone.ui.theme.WalletTheme

class CardManagementActivity : ComponentActivity() {
    private lateinit var model: CardManagementModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[CardManagementModel::class.java]
        if (savedInstanceState == null) receive(intent)
        val editId = intent.getStringExtra("edit_card_id")
        setContent {
            WalletTheme {
                CardManagementScreen(
                    model,
                    editId,
                    intent.getStringExtra("card_action"),
                    onClose = { finish() },
                    onOpen = {
                        startActivity(CardActivity.intent(this, it))
                    })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); receive(intent)
    }

    private fun receive(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java
            )
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

            else -> null
        }
        if (uri != null && uri.scheme in setOf("content", "file")) model.import(uri)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardManagementScreen(
    model: CardManagementModel,
    editId: String?,
    action: String?,
    onClose: () -> Unit,
    onOpen: (Card) -> Unit
) {
    val resources = LocalResources.current
    val state by model.state.collectAsStateWithLifecycle()
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var editRequested by rememberSaveable { mutableStateOf(false) }
    var archived by rememberSaveable { mutableStateOf(action == "archived") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.import(uri) else if (action == "import") onClose()
    }
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(state.message, editorId, state.review) {
        if (editorId == null && state.review == null) state.message?.let { snackbars.showSnackbar(it); model.clearMessage() }
    }
    LaunchedEffect(state.loading) {
        if (!state.loading && !editRequested) {
            editRequested = true
            if (editId != null) editorId = editId
            else if (action == "add") editorId = "__new__"
            else if (action == "import") picker.launch(
                arrayOf(
                    "application/vnd.apple.pkpass",
                    "application/zip",
                    "application/octet-stream"
                )
            )
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (archived) resources.getString(R.string.archived_cards) else resources.getString(R.string.cards)) },
                navigationIcon = { BackButton(onClick = onClose) })
        },
        snackbarHost = { SnackbarHost(snackbars) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !state.busy && !state.loading,
                    onClick = { editorId = "__new__" }) { Text(stringResource(R.string.add_a_card)) }
                FilledTonalButton(
                    enabled = !state.busy && state.review == null,
                    onClick = {
                        picker.launch(
                            arrayOf(
                                "application/vnd.apple.pkpass",
                                "application/zip",
                                "application/octet-stream"
                            )
                        )
                    }) { Text(stringResource(R.string.import_pkpass)) }
            }
            Text(
                stringResource(R.string.cards_are_saved_on_your_phone_when_encrypted_backup),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !archived,
                    onClick = { archived = false },
                    label = { Text(stringResource(R.string.your_cards)) })
                FilterChip(
                    selected = archived,
                    onClick = { archived = true },
                    label = { Text(stringResource(R.string.archived)) })
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val cards = state.cards.filter { it.archived == archived }
            if (!state.loading && cards.isEmpty()) Text(if (archived) resources.getString(R.string.no_archived_cards) else resources.getString(R.string.add_your_first_card_or_import_a_pass_from))
            cards.forEach { card ->
                MembershipCardRow(card, false, onClick = { onOpen(card) })
            }
        }
    }
    if (!state.loading) editorId?.let { id ->
        val original = remember(id) { state.cards.find { it.id == id } }
        if (id == "__new__" || original != null) CardEditor(
            original, state.busy, state.message,
            onClose = { editorId = null; model.clearMessage(); if (action == "add") onClose() },
            onSave = {
                model.save(it, expected = original) {
                    editorId = null; if (action == "add") onClose()
                }
            })
    }
    state.review?.let { pass ->
        PassImportReview(
            pass, state.existing, state.busy, state.message,
            onCancel = { model.dismissReview(); model.clearMessage(); if (action == "import") onClose() },
            onImport = { keep, risk ->
                model.acceptImport(
                    keep,
                    risk
                ) { if (action == "import") onClose() }
            })
    }

}

@Composable
internal fun CardActions(card: Card, model: CardManagementModel, onDeleted: () -> Unit) {
    val resources = LocalResources.current
    val state by model.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable(card.id) { mutableStateOf(false) }
    var deleting by rememberSaveable(card.id) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val original = remember(editing) { card }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.apple.pkpass")) { uri ->
            if (uri != null) model.exportOriginal(card.id, uri)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(
            enabled = !state.busy,
            onClick = { model.clearMessage(); editing = true }) { Text(stringResource(R.string.edit)) }
        TextButton(
            enabled = !state.busy,
            onClick = { model.archive(card) }) { Text(if (card.archived) resources.getString(R.string.restore) else resources.getString(R.string.archive)) }
        Box {
            TextButton(enabled = !state.busy, onClick = { more = true }) { Text(stringResource(R.string.more)) }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                if (card.source?.format == "pkpass") DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_original_pkpass)) },
                    onClick = {
                        more = false; exporter.launch("card.pkpass")
                    })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    onClick = { more = false; deleting = true })
            }
        }
    }
    if (!editing) state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (editing) CardEditor(
        original, state.busy, state.message,
        onClose = { editing = false; model.clearMessage() },
        onSave = { model.save(it, expected = original) { editing = false } })
    if (deleting) AlertDialog(
        onDismissRequest = { if (!state.busy) deleting = false },
        title = { Text(stringResource(R.string.cardmanagementactivity_delete, card.brandName, card.cardName)) },
        text = { Text(stringResource(R.string.permanently_removes_this_card_saved_locations_and_original_import)) },
        confirmButton = {
            TextButton(
                enabled = !state.busy,
                onClick = { model.delete(card, onDeleted) }) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(
                enabled = !state.busy,
                onClick = { deleting = false }) { Text(stringResource(R.string.keep_card)) }
        })
}
