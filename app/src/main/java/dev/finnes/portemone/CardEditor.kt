// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.finnes.portemone.cards.editor.CardLocations
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardEditor(
    original: Card?,
    busy: Boolean,
    error: String?,
    onClose: () -> Unit,
    onSave: (Card) -> Unit
) {
    val resources = LocalResources.current
    val draftId = rememberSaveable { original?.id ?: UUID.randomUUID().toString() }
    val template = remember {
        original ?: Card(
            draftId,
            "",
            "",
            R.drawable.ic_card,
            0xFF146C64.toInt(),
            0xFFFFFFFF.toInt(),
            BarcodeType.CODE_128,
            ""
        )
    }
    var stores by rememberSaveable(stateSaver = StoreLocationsSaver) { mutableStateOf(template.stores) }
    var brand by rememberSaveable { mutableStateOf(template.brandName) }
    var label by rememberSaveable { mutableStateOf(template.cardName) }
    var raw by rememberSaveable { mutableStateOf(template.rawValue) }
    var description by rememberSaveable { mutableStateOf(template.description.orEmpty()) }
    var typeName by rememberSaveable { mutableStateOf(template.barcodeType.name) }
    var color by rememberSaveable { mutableIntStateOf(template.brandColor) }
    var shaded by rememberSaveable { mutableStateOf(template.shaded) }
    val logo = rememberEditorLogo(template.logoImage)
    val locked = busy || logo.loading
    var expiration by rememberSaveable { mutableStateOf(template.expiresAt.orEmpty()) }
    var discard by remember { mutableStateOf(false) }
    var pickingExpiration by remember { mutableStateOf(false) }
    var pickingColor by rememberSaveable { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }
    val draft = template.copy(
        brandName = brand.trim(),
        cardName = label.trim(),
        rawValue = raw,
        description = description.takeIf { it.isNotBlank() },
        barcodeType = BarcodeType.valueOf(typeName),
        brandColor = color,
        expiresAt = expiration.takeIf { it.isNotBlank() },
        logoImage = logo.image,
        logo = if (logo.removed || logo.image != null) R.drawable.ic_card else template.logo,
        shaded = shaded,
        stores = stores
    )
    val dirty = draft != template
    val barcodeError = remember(raw, typeName, draft.barcodeEncoding, resources.configuration.locales.toLanguageTags()) {
        try {
            validateBarcode(draft)
            null
        } catch (failure: IllegalArgumentException) {
            failure.userMessage(resources, R.string.could_not_save_this_card_try_again)
        }
    }

    fun close() {
        if (!locked) {
            if (dirty) discard = true else onClose()
        }
    }
    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler { close() }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                TopAppBar(
                    title = { Text(if (original == null) resources.getString(R.string.add_a_card) else resources.getString(R.string.edit_card)) },
                    navigationIcon = {
                        TextButton(enabled = !locked, onClick = ::close) { Text(stringResource(R.string.cancel)) }
                    },
                    actions = {
                        TextButton(enabled = !locked && barcodeError == null, onClick = {
                            try {
                                validateCard(draft); validation = null; onSave(draft)
                            } catch (failure: IllegalArgumentException) {
                                validation = failure.userMessage(resources, R.string.could_not_save_this_card_try_again)
                            }
                        }) { Text(if (busy) resources.getString(R.string.saving) else resources.getString(R.string.save)) }
                    })
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CardEditorAppearance(
                        draft,
                        logo,
                        !locked,
                        validCode = barcodeError == null,
                        onColor = { color = it; shaded = true },
                        onCustomColor = { pickingColor = true })
                    OutlinedTextField(
                        brand,
                        { brand = it },
                        label = { Text(stringResource(R.string.issuer)) },
                        placeholder = { Text(stringResource(R.string.brand)) },
                        singleLine = true,
                        enabled = !locked,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        label,
                        { label = it },
                        label = { Text(stringResource(R.string.label)) },
                        placeholder = { Text(stringResource(R.string.card)) },
                        supportingText = { Text(stringResource(R.string.choose_a_name_that_helps_you_tell_your_cards)) },
                        singleLine = true,
                        enabled = !locked,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(stringResource(R.string.code_format), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.match_the_format_on_your_original_card_changing_formats),
                        style = MaterialTheme.typography.bodySmall
                    )
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BarcodeType.entries.forEach { type ->
                            FilterChip(
                                selected = typeName == type.name,
                                enabled = !locked,
                                onClick = { typeName = type.name; validation = null },
                                label = { Text(stringResource(type.labelRes)) })
                        }
                    }
                    OutlinedTextField(
                        raw,
                        { raw = it; validation = null },
                        label = { Text(stringResource(R.string.barcode_value)) },
                        isError = raw.isNotEmpty() && barcodeError != null,
                        supportingText = {
                            Text(
                                if (raw.isNotEmpty()) barcodeError
                                    ?: resources.getString(draft.barcodeType.valueHintRes) else resources.getString(draft.barcodeType.valueHintRes)
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        enabled = !locked,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        description,
                        { description = it },
                        label = { Text(stringResource(R.string.description_optional)) },
                        supportingText = { Text(stringResource(R.string.shown_in_card_details_alongside_your_locations)) },
                        minLines = 2,
                        enabled = !locked,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(if (expiration.isBlank()) resources.getString(R.string.no_expiration_date) else resources.getString(R.string.expires, expiration.displayDate(resources)))
                    Row {
                        TextButton(
                            enabled = !locked,
                            onClick = {
                                pickingExpiration = true
                            }) { Text(stringResource(R.string.choose_expiration_date)) }
                        if (expiration.isNotBlank()) TextButton(
                            enabled = !locked,
                            onClick = { expiration = "" }) { Text(stringResource(R.string.clear)) }
                    }
                    if (original?.source != null) Text(
                        stringResource(R.string.edits_apply_to_your_saved_card_the_original_file),
                        style = MaterialTheme.typography.bodySmall
                    )
                    (validation ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    CardLocations(draft, enabled = !locked, onChange = { stores = it })
                }
            }
        }
    }
    if (pickingColor) CardColorPicker(color, onDismiss = { pickingColor = false }, onSelected = {
        color = it; shaded = true; pickingColor = false
    })
    if (pickingExpiration) {
        val dateState =
            rememberDatePickerState(initialSelectedDateMillis = expiration.takeIf { it.isNotBlank() }
                ?.let {
                    runCatching { expirationPickerDate(it) }.getOrNull()
                })
        DatePickerDialog(
            onDismissRequest = { pickingExpiration = false },
            confirmButton = {
                TextButton(enabled = dateState.selectedDateMillis != null, onClick = {
                    expiration = expirationForDate(dateState.selectedDateMillis!!, expiration.takeIf { it.isNotBlank() })
                    pickingExpiration = false
                }) { Text(stringResource(R.string.use_date)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pickingExpiration = false
                }) { Text(stringResource(R.string.cancel)) }
            }) {
            DatePicker(dateState)
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.discard_changes)) },
        text = { Text(stringResource(R.string.your_card_changes_haven_t_been_saved)) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.keep_editing)) } })
}
