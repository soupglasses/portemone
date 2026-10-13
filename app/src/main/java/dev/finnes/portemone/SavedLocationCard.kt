// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal fun StoreLocation.mapsIntent(): Intent {
    // Coordinates identify the saved pin even when an address is absent or ambiguous.
    val point = "$latitude,$longitude"
    return Intent(Intent.ACTION_VIEW, Uri.parse("geo:$point?q=${Uri.encode("$point ($name)")}"))
}

@Composable
internal fun SavedLocationCard(
    store: StoreLocation,
    brandColor: Int,
    modifier: Modifier = Modifier
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    var error by remember(store.id) { mutableStateOf<String?>(null) }
    fun openMaps() {
        error = null
        try {
            context.startActivity(store.mapsIntent())
        } catch (_: ActivityNotFoundException) {
            error = resources.getString(R.string.no_maps_app_is_available_to_open_this_location)
        }
    }
    Surface(
        modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(store.name, style = MaterialTheme.typography.titleMedium)
                store.address?.takeIf { it.isNotBlank() }?.let { address ->
                    Text(
                        address,
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = resources.getString(R.string.open_in_maps),
                            onClick = ::openMaps
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            StoreMap(
                listOf(store), store.id, brandColor,
                Modifier.fillMaxWidth().height(180.dp).testTag("location_map_${store.id}")
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = ::openMaps,
                    modifier = Modifier.testTag("location_maps_${store.id}")
                ) {
                    Icon(
                        painterResource(R.drawable.ic_open_in_new),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.open_in_maps))
                }
            }
            error?.let {
                Text(
                    it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
