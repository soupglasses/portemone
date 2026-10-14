// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.diagnostics.DiagnosticRedaction
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRedactionTest {
    @Test
    fun arbitraryMembershipValuesAreRemovedWithoutNumericAssumptions() {
        val secrets =
            arrayOf("AB-91-XZ", "00742", "https://member.example/qr?k=secret", "Member XY 73")
        DiagnosticRedaction.protect(*secrets)
        val result = DiagnosticRedaction.redact("Registration failed; values=${secrets.toList()}")
        secrets.forEach { assertFalse(result.contains(it)) }
        assertTrue(result.contains("Registration failed"))
    }

    @Test
    fun coordinateFormatsAreRemovedWhileUsefulMeasurementsRemain() {
        val result =
            DiagnosticRedaction.redact("2026-10-13T21:27:55.123456Z lat=+55.123456 lon=-.123456 at=(1.23456E-4, -123.987654); accuracy=12.5m; API=37")
        listOf("lat=+55.123456", "lon=-.123456", "1.23456", "123.987654").forEach {
            assertFalse(result.contains(it))
        }
        assertTrue(result.contains("accuracy=12.5m; API=37"))
        assertTrue(result.startsWith("2026-10-13T21:27:55.123456Z"))
    }

    @Test
    fun rejectedValuesNeverEnterExceptionDiagnostics() {
        val root = IllegalArgumentException("unsaved-QR-secret 55.12345")
        val error = IllegalStateException("another-unsaved-value", root)
        error.addSuppressed(RuntimeException("third-sensitive-value"))
        root.initCause(error)
        val result = DiagnosticRedaction.exceptionTrace(error)
        listOf(
            "unsaved",
            "55.12345",
            "third-sensitive-value"
        ).forEach { assertFalse(result.contains(it)) }
        assertTrue(result.contains("IllegalStateException"))
        assertTrue(result.contains("Caused by: java.lang.IllegalArgumentException"))
        assertTrue(result.contains("Suppressed: java.lang.RuntimeException"))
        assertTrue(result.contains("DiagnosticRedactionTest"))
    }
}
