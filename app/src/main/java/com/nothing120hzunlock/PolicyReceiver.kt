package com.nothing120hzunlock.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.nothing120hzunlock.FloatingService

class PolicyReceiver : BroadcastReceiver() {

    companion object {
        /** Saját manuális újraértékelés ACTION-je (UI/Accessibility ide küld). */
        const val ACTION_EVAL_POLICY = "com.nothing120hzunlock.ACTION_EVAL_POLICY"

        private const val TAG = "Policy"

        /** Ne váltogassunk túl sűrűn – ennyi időnek el kell telnie két döntés között. */
        private const val MIN_CHANGE_GAP_MS = 350L

        /** Ha blacklist miatt állítottuk le, tartsuk OFF-on legalább eddig. */
        private const val BLACKLIST_HOLD_MS = 1200L

        private var lastDecision: Boolean? = null
        private var lastChangeAt = 0L
        private var blacklistHoldUntil = 0L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        // Ha az Accessibility jelenti a top appot, eltesszük fallbacknek
        if (intent?.action == "com.nothing120hzunlock.TOP_APP_CHANGED") {
            intent.getStringExtra("pkg")?.let { pkg ->
                context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                    .edit().putString("last_top_pkg", pkg).apply()
            }
        }
        evaluateAndApply(context)
    }

    private fun evaluateAndApply(ctx: Context) {
        val now = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val userWants = prefs.getBoolean("user_wants_overlay", false)
        var desired = userWants

        // ---- Battery szabályok ----
        if (desired) {
            // Battery Saver
            if (prefs.getBoolean("pause_on_saver", false) && isPowerSaveOn(ctx)) {
                desired = false
            }
            // Battery threshold
            val thr = prefs.getInt("battery_threshold_percent", 0)
            if (thr in 1..50) {
                val (pct, charging) = batteryState(ctx)
                if (!charging && pct in 0..100 && pct < thr) desired = false
            }
        }

        // ---- Blacklist: Usage Access az elsődleges, Accessibility cache fallback ----
        if (desired) {
            val bl = prefs.getStringSet("blacklist_packages", emptySet()) ?: emptySet()
            var top: String? = null

            if (TopAppResolver.hasUsageAccess(ctx)) {
                top = TopAppResolver.currentTopApp(ctx)
            }

            if (top.isNullOrEmpty()) {
                // fallback az utoljára bejelentett csomagra
                top = prefs.getString("last_top_pkg", null)
            } else {
                // cache-eljük is, hogy legyen mindig fallbackünk
                prefs.edit().putString("last_top_pkg", top).apply()
            }

            if (!top.isNullOrEmpty() && top in bl) {
                desired = false
                blacklistHoldUntil = now + BLACKLIST_HOLD_MS
            } else if (now < blacklistHoldUntil) {
                desired = false
            }
        }

        // ---- Hiszterézis / rate-limit ----
        if (lastDecision != null && lastDecision == desired) return
        if (now - lastChangeAt < MIN_CHANGE_GAP_MS) return

        lastDecision = desired
        lastChangeAt = now

        if (desired) {
            ctx.startService(Intent(ctx, FloatingService::class.java))
            Log.d(TAG, "Apply: START overlay")
        } else {
            ctx.stopService(Intent(ctx, FloatingService::class.java))
            Log.d(TAG, "Apply: STOP overlay")
        }
    }

    private fun isPowerSaveOn(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    } catch (_: Exception) { false }

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
