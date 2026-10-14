// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import dev.finnes.portemone.diagnostics.AppDiagnostics
import dev.finnes.portemone.location.GeofenceCoordinator
import dev.finnes.portemone.location.LocationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A movement-triggered, bounded recovery job; never periodic or a foreground GPS service. */
class RegionRefreshJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        operation = scope.launch {
            val attempt = params.extras.getInt("attempt", 0)
            var retry = false
            try {
                if (LocationSettings(this@RegionRefreshJob).enabled) {
                    check(withTimeoutOrNull(30_000) {
                        GeofenceCoordinator(this@RegionRefreshJob).refreshNearby(force = true)
                        true
                    } == true) { getString(R.string.location_region_recovery_timed_out_open_the_app_to_refresh) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppDiagnostics.event("Location", "Region recovery failed; attempt=$attempt", error)
                LocationSettings(this@RegionRefreshJob).status = GeofenceCoordinator.errorMessage(error, resources)
                retry = attempt < MAX_RETRIES && LocationSettings(this@RegionRefreshJob).enabled
            } finally {
                if (isActive) jobFinished(params, false)
            }
            if (retry) schedule(this@RegionRefreshJob, attempt + 1)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        operation?.cancel()
        // Retry OS interruption (for example Doze) without running a location service.
        return LocationSettings(this).enabled
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val ID = 2002
        private const val MAX_RETRIES = 3
        fun schedule(context: Context, attempt: Int = 0) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (attempt == 0 && scheduler.getPendingJob(ID) != null) return
            val job = JobInfo.Builder(ID, ComponentName(context, RegionRefreshJob::class.java))
                .setExtras(PersistableBundle().apply { putInt("attempt", attempt) })
                .setMinimumLatency(if (attempt == 0) 0 else 30_000L * attempt)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                AppDiagnostics.event("Location", "Could not schedule region recovery; open the app to retry")
            }
        }
        fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java).cancel(ID) }
    }
}
