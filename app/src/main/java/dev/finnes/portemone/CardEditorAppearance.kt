// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class EditorLogo(
    val image: String?, val removed: Boolean, val loading: Boolean, val error: String?,
    val choose: () -> Unit, val remove: () -> Unit,
)

@Composable
internal fun rememberEditorLogo(original: String?): EditorLogo {
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by rememberSaveable { mutableStateOf<String?>(null) }
    var removed by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var image by remember(token, removed) { mutableStateOf(if (removed) null else original) }
    var restoring by remember(token) { mutableStateOf(token != null) }
    LaunchedEffect(token) {
        val selected = token ?: return@LaunchedEffect
        try {
            image = withContext(Dispatchers.IO) { CardLogoImport.restore(context, selected) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = resources.getString(R.string.the_draft_image_is_unavailable_choose_it_again)
        } finally {
            restoring = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            error = null
            scope.launch {
                try {
                    val selected =
                        withContext(Dispatchers.IO) { CardLogoImport.import(context, uri) }
                    removed = false
                    token = selected
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error =
                        failure.userMessage(resources, R.string.could_not_open_this_image_try_another_file)
                } finally {
                    importing = false
                }
            }
        }
    }
    return EditorLogo(
        image, removed, importing || restoring, error,
        choose = { picker.launch(arrayOf("image/*")) },
        remove = { token = null; removed = true; error = null })
}

@Composable
internal fun CardEditorAppearance(
    card: Card, logo: EditorLogo, enabled: Boolean, validCode: Boolean,
    onColor: (Int) -> Unit, onCustomColor: () -> Unit
) {
    val resources = LocalResources.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var logoMenu by remember { mutableStateOf(false) }
    val hasLogo = card.logoImage != null || card.logo != R.drawable.ic_card
    val preview = if (validCode) card else card.copy(barcodeType = BarcodeType.NONE)
    CardFace(
        preview.copy(
            brandName = card.brandName.ifBlank { resources.getString(R.string.brand) },
            cardName = card.cardName.ifBlank { resources.getString(R.string.card) }), Modifier.fillMaxWidth(),
        onLogoClick = if (enabled) ({ if (hasLogo) logoMenu = true else logo.choose() }) else null
    )
    if (!validCode) {
        Text(
            if (card.rawValue.isEmpty()) resources.getString(R.string.enter_a_code_to_preview_it_here)
            else resources.getString(R.string.this_value_cannot_be_shown_as, resources.getString(card.barcodeType.labelRes)),
            style = MaterialTheme.typography.bodySmall
        )
    }
    val suggestions by produceState<List<CardColorSuggestion>>(emptyList(), card.logoImage) {
        value = emptyList()
        value = withContext(Dispatchers.Default) {
            card.logoImage?.let {
                runCatching { CardAppearance.suggestions(it) }.getOrDefault(
                    emptyList()
                )
            }.orEmpty()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.background), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Box {
                TextButton(enabled = enabled, onClick = {
                    if (hasLogo) logoMenu = true else logo.choose()
                }) {
                    Text(if (hasLogo) resources.getString(R.string.logo) else resources.getString(R.string.add_logo))
                    if (hasLogo) Icon(
                        painterResource(R.drawable.ic_expand_more), contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp).size(18.dp)
                    )
                }
                DropdownMenu(expanded = logoMenu, onDismissRequest = { logoMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.choose_another_logo)) }, onClick = {
                        logoMenu = false; logo.choose()
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove_logo)) }, onClick = {
                        logoMenu = false; logo.remove()
                    })
                }
            }
        }
        val colors = suggestions.ifEmpty {
            listOf(0xff146c64, 0xff245a81, 0xff694b78, 0xffa54835, 0xff42474e)
                .map { CardColorSuggestion(it.toInt()) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            colors.forEachIndexed { index, suggestion ->
                val selected = card.brandColor == suggestion.color
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .selectable(
                            selected, enabled = enabled, role = Role.RadioButton,
                            onClick = { onColor(suggestion.color) })
                        .semantics {
                            contentDescription = resources.getString(R.string.color_suggestion, index + 1, String.format(
                                    java.util.Locale.ROOT,
                                    "%06X",
                                    suggestion.color and 0xFFFFFF
                                ))
                        }, contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(suggestion.color),
                                        Color(CardAppearance.shade(suggestion.color))
                                    )
                                )
                            )
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = Color(CardAppearance.foreground(suggestion.color)),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            val customSelected = colors.none { it.color == card.brandColor }
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .clickable(enabled = enabled, role = Role.Button) {
                        focus.clearFocus(); keyboard?.hide(); onCustomColor()
                    }
                    .semantics { contentDescription = resources.getString(R.string.custom_background_color) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(
                            if (customSelected) Brush.linearGradient(
                                listOf(
                                    Color(card.brandColor),
                                    Color(CardAppearance.shade(card.brandColor))
                                )
                            )
                            else Brush.sweepGradient(
                                listOf(
                                    Color(0xffe57373),
                                    Color(0xffffd54f),
                                    Color(0xff81c784),
                                    Color(0xff64b5f6),
                                    Color(0xffba68c8),
                                    Color(0xffe57373)
                                )
                            )
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_edit),
                        contentDescription = null,
                        tint = if (customSelected) Color(CardAppearance.foreground(card.brandColor)) else Color(
                            0xff202124
                        ),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
    if (logo.loading) Text(stringResource(R.string.loading_logo), style = MaterialTheme.typography.bodySmall)
    logo.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
