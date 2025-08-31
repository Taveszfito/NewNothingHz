package com.nothing120hzunlock.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.nothing120hzunlock.FloatingService
import com.nothing120hzunlock.accessibility.AppWatcherService

class PolicyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Ha top app érkezett, mentsük el
        if (intent?.action == AppWatcherService.ACTION_TOP_APP) {
            val pkg = intent.getStringExtra(AppWatcherService.EXTRA_PKG)
            if (!pkg.isNullOrEmpty()) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST_TOP_PKG, pkg).apply()
            }
        }
        // Bármely érkezett eseményre értékeljünk
        evaluateAndApply(context)
    }

    private fun evaluateAndApply(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 0) User főkapcsoló
        val wantOverlay = prefs.getBoolean(KEY_USER_WANTS, false)
        if (!wantOverlay) {
            ctx.stopService(Intent(ctx, FloatingService::class.java))
            return
        }

        // 1) Battery Saver
        val pauseOnSaver = prefs.getBoolean(KEY_PAUSE_ON_SAVER, false)
        if (pauseOnSaver) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isPowerSaveMode) {
                ctx.stopService(Intent(ctx, FloatingService::class.java))
                return
            }
        }

        // 2) Akkuszázalék küszöb (0 = kikapcsolva). Töltőn nem limitálunk.
        val threshold = prefs.getInt(KEY_BATT_THRESHOLD, 0).coerceIn(0, 50)
        if (threshold > 0) {
            val batt = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct   = if (level >= 0) (level * 100f / scale).toInt() else 100
            val plugged = (batt?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

            if (!plugged && pct <= threshold) {
                ctx.stopService(Intent(ctx, FloatingService::class.java))
                return
            }
        }

        // 3) Blacklist – aktuális előtér app alapján
        val blackSet = prefs.getStringSet(KEY_BLACKLIST_SET, emptySet()) ?: emptySet()
        val lastPkg = prefs.getString(KEY_LAST_TOP_PKG, null)

        // Néhány rendszerfelületet defaultból ne futtasd
        val isSystemUi = lastPkg?.startsWith("com.android.systemui") == true
        if (isSystemUi) {
            ctx.stopService(Intent(ctx, FloatingService::class.java))
            return
        }

        if (!lastPkg.isNullOrEmpty() && lastPkg in blackSet) {
            ctx.stopService(Intent(ctx, FloatingService::class.java))
            return
        }

        // Ha minden oké, indulhat/mehet az overlay
        ctx.startService(Intent(ctx, FloatingService::class.java))
    }

    companion object {
        // Saját broadcast a manuális újraértékelésre (UI-ból)
        const val ACTION_EVAL_POLICY = "com.nothing120hzunlock.ACTION_EVAL_POLICY"

        // Pref kulcsok
        private const val PREFS = "prefs"
        private const val KEY_USER_WANTS = "user_wants_overlay"
        private const val KEY_PAUSE_ON_SAVER = "pause_on_saver"
        private const val KEY_BATT_THRESHOLD = "battery_threshold_percent"
        private const val KEY_BLACKLIST_SET = "blacklist_packages"
        private const val KEY_LAST_TOP_PKG  = "last_top_pkg"
    }
}

