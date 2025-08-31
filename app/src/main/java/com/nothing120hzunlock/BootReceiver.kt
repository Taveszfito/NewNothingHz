package com.nothing120hzunlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nothing120hzunlock.policy.PolicyReceiver

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            val autostart = prefs.getBoolean("autostart", false)
            val userWants = prefs.getBoolean("user_wants_overlay", false)
            val hasOverlay = Settings.canDrawOverlays(ctx)

            if (autostart && userWants && hasOverlay) {
                // kérjünk azonnali policy újraértékelést
                ctx.sendBroadcast(
                    Intent(PolicyReceiver.ACTION_EVAL_POLICY).setPackage(ctx.packageName)
                )
                // indítsuk el a szolgáltatást FOREGROUND módban (Android 8+ követelmény)
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, FloatingService::class.java).putExtra("start_fg", true)
                )
            }
        }
    }
}
