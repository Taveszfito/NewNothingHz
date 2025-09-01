package com.nothing120hzunlock.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nothing120hzunlock.FloatingService

class PolicyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_EVAL_POLICY = "com.nothing120hzunlock.ACTION_EVAL_POLICY"
        private const val ACTION_TOP_APP_CHANGED = "com.nothing120hzunlock.TOP_APP_CHANGED"
        private const val TAG = "Policy"

        private const val PREFS = "prefs"
        private const val KEY_USER_WANTS = "user_wants_overlay"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()

        // Cache top app (blacklisthez)
        if (action == ACTION_TOP_APP_CHANGED) {
            intent?.getStringExtra("pkg")?.let { pkg ->
                val now = System.currentTimeMillis()
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_top_pkg", pkg)
                    .putLong("last_top_pkg_ts", now)
                    .apply()
                Log.d(TAG, "Top app cached: $pkg")
            }
        }

        // Csak akkor ébresszük a service-t, ha a user is akarja
        val userOn = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USER_WANTS, false)

        if (userOn && !FloatingService.isRunning) {
            runCatching {
                context.startService(Intent(context, FloatingService::class.java))
            }.onFailure { Log.w(TAG, "startService rejected (bg restriction)", it) }
        }

        // Központi újraértékelés kérése (ha fut a service, megkapja)
        context.sendBroadcast(
            Intent(ACTION_EVAL_POLICY)
                .setPackage(context.packageName)
                .putExtra("from_pr", true)
        )
        Log.d(TAG, "Forwarded EVAL (action=$action, userOn=$userOn)")
    }
}
