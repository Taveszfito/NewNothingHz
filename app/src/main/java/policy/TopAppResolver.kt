package com.nothing120hzunlock.policy

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

object TopAppResolver {

    private const val TAG = "TopAppResolver"

    /** Ellenőrzi, hogy van-e Usage Access engedély (API 26–28-on fallback-kel) */
    fun hasUsageAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } else {
            // Fallback: próbáljunk usage stat-ot lekérni
            runCatching {
                val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    System.currentTimeMillis() - 60_000,
                    System.currentTimeMillis()
                )
                !stats.isNullOrEmpty()
            }.getOrDefault(false)
        }
    }

    /** Legutóbbi előtérbe kerülő app csomagneve (részletes eseménynaplóból) */
    fun currentTopApp(ctx: Context): String? {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val begin = now - 15_000 // 15 mp elég a legutóbbi fg eseményhez

            val events = usm.queryEvents(begin, now)
            val e = UsageEvents.Event()
            var lastPkg: String? = null
            var lastTs = -1L

            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val isFg = when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> true
                    else -> if (Build.VERSION.SDK_INT >= 29)
                        (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
                    else false
                }
                if (isFg && !e.packageName.isNullOrEmpty() && e.timeStamp >= lastTs) {
                    if (e.packageName != "com.android.systemui") { // ne kezeljük „tiltottként” az értesítési sávot/launchert
                        lastPkg = e.packageName
                        lastTs = e.timeStamp
                    }
                }
            }
            lastPkg
        } catch (t: Throwable) {
            Log.w(TAG, "currentTopApp failed", t)
            null
        }
    }
}
