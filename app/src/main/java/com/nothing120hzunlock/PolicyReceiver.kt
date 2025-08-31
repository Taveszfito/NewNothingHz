package com.nothing120hzunlock.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.nothing120hzunlock.FloatingService

class PolicyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_EVAL_POLICY = "com.nothing120hzunlock.ACTION_EVAL_POLICY"

        private const val TAG = "Policy"

        private const val MIN_CHANGE_GAP_MS = 300L
        private const val BLACKLIST_HOLD_MS = 1000L
        private const val LAST_TOP_MAX_AGE_MS = 2_000L

        private var lastDecision: Int? = null
        private var lastChangeAt = 0L
        private var blacklistHoldUntil = 0L

        private const val DEC_OFF = 0
        private const val DEC_PAUSE = 1
        private const val DEC_ON = 2
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "com.nothing120hzunlock.TOP_APP_CHANGED") {
            intent.getStringExtra("pkg")?.let { pkg ->
                val now = System.currentTimeMillis()
                context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_top_pkg", pkg)
                    .putLong("last_top_pkg_ts", now)
                    .apply()
            }
        }
        evaluateAndApply(context)
    }

    private fun evaluateAndApply(ctx: Context) {
        val now = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val userWants = prefs.getBoolean("user_wants_overlay", false)
        var decision = if (userWants) DEC_ON else DEC_OFF

        // ---- Battery szabályok (csak ha user ON) ----
        if (decision != DEC_OFF) {
            if (prefs.getBoolean("pause_on_saver", false) && isPowerSaveOn(ctx)) {
                decision = DEC_PAUSE
            }
            if (decision != DEC_PAUSE) {
                val thr = prefs.getInt("battery_threshold_percent", 0)
                if (thr in 1..50) {
                    val (pct, charging) = batteryState(ctx)
                    if (!charging && pct in 0..100 && pct < thr) {
                        decision = DEC_PAUSE
                    }
                }
            }
        }

        // ---- Blacklist + rövid idejű cache ----
        if (decision != DEC_OFF && decision != DEC_PAUSE) {
            val bl = prefs.getStringSet("blacklist_packages", emptySet()) ?: emptySet()
            var top: String? = null

            if (TopAppResolver.hasUsageAccess(ctx)) {
                top = TopAppResolver.currentTopApp(ctx)
            }

            if (!top.isNullOrEmpty()) {
                prefs.edit()
                    .putString("last_top_pkg", top)
                    .putLong("last_top_pkg_ts", now)
                    .apply()
            } else {
                val lastTs = prefs.getLong("last_top_pkg_ts", 0L)
                if (now - lastTs <= LAST_TOP_MAX_AGE_MS) {
                    top = prefs.getString("last_top_pkg", null)
                }
            }

            if (!top.isNullOrEmpty() && top in bl) {
                decision = DEC_PAUSE
                blacklistHoldUntil = now + BLACKLIST_HOLD_MS
            } else if (now < blacklistHoldUntil) {
                decision = DEC_PAUSE
            }
        }

        // ---- Rate limit ----
        if (lastDecision != null && lastDecision == decision) return
        if (now - lastChangeAt < MIN_CHANGE_GAP_MS) return

        // ---- Apply ----
        when (decision) {
            DEC_OFF -> {
                ctx.stopService(Intent(ctx, FloatingService::class.java))
                Log.d(TAG, "Apply: SERVICE STOP (user off)")
            }
            DEC_PAUSE -> {
                if (!FloatingService.isRunning) {
                    runCatching {
                        ctx.startService(Intent(ctx, FloatingService::class.java))
                    }.onFailure { Log.w(TAG, "startService rejected (bg restriction)", it) }
                    Log.d(TAG, "Apply: SERVICE START (for pause)")
                }
                ctx.sendBroadcast(
                    Intent(FloatingService.ACTION_OVERLAY_PAUSE).setPackage(ctx.packageName)
                )
                Log.d(TAG, "Apply: OVERLAY PAUSE")
            }
            DEC_ON -> {
                if (!FloatingService.isRunning) {
                    runCatching {
                        ctx.startService(Intent(ctx, FloatingService::class.java))
                    }.onFailure { Log.w(TAG, "startService rejected (bg restriction)", it) }
                    Log.d(TAG, "Apply: SERVICE START (for resume)")
                }
                ctx.sendBroadcast(
                    Intent(FloatingService.ACTION_OVERLAY_RESUME).setPackage(ctx.packageName)
                )
                Log.d(TAG, "Apply: OVERLAY RESUME")
            }
        }

        lastDecision = decision
        lastChangeAt = now
    }

    private fun isPowerSaveOn(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val saver = runCatching { pm.isPowerSaveMode }.getOrDefault(false)
        val idle = runCatching { pm.isDeviceIdleMode }.getOrDefault(false)
        val lowPower = runCatching {
            Settings.Global.getInt(ctx.contentResolver, "low_power", 0) == 1
        }.getOrDefault(false)
        return saver || idle || lowPower
    }

    private fun batteryState(ctx: Context): Pair<Int, Boolean> = try {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else -1
        pct to charging
    } catch (_: Exception) { -1 to false }
}
