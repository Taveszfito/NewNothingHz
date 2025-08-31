package com.nothing120hzunlock.policy

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

object TopAppResolver {

    private const val TAG = "TopAppResolver"

    /** Ellenőrzi, hogy van-e Usage Access engedély */
    fun hasUsageAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ → van unsafeCheckOpNoThrow
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } else {
            // Régi Androidon fallback: csak próbálunk usage stats-ot kérni
            try {
                val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    System.currentTimeMillis() - 1000 * 60,
                    System.currentTimeMillis()
                )
                !stats.isNullOrEmpty()
            } catch (e: Exception) {
                false
            }
        }
    }

    /** Lekéri a jelenlegi előtérben lévő app package nevét */
    fun currentTopApp(ctx: Context): String? {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val begin = end - 1000 * 30 // 30s history

            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
            if (stats.isNullOrEmpty()) return null

            val recent = stats.maxByOrNull { it.lastTimeUsed }
            recent?.packageName
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get top app", e)
            null
        }
    }
}
