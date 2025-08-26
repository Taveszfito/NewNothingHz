package com.nothing120hzunlock

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*

class FloatingService : Service() {

    companion object { var isRunning = false }

    private lateinit var windowManager: WindowManager
    private lateinit var floatView: View
    private lateinit var params: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Indulás: 1×1 pixel, jobb felső sarok
        params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = 0
        }

        floatView = View(this).apply {
            // teljesen átlátszó
            setBackgroundColor(Color.TRANSPARENT)

            // nincs click listener → fixen 1×1 px marad
        }

        windowManager.addView(floatView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatView.isInitialized) windowManager.removeView(floatView)
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
