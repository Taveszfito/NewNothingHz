package com.nothing120hzunlock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.max

class PermissionWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "PermWatcher"

        private const val ACTION_PAUSE  = "com.nothing120hzunlock.ACTION_OVERLAY_PAUSE"
        private const val ACTION_RESUME = "com.nothing120hzunlock.ACTION_OVERLAY_RESUME"

        const val ACTION_TOP_APP = "com.nothing120hzunlock.TOP_APP_CHANGED"
        const val EXTRA_PKG = "pkg"

        private const val BASE_HOLD_MS = 2000L
        private const val HOLD_EXTEND_MS = 800L
        private const val MIN_TOGGLE_INTERVAL_MS = 200L
        private const val CLEAR_STABLE_MS = 500L

        private const val TOP_STABLE_MS = 180L            // ennyi ideig legyen stabil a jelölt
        private val IGNORED_PKGS = setOf("com.android.systemui")

        private val PERMISSION_PACKAGES = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.samsung.android.permissioncontroller",
            "com.miui.securitycenter",
            "com.miui.securitycore",
            "com.coloros.securitypermission",
            "com.oplus.securitypermission",
            "com.oneplus.security",
            "com.vivo.permissionmanager",
            "com.huawei.systemmanager"
        )

        private val SETTINGS_REQUIRE_CLASS = setOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.nothing.settings"
        )

        private val CHROMIUM_PKGS = setOf(
            "com.android.chrome","com.chrome.beta","com.chrome.dev","com.chrome.canary",
            "com.brave.browser","com.microsoft.emmx",
            "com.opera.browser","com.opera.mini.native","com.opera.gx",
            "com.vivaldi.browser","com.vivaldi.browser.snapshot",
            "com.sec.android.app.sbrowser","org.kiwibrowser.browser",
            "com.yandex.browser","com.duckduckgo.mobile.android","com.ecosia.android",
            "com.mi.globalbrowser","com.miui.browser","com.naver.whale"
        )

        private val PERMISSION_CLASS_HINTS = arrayOf(
            "GrantPermissions","ReviewPermissions","ManagePermissions",
            "PermissionActivity","PermissionController","PermissionDialog",
            "Overlay","DrawOverlay","ManageOverlay","UnknownSources","ExternalSources",
            "WriteSettings","SpecialAppAccess","AppOps","ManageAppOps",
            "Install","PackageInstaller","ResolverActivity","ChooserActivity",
            "Dialog","Modal","Interstitial"
        )

        // — Akkumulátor ellenőrzés — //
        private const val BATTERY_POLL_MS = 10_000L       // folyamatos ellenőrzés: 10 mp-enként
        private const val PREFS_NAME = "prefs"
        private val PREF_KEYS_INSTANT_REEVAL = setOf(
            "battery_threshold_percent", "user_wants_overlay", "pause_on_saver"
        )
    }

    private val main = Handler(Looper.getMainLooper())

    private var isPaused = false
    private var lastToggleAt = 0L
    private var clearSince = 0L
    private var pauseUntil = 0L

    private var lastEventPkg = ""
    private var lastEventCls = ""
    private var lastTopPkgBroadcasted: String? = null

    // debounce a top-app észleléshez
    private var pendingPkg: String? = null
    private var pendingToken = 0

    // —— Akkumulátor figyelés & pref-listener —— //
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Százalék- vagy töltési státusz-változás → azonnali újraértékelés
            sendBroadcast(
                Intent("com.nothing120hzunlock.ACTION_EVAL_POLICY").setPackage(packageName)
            )
        }
    }

    private val batteryPollRunnable = object : Runnable {
        override fun run() {
            // Folyamatos ellenőrzés akkor is, ha nem ugrik %-ot a kijelzés
            sendBroadcast(
                Intent("com.nothing120hzunlock.ACTION_EVAL_POLICY").setPackage(packageName)
            )
            main.postDelayed(this, BATTERY_POLL_MS)
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in PREF_KEYS_INSTANT_REEVAL) {
            // Csúszka/váltó állítása → azonnali újraértékelés
            sendBroadcast(
                Intent("com.nothing120hzunlock.ACTION_EVAL_POLICY").setPackage(packageName)
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Akkumulátor változás (sticky + későbbi változások)
        val batFilter = IntentFilter().apply { addAction(Intent.ACTION_BATTERY_CHANGED) }
        registerReceiverCompat(batteryReceiver, batFilter)

        // Pref-változások figyelése (küszöb/csúszka azonnali hatás)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        // Periodikus ellenőrzés indítása
        main.post(batteryPollRunnable)

        // Kezdő állapot azonnali kiértékelése
        sendBroadcast(
            Intent("com.nothing120hzunlock.ACTION_EVAL_POLICY").setPackage(packageName)
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // 1) Ütemezett, stabil top-app észlelés
                scheduleTopAppCandidate(event.packageName?.toString())

                // 2) Permission prompt auto-pause (csak ha bekapcsoltad)
                lastEventPkg = event.packageName?.toString().orEmpty()
                lastEventCls = event.className?.toString().orEmpty()
                if (isPermWatchOn()) recomputeState()
            }
        }
    }

    private fun scheduleTopAppCandidate(fromEventPkg: String?) {
        val candidate = (fromEventPkg ?: rootInActiveWindow?.packageName?.toString()).orEmpty()
        if (candidate.isEmpty() || candidate in IGNORED_PKGS) return

        pendingPkg = candidate
        val myToken = ++pendingToken

        // töröld a korábbi ütemezést, majd 180 ms múlva ellenőrizzünk
        main.removeCallbacksAndMessages("top")
        main.postAtTime({
            // ha közben jött újabb jelölt, ezt hagyjuk
            if (myToken != pendingToken) return@postAtTime

            // végső ellenőrzés az aktuális rootból
            val stable = rootInActiveWindow?.packageName?.toString().orEmpty()
            val pkg = (if (stable.isNotEmpty()) stable else candidate)
            if (pkg.isEmpty() || pkg == lastTopPkgBroadcasted || pkg in IGNORED_PKGS) return@postAtTime

            lastTopPkgBroadcasted = pkg
            sendBroadcast(
                Intent(ACTION_TOP_APP).setPackage(packageName).putExtra(EXTRA_PKG, pkg)
            )
            sendBroadcast(
                Intent("com.nothing120hzunlock.ACTION_EVAL_POLICY").setPackage(packageName)
            )
            Log.d(TAG, "Top app: $pkg")
        }, "top", System.currentTimeMillis() + TOP_STABLE_MS)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        // top-app debounce
        main.removeCallbacksAndMessages("top")

        // akku-figyelés takarítás
        runCatching { unregisterReceiver(batteryReceiver) }
        main.removeCallbacks(batteryPollRunnable)
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }

        if (isPaused) sendBroadcast(Intent(ACTION_RESUME).setPackage(packageName))
        super.onDestroy()
    }

    // —— Permission-UI auto-pause —— //

    private fun recomputeState() {
        val now = System.currentTimeMillis()
        val visible =
            isPermissionUiVisibleFromWindows() ||
                    fallbackByEvent() ||
                    fallbackTextHeuristic()

        if (visible) {
            pauseUntil = max(pauseUntil, now + if (isPaused) HOLD_EXTEND_MS else BASE_HOLD_MS)
            clearSince = 0L
            if (!isPaused) toggle(true)
        } else {
            if (clearSince == 0L) clearSince = now
            if (isPaused && now >= pauseUntil && (now - clearSince) >= CLEAR_STABLE_MS) {
                toggle(false)
            }
        }
    }

    private fun toggle(pause: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastToggleAt < MIN_TOGGLE_INTERVAL_MS) return
        lastToggleAt = now

        isPaused = pause
        val action = if (pause) ACTION_PAUSE else ACTION_RESUME
        sendBroadcast(Intent(action).setPackage(packageName))
        Log.d(TAG, if (pause) "PAUSE overlay (a11y)" else "RESUME overlay (a11y)")
    }

    private fun isPermissionUiVisibleFromWindows(): Boolean {
        val ws = windows ?: return false
        for (w in ws) {
            val r = w.root ?: continue
            if (isPermissionUiNode(r)) return true
        }
        return false
    }

    private fun isPermissionUiNode(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString().orEmpty()
        val cls = root.className?.toString().orEmpty()

        if (pkg in PERMISSION_PACKAGES) return true
        if (pkg in SETTINGS_REQUIRE_CLASS && looksLikePermissionClass(cls)) return true

        if (pkg in CHROMIUM_PKGS) {
            if (looksLikeChromiumDialog(cls)) return true
            if (containsViewIdSubstring(root, "permission")) return true
        }

        if (looksLikePermissionClass(cls)) return true
        if (containsPermissionKeywords(root)) return true
        return false
    }

    private fun looksLikePermissionClass(cls: String): Boolean {
        if (cls.isEmpty()) return false
        val simple = cls.substringAfterLast('.')
        return PERMISSION_CLASS_HINTS.any { h ->
            simple.contains(h, true) || cls.contains(h, true)
        }
    }

    private fun containsViewIdSubstring(node: AccessibilityNodeInfo?, needle: String): Boolean {
        node ?: return false
        if ((node.viewIdResourceName ?: "").contains(needle, true)) return true
        for (i in 0 until node.childCount) if (containsViewIdSubstring(node.getChild(i), needle)) return true
        return false
    }

    private fun fallbackByEvent(): Boolean {
        val pkg = lastEventPkg
        val cls = lastEventCls
        if (pkg.isEmpty() && cls.isEmpty()) return false
        if (pkg in PERMISSION_PACKAGES) return true
        if (pkg in SETTINGS_REQUIRE_CLASS && looksLikePermissionClass(cls)) return true
        if (pkg in CHROMIUM_PKGS && looksLikeChromiumDialog(cls)) return true
        return looksLikePermissionClass(cls)
    }

    // Add this inside PermissionWatcherService class, near the other helpers
    private fun looksLikeChromiumDialog(cls: String?): Boolean {
        if (cls.isNullOrEmpty()) return false
        val s = cls.substringAfterLast('.')
        return s.contains("Dialog", true) ||
                s.contains("Modal", true) ||
                s.contains("Interstitial", true) ||
                cls.contains("Dialog", true) ||
                cls.contains("Modal", true) ||
                cls.contains("Interstitial", true)
    }

    private fun fallbackTextHeuristic(): Boolean {
        val root = rootInActiveWindow ?: return false
        return containsPermissionKeywords(root)
    }

    private fun containsPermissionKeywords(node: AccessibilityNodeInfo?): Boolean {
        node ?: return false
        val t = ((node.text ?: node.contentDescription) ?: "").toString().lowercase()
        if (t.contains("permission") || t.contains("allow") || t.contains("deny")
            || t.contains("engedély") || t.contains("engedélyez")) return true
        for (i in 0 until node.childCount) if (containsPermissionKeywords(node.getChild(i))) return true
        return false
    }

    private fun isPermWatchOn(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("perm_watch", true)

    // --- Compat helper a registerReceiver-hez (Android 13+ flag szükséges) ---
    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }
}
