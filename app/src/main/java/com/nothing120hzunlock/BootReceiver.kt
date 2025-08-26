package com.nothing120hzunlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Csak akkor induljon, ha a user engedélyezte
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val autostart = prefs.getBoolean("autostart", false)
        if (!autostart) return

        // Overlay engedély kell hozzá
        if (!Settings.canDrawOverlays(context)) return

        // Szolgáltatás indítása
        context.startService(Intent(context, FloatingService::class.java))
    }
}
