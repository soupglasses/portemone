// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.TextView

object PixelLabPreview { var notification: Notification? = null }

/** Visible, timed emulator control for genuine foreground-service colorization. */
class PixelLabActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Pixel card preview · ends automatically after one minute" })
    }

    override fun onResume() {
        super.onResume()
        if (PixelLabPreview.notification == null) return
        startForegroundService(Intent(this, PixelLabService::class.java))
    }
}

class PixelLabService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = PixelLabPreview.notification
        if (notification == null) { stopSelf(); return START_NOT_STICKY }
        startForeground(4311, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        handler.postDelayed({ stopSelf() }, 60_000)
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
