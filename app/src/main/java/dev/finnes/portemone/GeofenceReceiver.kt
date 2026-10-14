// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.finnes.portemone.diagnostics.AppDiagnostics
import dev.finnes.portemone.location.FrameworkGeofenceBackend
import dev.finnes.portemone.location.GeofenceCoordinator
import dev.finnes.portemone.location.LocationSettings
import dev.finnes.portemone.location.PlayServicesGeofenceBackend
import kotlinx.coroutines.*

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event =
            (PlayServicesGeofenceBackend.decode(intent, context.resources) ?: FrameworkGeofenceBackend.decode(intent))
                ?: run {
                    AppDiagnostics.event("Geofence", "Received empty geofence broadcast")
                    return
                }
        val started = SystemClock.elapsedRealtime()
        val process =
            ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        AppDiagnostics.event(
            "Geofence",
            "Broadcast received: ${GeofenceCoordinator.transitionName(event.transition)}, requests=${
                event.requestIds.map {
                    it.substringAfter(':')
                }
            }, processImportance=${process.importance}, ${GeofenceCoordinator.fixSummary(event.location)}"
        )
        val pending = goAsync()
        receiverScope.launch {
            try {
                withTimeout(8_000) { GeofenceCoordinator(context).onEvent(event) }
            } catch (error: Exception) {
                AppDiagnostics.event("Geofence", "Receiver operation failed", error)
                LocationSettings(context).status = GeofenceCoordinator.errorMessage(error, context.resources)
                if (LocationSettings(context).enabled) RegionRefreshJob.schedule(context)
            } finally {
                AppDiagnostics.event(
                    "Geofence",
                    "Broadcast finished in ${SystemClock.elapsedRealtime() - started}ms"
                )
                // Android may reclaim a receiver-only process after finish(). Drain the
                // existing bounded log writer first so a field-test event survives it.
                withContext(NonCancellable + Dispatchers.IO) { runCatching { AppDiagnostics.flush() } }
                pending.finish()
            }
        }
    }
}

class GeofenceRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppDiagnostics.event("Geofence", "Restore broadcast: ${intent.action}")
        val pending = goAsync()
        receiverScope.launch {
            try {
                withTimeout(8_000) {
                    GeofenceCoordinator(context).restore()
                    GeofenceCoordinator(context).reconcileCardValidity()
                }
            } catch (error: Exception) {
                AppDiagnostics.event("Geofence", "Receiver operation failed", error)
                LocationSettings(context).status = GeofenceCoordinator.errorMessage(error, context.resources)
                if (LocationSettings(context).enabled) RegionRefreshJob.schedule(context)
            } finally {
                AppDiagnostics.event("Geofence", "Restore broadcast finished")
                withContext(NonCancellable + Dispatchers.IO) { runCatching { AppDiagnostics.flush() } }
                pending.finish()
            }
        }
    }
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
