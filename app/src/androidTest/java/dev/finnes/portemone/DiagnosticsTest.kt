// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class DiagnosticsTest {
    @get:Rule
    val compose = createAndroidComposeRule<DebugActivity>()

    @Test
    fun copyAllIncludesFilteredOutEventsAndDeviceState() {
        val marker = "copy-report-${UUID.randomUUID().toString().take(8)}"
        AppDiagnostics.event("Test", marker)
        AppDiagnostics.flush()
        compose.onNodeWithText("Filter events").performTextInput("no matching entry")
        compose.onNodeWithText("Copy all").performClick()
        var copied = ""
        compose.waitUntil(5_000) {
            compose.runOnIdle {
                copied = compose.activity.getSystemService(ClipboardManager::class.java)
                    .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            }
            copied.contains(marker)
        }
        assertTrue(copied.contains("Geofencing enabled:"))
        assertTrue(copied.contains("Android:"))
    }

    @Test
    fun zipCanBeReadThroughShareUriAndOmitsCodesAndCoordinates() {
        val context = compose.activity
        val code = "Member-QR-secret/07"
        val display = "00742"
        val repository = LocalCardRepository.get(context)
        val card = TestMemberships.cards.first().copy(
            id = UUID.randomUUID().toString(), rawValue = code, displayText = display,
            stores = emptyList(), source = null, barcodeType = BarcodeType.QR_CODE
        )
        repository.saveCard(card)
        val marker = "archive-event-${UUID.randomUUID().toString().take(8)}"
        try {
            AppDiagnostics.event(
                "Test",
                "$marker code=$code display=$display lat=55.000000",
                IllegalStateException("Test error unsaved-private-value")
            )
            AppDiagnostics.flush()
            val persisted = File(context.noBackupFilesDir, "diagnostics-v2/events.log").readText()
            assertTrue(persisted.contains(marker))
            listOf(code, display, "55.000000", "unsaved-private-value").forEach {
                assertFalse(persisted.contains(it))
            }
        } finally {
            repository.deleteCard(card.id)
        }
        android.util.Log.e("ThirdParty", "raw-logcat-private-value")
        val archive = AppDiagnostics.createArchive(context)
        try {
            val intent = AppDiagnostics.shareIntent(context, archive)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("application/zip", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = androidx.core.content.IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java
            )!!
            assertEquals("content", uri.scheme)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            val files = mutableMapOf<String, String>()
            ZipInputStream(context.contentResolver.openInputStream(uri)!!).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    files[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            assertEquals(setOf("device-state.txt", "app-events.txt"), files.keys)
            assertTrue(files.getValue("app-events.txt").contains(marker))
            Instant.parse(
                files.getValue("app-events.txt").lineSequence().first { it.contains(marker) }
                    .substringBefore(' ')
            )
            assertTrue(
                files.getValue("app-events.txt").contains("IllegalStateException [message omitted]")
            )
            assertFalse(files.values.any { it.contains("Test error") })
            files.values.forEach { text ->
                assertFalse(text.contains(code))
                assertFalse(text.contains("55.000000"))
                assertFalse(text.contains(display))
                assertFalse(text.contains("unsaved-private-value"))
                assertFalse(text.contains("raw-logcat-private-value"))
            }
        } finally {
            archive.delete()
        }
        // Sharing diagnostics must not expose other private app files.
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.diagnostics",
                File(context.filesDir, "private.txt")
            )
        }
    }

    @Test
    fun historyStaysBoundedAndRetainsRecentEvents() {
        val marker = "retention-${UUID.randomUUID().toString().take(8)}"
        repeat(45) { AppDiagnostics.event("Test", "$marker event=$it " + "x".repeat(7_900)) }
        val history = AppDiagnostics.readEvents()
        assertTrue(history.toByteArray().size <= 256 * 1024)
        assertTrue(history.contains("$marker event=44 "))
        assertFalse(history.contains("$marker event=0 "))
        AppDiagnostics.clear()
        AppDiagnostics.event("Test", "Retention test passed; subsequent events remain available")
        assertTrue(AppDiagnostics.readEvents().contains("Retention test passed"))
    }
}
