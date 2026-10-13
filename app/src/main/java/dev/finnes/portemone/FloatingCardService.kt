// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.res.stringResource

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.min
import kotlin.math.roundToInt

/** A manual checkout experiment. Geofences never start this service or request overlay access. */
class FloatingCardService : Service() {
    private lateinit var windows: WindowManager
    private lateinit var ui: Context
    private var panel: View? = null
    private var card = SampleCard.card
    private val timer = Handler(Looper.getMainLooper())
    private val expire = Runnable { stopSelf() }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val display =
            getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val windowContext =
            createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        windows = windowContext.getSystemService(WindowManager::class.java)
        ui = ContextThemeWrapper(windowContext, android.R.style.Theme_Material_Light_NoActionBar)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(
            screenOff,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            RECEIVER_NOT_EXPORTED
        )
        else registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppDiagnostics.event("Overlay", "Floating card command: ${intent?.action}")
        if (intent?.action != SHOW || !Settings.canDrawOverlays(this) ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = intent.getStringExtra(CardActivity.TYPE)
            ?.let { runCatching { BarcodeType.valueOf(it) }.getOrNull() } ?: BarcodeType.CODE_128
        card = SampleCard.card.copy(barcodeType = type)
        try {
            startVisibleService()
            showPanel()
            timer.removeCallbacks(expire)
            timer.postDelayed(expire, FIVE_MINUTES)
        } catch (error: SecurityException) {
            AppDiagnostics.event("Overlay", "Permission denied", error)
            Toast.makeText(
                this,
                getString(R.string.allow_display_over_other_apps_to_show_the_floating),
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
        } catch (error: WindowManager.BadTokenException) {
            AppDiagnostics.event("Overlay", "Window rejected", error)
            Toast.makeText(
                this,
                getString(R.string.android_could_not_display_the_floating_card_reopen_portemon),
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }
        // Do not recreate an unexpected overlay after process death or reboot.
        return START_NOT_STICKY
    }

    private fun startVisibleService() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, getString(R.string.floating_card_controls),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.close_a_manually_opened_floating_card)
                    setSound(null, null)
                    enableVibration(false)
                })
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, FloatingCardService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, CardActivity::class.java).putExtra(
                CardActivity.TYPE,
                card.barcodeType.name
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_card)
            .setContentTitle(getString(R.string.floating_card, card.brandName))
            .setContentText(getString(R.string.closes_when_the_screen_turns_off_or_after_5))
            .setContentIntent(open)
            .addAction(0, getString(R.string.close_floating_card), stop)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(
            ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        else startForeground(ID, notification)
    }

    private fun showPanel() {
        removePanel()
        val metrics = windows.currentWindowMetrics
        val insets =
            metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val availableWidth = metrics.bounds.width() - insets.left - insets.right
        val availableHeight = metrics.bounds.height() - insets.top - insets.bottom
        val width = min(availableWidth - dp(24), dp(560)).coerceAtLeast(1)
        val codeHeight = min(
            dp(if (card.barcodeType.square) 240 else 144),
            (availableHeight - dp(180)).coerceAtLeast(dp(48))
        )
        val layout = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }
        val root = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                setColor(card.brandColor); cornerRadius = dp(20).toFloat()
            }
            clipToOutline = true
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        val header = LinearLayout(ui).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(ui).apply {
            text = getString(R.string.floating_card_title, card.brandName, card.cardName)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(card.accentColor)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            contentDescription =
                getString(R.string.drag_vertically_to_move_the_card, card.brandName, card.cardName)
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(Button(ui).apply {
            text = "×"
            textSize = 24f
            setTextColor(card.accentColor)
            backgroundTintList = android.content.res.ColorStateList.valueOf(card.brandColor)
            contentDescription = getString(R.string.close_floating_card)
            setOnClickListener { stopSelf() }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        root.addView(header)
        root.addView(
            FloatingBarcodeView(ui, card),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, codeHeight)
        )
        root.addView(Button(ui).apply {
            text = getString(R.string.open_full_screen_card)
            isAllCaps = false
            setTextColor(card.brandColor)
            backgroundTintList = android.content.res.ColorStateList.valueOf(card.accentColor)
            setOnClickListener {
                startActivity(
                    Intent(this@FloatingCardService, CardActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(CardActivity.TYPE, card.barcodeType.name)
                )
                stopSelf()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        var downY = 0f
        var initialY = 0
        title.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY; initialY = layout.y; true
                }

                MotionEvent.ACTION_MOVE -> {
                    layout.y = (initialY + event.rawY - downY).roundToInt()
                        .coerceIn(0, (availableHeight - root.height).coerceAtLeast(0))
                    if (root.isAttachedToWindow) windows.updateViewLayout(root, layout)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    view.performClick(); true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        windows.addView(root, layout)
        panel = root
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (panel != null) {
            // Rotation/density changes require fresh barcode pixels at the new display size.
            if (Settings.canDrawOverlays(this)) runCatching { showPanel() }.onFailure { stopSelf() }
            else stopSelf()
        }
    }

    private fun removePanel() {
        panel?.let { if (it.isAttachedToWindow) windows.removeViewImmediate(it) }
        panel = null
    }

    override fun onDestroy() {
        AppDiagnostics.event("Overlay", "Floating card stopped")
        timer.removeCallbacks(expire)
        removePanel()
        unregisterReceiver(screenOff)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * ui.resources.displayMetrics.density).roundToInt()

    companion object {
        const val SHOW = "dev.finnes.portemone.SHOW_FLOATING_CARD"
        private const val STOP = "dev.finnes.portemone.STOP_FLOATING_CARD"
        private const val CHANNEL = "floating_card_controls"
        private const val ID = 1002
        private const val FIVE_MINUTES = 5 * 60 * 1000L
    }
}
