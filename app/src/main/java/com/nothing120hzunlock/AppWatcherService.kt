package com.nothing120hzunlock.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Intent

class AppWatcherService : AccessibilityService() {

    companion object {
        const val ACTION_TOP_APP = "com.nothing120hzunlock.TOP_APP_CHANGED"
        const val EXTRA_PKG = "pkg"
    }

    private var lastPkg: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val t = event.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            t != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == lastPkg) return
        lastPkg = pkg

        // Közöljük a PolicyReceiver-rel az aktuális appot
        sendBroadcast(Intent(ACTION_TOP_APP).putExtra(EXTRA_PKG, pkg))

        // Opcionális: kérjünk azonnali újraértékelést is
        sendBroadcast(Intent("com.nothing120hzunlock.ACTION_EVAL_POLICY"))
    }

    override fun onInterrupt() {}
}
