// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource

import dev.finnes.portemone.imports.localizedPassNotice

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.finnes.portemone.imports.PKPassImporter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PassImportReview(
    pass: PKPassImporter.Package, existing: Card?, busy: Boolean, error: String?,
    onCancel: () -> Unit, onImport: (Boolean, Boolean) -> Unit
) {
    val resources = LocalResources.current
    var keepLocations by rememberSaveable(pass.card.id) { mutableStateOf(existing != null) }
    val card = pass.card
    Dialog(
        onDismissRequest = { if (!busy) onCancel() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                TopAppBar(
                    title = { Text(if (existing == null) resources.getString(R.string.review_pass) else resources.getString(R.string.review_card_update)) },
                    navigationIcon = {
                        TextButton(enabled = !busy, onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    })
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "${card.brandName} ${card.cardName}",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        stringResource(R.string.pass_format, resources.getString(card.kind.labelRes), resources.getString(card.barcodeType.labelRes))
                    )
                    if (existing != null) {
                        Text(stringResource(R.string.this_pass_matches_a_saved_card_and_will_update))
                        Text(if (card.rawValue != existing.rawValue || card.barcodeType != existing.barcodeType) resources.getString(R.string.the_scannable_code_changes) else resources.getString(R.string.the_scannable_code_is_unchanged))
                        Row {
                            Checkbox(
                                checked = keepLocations,
                                enabled = !busy,
                                onCheckedChange = { keepLocations = it })
                            Text(
                                stringResource(R.string.keep_locations_or_replace, resources.getQuantityString(R.plurals.keep_saved_locations, existing.stores.size, existing.stores.size), resources.getQuantityString(R.plurals.replace_suggested_locations, card.stores.size, card.stores.size)),
                                Modifier.padding(top = 10.dp)
                            )
                        }
                    } else Text(resources.getQuantityString(R.plurals.suggested_location_count, card.stores.size, card.stores.size, MAX_CARD_LOCATIONS))
                    card.statusLabelRes()?.let { resources.getString(it) }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    card.expiresAt?.let { Text(stringResource(R.string.expires, it.displayDate(resources))) }
                    CardFields(card)
                    HorizontalDivider()
                    Text(stringResource(pass.verification.summaryRes), style = MaterialTheme.typography.titleMedium)
                    if (pass.verification.warnings.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.large
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pass.verification.warnings.forEach { Text(it.localizedPassNotice(resources)) }
                                Text(stringResource(R.string.only_continue_if_you_trust_the_source_and_accept))
                            }
                        }
                        Button(
                            enabled = !busy,
                            onClick = { onImport(keepLocations, true) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (busy) resources.getString(R.string.importing) else resources.getString(R.string.accept_risk_and_import))
                        }
                    } else Button(
                        enabled = !busy,
                        onClick = { onImport(keepLocations, false) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (busy) resources.getString(R.string.importing) else if (existing == null) resources.getString(R.string.import_card) else resources.getString(R.string.update_card))
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    pass.limitations.forEach {
                        Text(
                            it.localizedPassNotice(resources),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        stringResource(R.string.the_original_file_and_artwork_are_saved_on_your),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
internal fun CardFields(card: Card) {
    val resources = LocalResources.current
    card.description?.takeIf { it.isNotBlank() }?.let { description ->
        Text(stringResource(R.string.description), style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
    card.artworkImage?.let { data ->
        val bitmap = remember(data) { PassImages.decode(data).asImageBitmap() }
        Image(
            bitmap,
            contentDescription = resources.getString(R.string.pass_artwork),
            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
        )
    }
    card.relevantAt?.let { Text(stringResource(R.string.relevant, it.displayDate(resources)), style = MaterialTheme.typography.bodyMedium) }
    CardFieldSection.entries.forEach { section ->
        val fields = card.fields.filter { it.section == section }
        if (fields.isNotEmpty()) {
            Text(
                stringResource(section.labelRes),
                style = MaterialTheme.typography.titleSmall
            )
            fields.forEach { field ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (field.label.isNotBlank()) Text(
                        field.label,
                        style = MaterialTheme.typography.labelMedium
                    )
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            field.value,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
