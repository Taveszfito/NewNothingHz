package com.nothing120hzunlock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class PermissionWatcherService : AccessibilityService() {

    // Gyakori permission-UI csomagok több gyártónál/Android verzión
    private val permissionPkgs = setOf(
        "com.android.permissioncontroller",     // AOSP / Pixel (Android 10+)
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",         // Régebbi AOSP
        "com.miui.securitycenter",              // Xiaomi / MIUI
        "com.samsung.android.packageinstaller", // Samsung
        "com.coloros.safecenter",               // OPPO / realme
        "com.huawei.systemmanager",
        "com.vivo.securedaemonservice",
        "com.oneplus.security"
    )

    // Gyakori osztálynév-részletek a runtime permission képernyőkre
    private val classHints = listOf(
        "GrantPermissions", "Permission", "AppPermission", "PackageInstaller"
    )

    private var paused = false

    override fun onServiceConnected() {
        // no-op, a működés kulcsa az onAccessibilityEvent
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Csak akkor figyelünk, ha a felhasználó bekapcsolta a funkciót a Settings-ben
        val enabledByUser = try {
            getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("perm_watch", false)
        } catch (_: Exception) { false }
        if (!enabledByUser) return

        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()

        val isPermissionUi = pkg in permissionPkgs ||
                classHints.any { hint -> cls.contains(hint, ignoreCase = true) }

        if (isPermissionUi && !paused) {
            paused = true
            sendBroadcast(Intent(FloatingService.ACTION_PAUSE))
            return
        }

        // Ha elhagytuk a permission-UI-t és korábban pausoltunk → resume
        if (paused && !isPermissionUi) {
            paused = false
            sendBroadcast(Intent(FloatingService.ACTION_RESUME))
        }
    }

    override fun onInterrupt() {
        // no-op
    }
}
