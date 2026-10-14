// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpirationDatesTest {
    @Test
    fun selectedDateSurvivesRepeatedEditsAcrossZonesAndDst() {
        for (zone in listOf("UTC", "Europe/Copenhagen", "America/New_York")) {
            for (day in listOf("2026-03-08", "2026-03-29", "2026-10-25", "2026-11-01")) {
                val selected = Instant.parse("${day}T00:00:00Z").toEpochMilli()
                val expires = expirationForDate(selected, null, ZoneId.of(zone))
                assertEquals(selected, expirationPickerDate(expires, ZoneId.of(zone)))
                assertEquals(expires, expirationForDate(selected, expires, ZoneId.of(zone)))
            }
        }
    }

    @Test
    fun confirmingIssuerDatePreservesItsExactTime() {
        val original = "2030-01-02T03:04:05Z"
        for (zone in listOf("UTC", "Europe/Copenhagen", "America/New_York")) {
            val id = ZoneId.of(zone)
            assertEquals(original, expirationForDate(expirationPickerDate(original, id), original, id))
        }
    }

    @Test
    fun newDateExpiresAtFollowingLocalMidnight() {
        val selected = Instant.parse("2026-03-29T00:00:00Z").toEpochMilli()
        assertEquals("2026-03-29T22:00:00Z", expirationForDate(selected, null, ZoneId.of("Europe/Copenhagen")))
    }
}
