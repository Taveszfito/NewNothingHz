package com.nothing120hzunlock

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.nothing120hzunlock.policy.TopAppResolver
import com.nothing120hzunlock.policy.PolicyReceiver

class FloatingService : Service() {

    companion object {
        @Volatile var isRunning: Boolean = false

        const val ACTION_OVERLAY_PAUSE  = "com.nothing120hzunlock.ACTION_OVERLAY_PAUSE"
        const val ACTION_OVERLAY_RESUME = "com.nothing120hzunlock.ACTION_OVERLAY_RESUME"

        // régi aliasok, ha valahol ezek maradtak
        const val ACTION_PAUSE_OVERLAY  = "com.nothing120hzunlock.ACTION_PAUSE_OVERLAY"
        const val ACTION_RESUME_OVERLAY = "com.nothing120hzunlock.ACTION_RESUME_OVERLAY"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var overlayView: View? = null

    private var overlayAttached = false
    private var overlayPaused   = false

    private val overlayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_OVERLAY_PAUSE, ACTION_PAUSE_OVERLAY   -> pauseOverlay()
                ACTION_OVERLAY_RESUME, ACTION_RESUME_OVERLAY -> resumeOverlay()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Nem használunk FGS-t, itt nincs teendő.
        return START_STICKY
    }

    // ---- Battery Saver / Doze váltás figyelése (azonnali policy-eval) ----
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            sendBroadcast(
                Intent(PolicyReceiver.ACTION_EVAL_POLICY).setPackage(packageName)
            )
        }
    }

    // ---- Usage Access polling: top app változás figyelése ----
    private val pollHandler = Handler(Looper.getMainLooper())
    private var lastTopPkgPolled: String? = null
    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                if (TopAppResolver.hasUsageAccess(this@FloatingService)) {
                    val top = TopAppResolver.currentTopApp(this@FloatingService)
                    if (top != lastTopPkgPolled) {
                        lastTopPkgPolled = top
                        sendBroadcast(
                            Intent(PolicyReceiver.ACTION_EVAL_POLICY).setPackage(packageName)
                        )
                    }
                }
            } finally {
                // 0.8 s – állítható 500–1000 ms közé
                pollHandler.postDelayed(this, 800L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        if (!Settings.canDrawOverlays(this)) {
            stopSelf(); return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            /* width  */ 1,
            /* height */ 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0; y = 0
            // Ne legyen WM warning a touch-through miatt
            alpha = 0f
        }

        overlayView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT) // 1×1 átlátszó
            isClickable = false
            isFocusable = false
        }

        addOverlayIfNeeded()

        // Overlay pause/resume üzenetek
        val filter = IntentFilter().apply {
            addAction(ACTION_OVERLAY_PAUSE)
            addAction(ACTION_OVERLAY_RESUME)
            addAction(ACTION_PAUSE_OVERLAY)
            addAction(ACTION_RESUME_OVERLAY)
        }
        registerReceiverCompat(overlayReceiver, filter)

        // Battery Saver / Doze váltások figyelése
        val psFilter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        registerReceiverCompat(powerSaveReceiver, psFilter)

        // 1) Usage Access poll loop indítása
        pollHandler.post(pollRunnable)

        // 2) Azonnali policy újraértékelés
        sendBroadcast(
            Intent(PolicyReceiver.ACTION_EVAL_POLICY).setPackage(packageName)
        )
    }

    override fun onDestroy() {
        pollHandler.removeCallbacks(pollRunnable)
        runCatching { unregisterReceiver(powerSaveReceiver) }
        runCatching { unregisterReceiver(overlayReceiver) }
        removeOverlayIfNeeded()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Compat helper a registerReceiver-hez (Android 13+ flag szükséges) ---
    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    // --- Overlay kezelés ---

    private fun addOverlayIfNeeded() {
        val v = overlayView ?: return
        if (!overlayAttached) {
            runCatching { windowManager.addView(v, params) }
            overlayAttached = true
            overlayPaused = false
        }
    }

    private fun removeOverlayIfNeeded() {
        val v = overlayView
        if (overlayAttached && v != null) {
            runCatching { windowManager.removeViewImmediate(v) }
            overlayAttached = false
        }
    }

    /** Jelzésre eltüntetjük az overlayt. */
    private fun pauseOverlay() {
        if (!overlayPaused) {
            removeOverlayIfNeeded()
            overlayPaused = true
        }
    }

    /** Csak akkor hozzuk vissza, ha még van overlay engedély. */
    private fun resumeOverlay() {
        if (overlayPaused && Settings.canDrawOverlays(this)) {
            addOverlayIfNeeded()
            overlayPaused = false
        }
    }
}
