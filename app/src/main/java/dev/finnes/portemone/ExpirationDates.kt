// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

internal fun expirationPickerDate(expiration: String, zone: ZoneId = ZoneId.systemDefault()): Long {
    val local = Instant.parse(expiration).atZone(zone)
    // Midnight is an exclusive boundary: the card was valid through the previous day.
    val day = if (local.toLocalTime() == LocalTime.MIDNIGHT) local.toLocalDate().minusDays(1) else local.toLocalDate()
    return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

internal fun expirationForDate(dayMillis: Long, previous: String?, zone: ZoneId = ZoneId.systemDefault()): String {
    // Confirming the same day must also preserve an issuer's exact expiration time.
    if (previous != null && expirationPickerDate(previous, zone) == dayMillis) return previous
    val day = Instant.ofEpochMilli(dayMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return day.plusDays(1).atStartOfDay(zone).toInstant().toString()
}
