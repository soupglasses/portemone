// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.finnes.portemone.cards.LocalCardRepository
import dev.finnes.portemone.location.GeofenceCoordinator
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Inexact expiry cleanup avoids asking for an unrelated exact-alarm permission. */
internal object CardExpiration {
    fun schedule(context: Context, cards: List<Card> = LocalCardRepository.get(context).cards()) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(context, 0, Intent(context, CardExpirationReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.cancel(intent)
        val now = Instant.now()
        val next = cards.filter { !it.voided }.mapNotNull { it.expiresAt?.let(Instant::parse) }
            .filter { it.isAfter(now) }.minOrNull() ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), intent)
    }
}

class CardExpirationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try { kotlinx.coroutines.withTimeout(8_000) { GeofenceCoordinator(context).reconcileCardValidity() } }
            catch (_: Exception) { RegionRefreshJob.schedule(context) }
            finally { pending.finish() }
        }
    }
    companion object { private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
