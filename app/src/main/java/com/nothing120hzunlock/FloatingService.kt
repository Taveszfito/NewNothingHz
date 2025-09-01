package com.nothing120hzunlock

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.util.Log
import com.nothing120hzunlock.policy.TopAppResolver

class FloatingService : Service() {

    companion object {
        @Volatile var isRunning: Boolean = false

        const val ACTION_OVERLAY_PAUSE  = "com.nothing120hzunlock.ACTION_OVERLAY_PAUSE"
        const val ACTION_OVERLAY_RESUME = "com.nothing120hzunlock.ACTION_OVERLAY_RESUME"
        const val ACTION_PAUSE_OVERLAY  = "com.nothing120hzunlock.ACTION_PAUSE_OVERLAY"   // legacy
        const val ACTION_RESUME_OVERLAY = "com.nothing120hzunlock.ACTION_RESUME_OVERLAY" // legacy

        private const val TAG = "FloatingService"

        private const val MIN_CHANGE_GAP_MS = 300L
        private const val BLACKLIST_HOLD_MS = 1000L
        private const val DEC_OFF = 0
        private const val DEC_PAUSE = 1
        private const val DEC_ON = 2

        private const val PREFS = "prefs"
        private const val KEY_USER_WANTS = "user_wants_overlay"
        private const val KEY_PAUSE_ON_SAVER = "pause_on_saver"
        private const val KEY_BATT_THRESHOLD = "battery_threshold_percent" // 0=OFF, 1..50 aktív
        private const val KEY_BLACKLIST_SET = "blacklist_packages"
        private const val KEY_PERM_WATCH = "perm_watch"

        private const val BATTERY_POLL_MS = 10_000L
        private const val EXTERNAL_PAUSE_HOLD_MS = 2000L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var overlayView: View? = null

    private var overlayAttached = false
    private var overlayPaused   = true   // induláskor tekintsük „szüneteltetettnek”

    private var lastDecision: Int? = null
    private var lastChangeAt = 0L
    private var blacklistHoldUntil = 0L
    private var externalPauseUntil = 0L

    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())

    private fun isPermWatchOn(): Boolean = prefs.getBoolean(KEY_PERM_WATCH, false)

    // Külső jelzések: csak állapotot állítunk (ha perm_watch ON), majd értékelünk
    private val overlayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_OVERLAY_PAUSE, ACTION_PAUSE_OVERLAY -> {
                    if (isPermWatchOn()) {
                        externalPauseUntil = System.currentTimeMillis() + EXTERNAL_PAUSE_HOLD_MS
                    }
                    evaluateAndApply()
                }
                ACTION_OVERLAY_RESUME, ACTION_RESUME_OVERLAY -> {
                    if (isPermWatchOn()) externalPauseUntil = 0L
                    evaluateAndApply()
                }
                com.nothing120hzunlock.policy.PolicyReceiver.ACTION_EVAL_POLICY -> evaluateAndApply()
            }
        }
    }

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { evaluateAndApply() }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { evaluateAndApply() }
    }

    private val pollHandler = Handler(Looper.getMainLooper())
    private var lastTopPkgPolled: String? = null
    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                if (TopAppResolver.hasUsageAccess(this@FloatingService)) {
                    val top = TopAppResolver.currentTopApp(this@FloatingService)
                    if (top != lastTopPkgPolled) {
                        lastTopPkgPolled = top
                        evaluateAndApply()
                    }
                }
            } finally {
                pollHandler.postDelayed(this, 800L)
            }
        }
    }

    private val batteryPollRunnable = object : Runnable {
        override fun run() {
            evaluateAndApply()
            main.postDelayed(this, BATTERY_POLL_MS)
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_USER_WANTS || key == KEY_PAUSE_ON_SAVER || key == KEY_BATT_THRESHOLD || key == KEY_BLACKLIST_SET || key == KEY_PERM_WATCH) {
            evaluateAndApply()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0; y = 0
            alpha = 0f
        }

        overlayView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
        }
        // FONTOS: induláskor NEM tesszük fel automatikusan!
        // addOverlayIfNeeded()

        // Overlay PAUSE/RESUME/EVAL üzenetek
        IntentFilter().apply {
            addAction(ACTION_OVERLAY_PAUSE)
            addAction(ACTION_OVERLAY_RESUME)
            addAction(ACTION_PAUSE_OVERLAY)
            addAction(ACTION_RESUME_OVERLAY)
            addAction(com.nothing120hzunlock.policy.PolicyReceiver.ACTION_EVAL_POLICY)
        }.also { registerReceiverCompat(overlayReceiver, it) }

        // Saver/Idle + töltő csatlakoztatás/leválasztás
        IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }.also { registerReceiverCompat(powerSaveReceiver, it) }

        // Akkumulátor (sticky + változások)
        IntentFilter().apply { addAction(Intent.ACTION_BATTERY_CHANGED) }
            .also { registerReceiverCompat(batteryReceiver, it) }

        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        pollHandler.post(pollRunnable)
        main.post(batteryPollRunnable)
        evaluateAndApply()
    }

    override fun onDestroy() {
        pollHandler.removeCallbacks(pollRunnable)
        main.removeCallbacks(batteryPollRunnable)
        runCatching { unregisterReceiver(powerSaveReceiver) }
        runCatching { unregisterReceiver(overlayReceiver) }
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }
        removeOverlayIfNeeded()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    // -------- Overlay kezelés --------

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

    private fun pauseOverlay() {
        if (overlayAttached) {
            removeOverlayIfNeeded()
        }
        overlayPaused = true
        Log.d(TAG, "OVERLAY PAUSE")
    }

    // Mindig próbáljuk visszatenni (idempotens)
    private fun resumeOverlay() {
        if (Settings.canDrawOverlays(this)) {
            addOverlayIfNeeded()
            overlayPaused = false
            Log.d(TAG, "OVERLAY RESUME")
        }
    }

    // -------- Prioritásos döntési logika --------
    private fun evaluateAndApply() {
        val now = System.currentTimeMillis()

        // 1) User ON – ha OFF, nincs layer
        val userOn = prefs.getBoolean(KEY_USER_WANTS, false)
        if (!userOn) { applyDecision(DEC_OFF, now); return }

        // 2) Battery Saver → csak hivatalos API
        val pauseOnSaver = prefs.getBoolean(KEY_PAUSE_ON_SAVER, false)
        if (pauseOnSaver && isPowerSaveOn()) { applyDecision(DEC_PAUSE, now); return }

        // 3) Akkuküszöb: 0 = kikapcsolva; 1..50 aktív (csak ha NEM tölt)
        val thrRaw = prefs.getInt(KEY_BATT_THRESHOLD, 0)
        if (thrRaw > 0) {
            val thr = thrRaw.coerceAtMost(50)
            val (pct, charging) = batteryState()
            if (!charging && pct in 0..100 && pct < thr) {
                applyDecision(DEC_PAUSE, now); return
            }
        }

        // 4) Blacklist hold
        val bl = prefs.getStringSet(KEY_BLACKLIST_SET, emptySet()) ?: emptySet()
        if (bl.isNotEmpty()) {
            var top: String? = null
            if (TopAppResolver.hasUsageAccess(this)) top = TopAppResolver.currentTopApp(this)
            if (!top.isNullOrEmpty() && top in bl) {
                blacklistHoldUntil = now + BLACKLIST_HOLD_MS
            }
            if (now < blacklistHoldUntil) { applyDecision(DEC_PAUSE, now); return }
        }

        // 4.5) Külső (Accessibility) pause hold – csak ha engedve van
        if (isPermWatchOn() && now < externalPauseUntil) { applyDecision(DEC_PAUSE, now); return }

        // 5) User ON → ON
        applyDecision(DEC_ON, now)
    }

    private fun applyDecision(decision: Int, now: Long) {
        if (lastDecision != null && lastDecision == decision) return
        if (now - lastChangeAt < MIN_CHANGE_GAP_MS) return

        when (decision) {
            DEC_OFF   -> pauseOverlay().also { Log.d(TAG, "Apply: OFF (user off)") }
            DEC_PAUSE -> pauseOverlay().also { Log.d(TAG, "Apply: PAUSE (policy)") }
            DEC_ON    -> resumeOverlay().also { Log.d(TAG, "Apply: ON (policy)") }
        }

        lastDecision = decision
        lastChangeAt = now
    }

    private fun isPowerSaveOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return runCatching { pm.isPowerSaveMode }.getOrDefault(false)
    }

    private fun batteryState(): Pair<Int, Boolean> = try {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val i = if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(null, filter)
        }
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else -1
        pct to charging
    } catch (_: Exception) { -1 to false }
}
