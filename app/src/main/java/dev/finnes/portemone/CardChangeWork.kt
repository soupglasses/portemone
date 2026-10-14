// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.finnes.portemone.cards.LocalCardRepository
import dev.finnes.portemone.diagnostics.AppDiagnostics
import dev.finnes.portemone.location.GeofenceCoordinator
import dev.finnes.portemone.location.LocationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The database outbox owns pending work; scheduler requests contain no card or location data. */
class CardChangeWork(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val completed = withTimeoutOrNull(30_000) {
                do {
                    GeofenceCoordinator(applicationContext).reconcileSavedCards()
                } while (LocalCardRepository.get(applicationContext).pendingChanges().isNotEmpty())
                true
            } == true
            if (completed && LocalCardRepository.get(applicationContext).pendingChanges()
                    .isEmpty()
            ) Result.success()
            else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppDiagnostics.event(
                "Cards",
                "Saved cards are waiting for automatic presentation",
                error
            )
            LocationSettings(applicationContext).status =
                applicationContext.getString(R.string.location_card_saved_automatic_presentation_will_retry)
            Result.retry()
        }
    }

    companion object {
        internal fun enqueue(context: Context) {
            // KEEP can lose an edit arriving as the current worker finishes. Appending ensures
            // another drain; the outbox coalesces edits and makes redundant drains harmless.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "saved-card-changes", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CardChangeWork>().build()
            )
        }
    }
}
