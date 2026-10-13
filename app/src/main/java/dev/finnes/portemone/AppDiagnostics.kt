// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.notifications.integrations.SamsungNowBar
import dev.finnes.portemone.notifications.integrations.PixelNotificationCompat

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.app.usage.UsageStatsManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local, bounded diagnostics. File IO is serialized off the UI and geofence receiver threads. */
object AppDiagnostics {
    private const val FILE_LIMIT = 128 * 1024
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(
            task,
            "portemone-diagnostics"
        ).apply { isDaemon = true }
    }

    @Volatile
    private var directory: File? = null
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    fun initialize(context: Context) {
        protect(SampleCard.card)
        protect(TestCard.card)
        writer.execute {
            runCatching {
                // Older files included untrusted exception messages and raw Logcat captures.
                File(context.noBackupFilesDir, "diagnostics").deleteRecursively()
                File(context.cacheDir, "debug-exports").listFiles()
                    ?.filter { !it.name.startsWith("portemone-safe-debug-") }
                    ?.forEach { it.delete() }
                LocalCardRepository.get(context).cards().forEach(::protect)
                directory = File(context.noBackupFilesDir, "diagnostics-v2")
            }.onFailure { directory = null }
        }
        event(
            "App",
            "Process started; Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    fun event(category: String, message: String, error: Throwable? = null) {
        val entry =
            "${Instant.now()} ${if (error == null) "INFO" else "ERROR"} [$category] $message" +
                    (error?.let { "\n${DiagnosticRedaction.exceptionTrace(it)}" } ?: "")
        writer.execute {
            try {
                val dir = directory ?: return@execute
                val safe = redact(entry).take(8_000) + "\n"
                Log.println(if (error == null) Log.INFO else Log.ERROR, "PortemoneDebug", safe)
                dir.mkdirs()
                val current = File(dir, "events.log")
                if (current.length() + safe.toByteArray(Charsets.UTF_8).size > FILE_LIMIT) {
                    val previous = File(dir, "previous.log")
                    previous.delete()
                    check(current.renameTo(previous)) { "Could not rotate diagnostics" }
                }
                current.appendText(safe)
                changes.value++
            } catch (failure: Exception) {
                // Diagnostics must never prevent a notification or geofence from working.
                Log.w("PortemoneDebug", "Could not persist diagnostics: ${failure.javaClass.name}")
            }
        }
    }

    internal fun protect(card: Card) {
        DiagnosticRedaction.protect(card.rawValue, card.displayText)
    }

    internal fun redact(text: String): String = DiagnosticRedaction.redact(text)

    fun flush() {
        writer.submit {}.get(2, TimeUnit.SECONDS)
    }

    fun readEvents(): String = writer.submit<String> {
        val dir = directory ?: return@submit ""
        listOf("previous.log", "events.log").joinToString("") { name ->
            redact(File(dir, name).takeIf { it.exists() }?.readText().orEmpty())
        }
    }.get(3, TimeUnit.SECONDS)

    fun clear() {
        writer.submit {
            directory?.let { dir ->
                listOf("previous.log", "events.log").forEach {
                    File(
                        dir,
                        it
                    ).delete()
                }
            }
            changes.value++
        }.get(3, TimeUnit.SECONDS)
    }

    @Suppress("DEPRECATION")
    fun deviceState(context: Context): String = buildString {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        appendLine("Portemoné diagnostic report • ${Instant.now()}")
        appendLine(
            "App: ${info.versionName} (${info.longVersionCode}); updated ${
                Instant.ofEpochMilli(
                    info.lastUpdateTime
                )
            }"
        )
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}; firmware: ${Build.DISPLAY}")
        appendLine(
            "Samsung platform: ${
                runCatching {
                    Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null)
                }.getOrNull() ?: "n/a"
            }"
        )
        appendLine("Screen: ${context.resources.configuration.screenWidthDp} × ${context.resources.configuration.screenHeightDp} dp; density ${context.resources.displayMetrics.density}; font scale ${context.resources.configuration.fontScale}")
        val manager = context.getSystemService(NotificationManager::class.java)
        appendLine("Notifications enabled: ${manager.areNotificationsEnabled()}; DND filter: ${manager.currentInterruptionFilter}")
        val permissions = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) +
                if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        for (permission in permissions) {
            appendLine(
                "${permission.substringAfterLast('.')}: ${
                    ContextCompat.checkSelfPermission(
                        context,
                        permission
                    ) == PackageManager.PERMISSION_GRANTED
                }"
            )
        }
        appendLine("Device location enabled: ${context.getSystemService(LocationManager::class.java).isLocationEnabled}")
        val power = context.getSystemService(PowerManager::class.java)
        appendLine(
            "Power saving: ${power.isPowerSaveMode}; idle: ${power.isDeviceIdleMode}; battery optimization exempt: ${
                power.isIgnoringBatteryOptimizations(
                    context.packageName
                )
            }"
        )
        appendLine(
            "Background restricted: ${context.getSystemService(ActivityManager::class.java).isBackgroundRestricted}; standby bucket: ${
                context.getSystemService(
                    UsageStatsManager::class.java
                ).appStandbyBucket
            }"
        )
        appendLine("Overlay access: ${Settings.canDrawOverlays(context)}")
        val settings = LocationSettings(context)
        appendLine("Style: ${settings.notificationStyle}; demo type: ${settings.barcodeType}")
        appendLine(
            "Card presentation: ${settings.notificationPresentation}; ${
                PixelNotificationCompat(
                    context
                ).status()
            }"
        )
        appendLine(
            "Location engine: requested=${settings.locationEngine}; selected=${
                GeofenceCoordinator(
                    context
                ).selectedEngine()
            }; registered=${settings.registeredEngine}"
        )
        appendLine("Geofencing enabled: ${settings.enabled}; registered: ${settings.registered.sorted()}; inside: ${settings.inside.sorted()}")
        appendLine("Location status text omitted; it can contain provider or card data.")
        appendLine(
            "Last successful nearby update: ${
                settings.lastNearbyUpdateAtMillis.takeIf { it > 0L }
                    ?.let(Instant::ofEpochMilli) ?: "never"
            }; source=${settings.lastNearbyUpdateSource.ifBlank { "none" }}"
        )
        appendLine(
            "Saved region: ${settings.anchor() != null}; store count: ${settings.stores().size}; registration current: ${
                GeofenceCoordinator(
                    context
                ).hasCurrentRegistration()
            }"
        )
        for (store in settings.stores()) {
            appendLine("Store ${store.id}: radius=${store.radiusMeters} m; registered=${store.id in settings.registered}; inside=${store.id in settings.inside}")
        }
        for (channel in manager.notificationChannels) {
            appendLine("Channel ${channel.id}: importance=${channel.importance}, visibility=${channel.lockscreenVisibility}, sound=${channel.sound != null}, vibration=${channel.shouldVibrate()}")
        }
        val health = NotificationHealth(context)
        for (check in health.basic() + if (SamsungNowBar.isSamsung) health.samsung() else emptyList()) {
            appendLine("Notification health ${check.id}: ready=${check.ready}; unknown=${check.unknown}; ${check.detail}")
        }
        for (posted in manager.activeNotifications) {
            val extras = posted.notification.extras
            appendLine(
                "Active notification ${posted.packageName}/${posted.id}/${posted.tag ?: "demo"}: template=${
                    extras.getString(
                        "android.template"
                    )
                }, flags=${posted.notification.flags}, visibility=${posted.notification.visibility}"
            )
            for (key in listOf(
                "expandedRemoteView",
                "subScreenExpandedRemoteView",
                "nowbarRemoteView",
                "chronometerRemoteView"
            )) {
                appendLine("  Samsung $key: ${extras.containsKey("android.ongoingActivityNoti.$key")}")
            }
        }
        val activityManager = context.getSystemService(ActivityManager::class.java)
        for (exit in activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 5)) {
            appendLine("Previous process exit ${Instant.ofEpochMilli(exit.timestamp)}: reason=${exit.reason}, status=${exit.status}, importance=${exit.importance}")
        }
        appendLine("Membership values, precise coordinates, exception messages and raw Logcat are omitted. Logs retain approximately the latest 256 KiB.")
    }.let(::redact)

    fun report(context: Context): String =
        deviceState(context) + "\nApp events (oldest first)\n" + readEvents()

    fun createArchive(context: Context): File {
        val dir = File(context.cacheDir, "debug-exports").apply { mkdirs() }
        // Leave recent shares readable; retire old exports only when making a new one.
        dir.listFiles()
            ?.filter { System.currentTimeMillis() - it.lastModified() > TimeUnit.DAYS.toMillis(7) }
            ?.forEach { it.delete() }
        val zip = File.createTempFile("portemone-safe-debug-", ".zip", dir)
        try {
            val files = linkedMapOf(
                "device-state.txt" to deviceState(context),
                "app-events.txt" to readEvents()
            )
            ZipOutputStream(zip.outputStream().buffered()).use { stream ->
                files.forEach { (name, text) ->
                    stream.putNextEntry(ZipEntry(name))
                    stream.write(text.toByteArray(Charsets.UTF_8))
                    stream.closeEntry()
                }
            }
            return zip
        } catch (error: Exception) {
            zip.delete(); throw error
        }
    }

    fun shareIntent(context: Context, archive: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", archive)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Portemoné debug report")
            clipData = ClipData.newRawUri("Portemoné debug report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
