package com.nothing120hzunlock

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*

class FloatingService : Service() {

    companion object {
        var isRunning: Boolean = false
        const val ACTION_PAUSE = "com.nothing120hzunlock.ACTION_PAUSE_OVERLAY"
        const val ACTION_RESUME = "com.nothing120hzunlock.ACTION_RESUME_OVERLAY"
        @JvmStatic var isPaused: Boolean = false
    }

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE -> pauseOverlay()
                ACTION_RESUME -> resumeOverlay()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 1×1 px overlay, jobb felső sarok
        params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0; y = 0
        }

        addOverlayIfNeeded()

        // ⬇️ Javított regisztrálás Android 13+ számára
        val filter = IntentFilter().apply {
            addAction(ACTION_PAUSE)
            addAction(ACTION_RESUME)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(controlReceiver, filter)
        }
        // ⬆️
    }

    private fun addOverlayIfNeeded() {
        if (floatView == null && !isPaused) {
            floatView = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
            windowManager.addView(floatView, params)
        }
    }

    private fun removeOverlayIfPresent() {
        floatView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            floatView = null
        }
    }

    private fun pauseOverlay() {
        if (!isPaused) {
            isPaused = true
            removeOverlayIfPresent()
        }
    }

    private fun resumeOverlay() {
        if (isPaused) {
            isPaused = false
            addOverlayIfNeeded()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(controlReceiver) } catch (_: Exception) {}
        removeOverlayIfPresent()
        isPaused = false
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
