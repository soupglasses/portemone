// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.platform.LocalResources
import android.text.format.DateUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Age is presentation only: keeping this label current must not request location. */
@Composable
internal fun NearbyUpdateLabel(updatedAtMillis: Long, lifecycle: Lifecycle) {
    val resources = LocalResources.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycle, updatedAtMillis) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(15_000L)
            }
        }
    }
    val label = when {
        updatedAtMillis <= 0L -> resources.getString(R.string.last_updated_not_yet)
        now - updatedAtMillis < DateUtils.MINUTE_IN_MILLIS -> resources.getString(R.string.last_updated_just_now)
        else -> resources.getString(R.string.last_updated, DateUtils.getRelativeTimeSpanString(updatedAtMillis, now, DateUtils.MINUTE_IN_MILLIS))
    }
    Text(label, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
