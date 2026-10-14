// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.diagnostics

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArraySet

internal object DiagnosticRedaction {
    // Retain replaced/deleted values for this process so queued events stay protected.
    private val values = CopyOnWriteArraySet<String>()
    private var protectedCharacters = 0

    @Volatile
    private var exhausted = false
    private val coordinates =
        Regex("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z)|(?<![\\w.])[+-]?(?:\\d+\\.\\d{4,}|\\.\\d{4,})(?:[eE][+-]?\\d+)?(?!\\d)")
    private val longNumbers = Regex("\\b\\d{12,}\\b")

    @Synchronized
    fun protect(vararg secrets: String?) {
        secrets.filterNotNull().filter { it.isNotBlank() }.forEach { value ->
            if (value !in values && !exhausted) {
                if (values.size >= 4096 || protectedCharacters + value.length > 2 * 1024 * 1024) {
                    // Withhold diagnostics instead of forgetting previously protected values.
                    exhausted = true
                    values.clear()
                } else {
                    values.add(value)
                    protectedCharacters += value.length
                }
            }
        }
    }

    @Synchronized
    fun redact(text: String): String {
        if (exhausted) return "[diagnostics withheld: membership redaction capacity reached]"
        var safe = text
        values.sortedByDescending { it.length }
            .forEach { safe = safe.replace(it, "[membership value]") }
        return coordinates.replace(safe) { it.groups[1]?.value ?: "[coordinate]" }
            .replace(longNumbers, "[long number]")
    }

    fun exceptionTrace(error: Throwable): String = buildString {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        fun appendError(failure: Throwable, prefix: String, depth: Int) {
            if (depth >= 16 || !seen.add(failure)) return
            // Throwable.toString()/printStackTrace() include arbitrary rejected input.
            appendLine("$prefix${failure.javaClass.name} [message omitted]")
            failure.stackTrace.take(64).forEach { appendLine("\tat $it") }
            failure.suppressed.take(8).forEach { appendError(it, "Suppressed: ", depth + 1) }
            failure.cause?.let { appendError(it, "Caused by: ", depth + 1) }
        }
        appendError(error, "", 0)
    }
}
