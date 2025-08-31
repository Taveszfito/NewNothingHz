package com.nothing120hzunlock

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat

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
            // fontos: ne legyen fókuszálható és ne fogjon touchot
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0; y = 0
        }

        overlayView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT) // 1×1 átlátszó
            isClickable = false
            isFocusable = false
        }

        addOverlayIfNeeded()

        val filter = IntentFilter().apply {
            addAction(ACTION_OVERLAY_PAUSE)
            addAction(ACTION_OVERLAY_RESUME)
            addAction(ACTION_PAUSE_OVERLAY)
            addAction(ACTION_RESUME_OVERLAY)
        }
        ContextCompat.registerReceiver(
            this, overlayReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(overlayReceiver) }
        removeOverlayIfNeeded()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            // fontos: immediate
            runCatching { windowManager.removeViewImmediate(v) }
            overlayAttached = false
        }
    }

    /** Accessibility jelzésre azonnal eltüntetjük az overlayt. */
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
