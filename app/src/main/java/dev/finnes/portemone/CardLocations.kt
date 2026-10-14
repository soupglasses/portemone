// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource

import dev.finnes.portemone.cards.editor.AndroidLocationEditorSource
import dev.finnes.portemone.cards.editor.LocationEditorState

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun CardLocations(
    card: Card,
    enabled: Boolean = true,
    onChange: (List<StoreLocation>) -> Unit
) {
    val resources = LocalResources.current
    var selectedId by rememberSaveable(card.id) { mutableStateOf(card.stores.firstOrNull()?.id) }
    var editorId by rememberSaveable(card.id) { mutableStateOf<String?>(null) }
    var exploring by rememberSaveable(card.id) { mutableStateOf(false) }
    val selected = card.stores.firstOrNull { it.id == selectedId } ?: card.stores.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.store_locations),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    resources.getQuantityString(R.plurals.saved_location_count, card.stores.size, card.stores.size, MAX_CARD_LOCATIONS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (card.stores.isNotEmpty()) TextButton(
                enabled = enabled && card.stores.size < MAX_CARD_LOCATIONS,
                onClick = { editorId = NEW_LOCATION }) { Text(stringResource(R.string.add)) }
        }
        if (card.stores.isEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.no_saved_stores),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.add_a_store_and_choose_how_close_you_need),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    FilledTonalButton(
                        enabled = enabled,
                        onClick = { editorId = NEW_LOCATION }) { Text(stringResource(R.string.add_a_location)) }
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().height(210.dp).clip(MaterialTheme.shapes.extraLarge)) {
                StoreMap(
                    card.stores, selected?.id, card.brandColor, Modifier.fillMaxSize(),
                    onOpen = { exploring = true })
            }
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column {
                    card.stores.forEachIndexed { index, store ->
                        if (index > 0) HorizontalDivider(
                            Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)
                        )
                        Row(
                            Modifier.fillMaxWidth().clickable { selectedId = store.id }
                                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected?.id == store.id,
                                onClick = { selectedId = store.id })
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(store.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(if (store.origin == LocationOrigin.IMPORTED) R.string.imported_arrival_radius else R.string.arrival_radius_meters, store.radiusMeters.roundToInt()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                enabled = enabled,
                                onClick = { editorId = store.id }) { Text(stringResource(R.string.edit)) }
                        }
                    }
                }
            }
        }
    }
    if (exploring) MapExplorer(
        card,
        selected?.id,
        editable = enabled,
        onSelect = { selectedId = it },
        onClose = { exploring = false },
        onEdit = { exploring = false; editorId = it },
        onAdd = { exploring = false; editorId = NEW_LOCATION })
    editorId?.let { id ->
        val existing = remember(card.id, id) { card.stores.firstOrNull { it.id == id } }
        if (id == NEW_LOCATION || existing != null) {
            key(card.id, id) {
                LocationEditor(
                    card.brandColor, existing, selected, onClose = { editorId = null },
                    onSave = { store ->
                        onChange.invoke(if (existing == null) card.stores + store else card.stores.map { if (it.id == store.id) store else it })
                        selectedId = store.id
                        editorId = null
                    },
                    onRemove = {
                        onChange.invoke(card.stores.filterNot { it.id == id })
                        editorId = null
                    })
            }
        }
    }
}

private const val NEW_LOCATION = "__new_location__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapExplorer(
    card: Card, selectedId: String?, editable: Boolean, onSelect: (String) -> Unit,
    onClose: () -> Unit, onEdit: (String) -> Unit, onAdd: () -> Unit
) {
    val resources = LocalResources.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.store_areas)) },
                    navigationIcon = {
                        TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                    },
                    actions = {
                        if (editable) TextButton(
                            enabled = card.stores.size < MAX_CARD_LOCATIONS,
                            onClick = onAdd
                        ) { Text(stringResource(R.string.add)) }
                    })
                Box(Modifier.weight(1f)) {
                    StoreMap(
                        card.stores,
                        selectedId,
                        card.brandColor,
                        Modifier.fillMaxSize(),
                        interactive = true,
                        onSelect = onSelect
                    )
                    Surface(
                        Modifier.align(Alignment.TopCenter).padding(16.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(
                            stringResource(R.string.tap_a_circle_to_select_a_store),
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                card.stores.firstOrNull { it.id == selectedId }?.let { store ->
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(store.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(if (store.origin == LocationOrigin.IMPORTED) R.string.imported_arrival_radius else R.string.arrival_radius_meters, store.radiusMeters.roundToInt()),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (editable) FilledTonalButton(onClick = { onEdit(store.id) }) { Text(stringResource(R.string.edit_area)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationEditor(
    brandColor: Int,
    original: StoreLocation?,
    anchor: StoreLocation?,
    onClose: () -> Unit,
    onSave: (StoreLocation) -> Unit,
    onRemove: () -> Unit
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val source = remember(context) { AndroidLocationEditorSource(context) }
    val scope = rememberCoroutineScope()
    val state = rememberSaveable(saver = LocationEditorState.saver(original, anchor)) {
        LocationEditorState(original, anchor)
    }
    var coordinatesOpen by rememberSaveable { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }

    fun close() {
        if (state.dirty) discard = true else onClose()
    }

    fun save() {
        attempted = true
        if (state.lat == null || state.lon == null) coordinatesOpen = true
        if (!state.valid) return
        onSave(state.draft)
    }

    var locationRequest by remember {
        mutableIntStateOf(if (state.lat == null && state.lon == null && source.hasPreciseLocation()) 1 else 0)
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (source.hasPreciseLocation()) locationRequest++
            else state.error = R.string.location_permission_was_not_granted_search_an_address_or
        }
    LaunchedEffect(locationRequest) {
        if (locationRequest != 0) state.locate(source::currentPosition)
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler { close() }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                TopAppBar(
                    title = { Text(if (original == null) resources.getString(R.string.add_a_location) else resources.getString(R.string.edit_store_area)) },
                    navigationIcon = { TextButton(onClick = { close() }) { Text(stringResource(R.string.cancel)) } },
                    actions = { TextButton(onClick = { save() }) { Text(if (original == null) resources.getString(R.string.add_location) else resources.getString(R.string.save_changes)) } })
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = { state.name = it },
                            label = { Text(stringResource(R.string.store_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            isError = attempted && state.name.isBlank()
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = state.search,
                                onValueChange = { state.search = it },
                                label = { Text(stringResource(R.string.search_an_address_or_store)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                enabled = !state.searching
                            )
                            TextButton(enabled = state.search.isNotBlank() && !state.searching, onClick = {
                                scope.launch { state.find(source::search) }
                            }) { Text(if (state.searching) resources.getString(R.string.finding) else resources.getString(R.string.find)) }
                        }
                        TextButton(enabled = !state.locating, onClick = {
                            if (source.hasPreciseLocation()) locationRequest++
                            else permission.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                )
                            )
                        }) { Text(if (state.locating) resources.getString(R.string.finding_your_location) else resources.getString(R.string.use_my_location)) }
                        state.searchError?.let {
                            Text(
                                stringResource(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        state.searchResults.forEach { result ->
                            TextButton(onClick = {
                                state.selectResult(result)
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    result.label,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    if (state.lat != null && state.lon != null) key(state.mapPositionRevision) {
                        Box(Modifier.fillMaxWidth().height(280.dp)) {
                            StoreMap(
                                listOf(state.draft),
                                state.draft.id,
                                brandColor,
                                Modifier.fillMaxSize(),
                                interactive = true,
                                onPlace = { newLat, newLon ->
                                    state.placePin(newLat, newLon)
                                })
                            Surface(
                                Modifier.align(Alignment.TopCenter).padding(12.dp),
                                shape = MaterialTheme.shapes.large
                            ) {
                                Text(
                                    stringResource(R.string.tap_the_map_to_move_the_store_pin),
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    Column(
                        Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.arrival_radius), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.your_card_appears_inside_this_circle),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            OutlinedTextField(
                                value = state.radius,
                                onValueChange = { state.radius = it },
                                modifier = Modifier.width(112.dp),
                                singleLine = true,
                                label = { Text(stringResource(R.string.meters)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = attempted && state.meters == null
                            )
                        }
                        Slider(
                            value = (state.meters ?: 150f).coerceIn(100f, 500f),
                            onValueChange = { state.radius = ((it / 10).roundToInt() * 10).toString() },
                            valueRange = 100f..500f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            stringResource(R.string.use_the_slider_for_100_to_500_meters_or),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (attempted && state.meters == null) Text(
                            stringResource(R.string.choose_a_radius_between_50_and_1_000_meters),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = {
                            coordinatesOpen = !coordinatesOpen
                        }) { Text(if (coordinatesOpen) resources.getString(R.string.hide_coordinates) else resources.getString(R.string.edit_exact_coordinates)) }
                        if (coordinatesOpen) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = state.latitude,
                                    onValueChange = {
                                        state.editLatitude(it)
                                    },
                                    label = { Text(stringResource(R.string.latitude)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                    isError = attempted && state.lat == null
                                )
                                OutlinedTextField(
                                    value = state.longitude,
                                    onValueChange = {
                                        state.editLongitude(it)
                                    },
                                    label = { Text(stringResource(R.string.longitude)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                    isError = attempted && state.lon == null
                                )
                            }
                            TextButton(
                                onClick = { state.centerMap() },
                                enabled = state.lat != null && state.lon != null
                            ) { Text(stringResource(R.string.center_map_on_coordinates)) }
                        }
                        state.error?.let {
                            Text(
                                stringResource(it),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (original != null) TextButton(
                            onClick = { deleting = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text(stringResource(R.string.remove_location)) }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.discard_changes)) },
        text = { Text(stringResource(R.string.your_location_changes_haven_t_been_saved)) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.keep_editing)) } })
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.remove_this_location)) },
        text = { Text(stringResource(R.string.remove_this_location_from_the_card_changes_apply_when)) },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                onRemove()
            }) { Text(stringResource(R.string.remove)) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } })
}
