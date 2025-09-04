package com.nothing120hzunlock.onboarding

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.Slide
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.nothing120hzunlock.R

class OnboardingActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("prefs", Context.MODE_PRIVATE) }

    private var pageIndex = 0
    private var overlayNoThanksCount = 0

    // „Megválaszolta-e” a nem kötelezőket (grant vagy decline)?
    private var answeredUsage = false
    private var answeredBattery = false
    // Overlay csak akkor „answered”, ha tényleg meg van adva
    private var answeredOverlay = false

    // Oldalak
    private lateinit var pageWelcome: View
    private lateinit var pagePermissions: View
    private lateinit var pageDone: View

    // Alsó navigáció + start
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnStart: Button

    // Engedély UI
    private lateinit var statusUsage: TextView
    private lateinit var statusBattery: TextView
    private lateinit var statusOverlay: TextView

    private lateinit var btnGrantUsage: Button
    private lateinit var btnDeclineUsage: Button

    private lateinit var btnGrantBattery: Button
    private lateinit var btnDeclineBattery: Button

    private lateinit var btnGrantOverlay: Button
    private lateinit var btnDeclineOverlay: Button
    private var txtOverlayLeave: TextView? = null

    // Animációkhoz
    private val sceneRoot by lazy { findViewById<ViewGroup>(android.R.id.content) }
    private val fastOutSlowIn by lazy { FastOutSlowInInterpolator() }
    private var lastBackPressAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        overlayNoThanksCount = prefs.getInt("overlay_no_thanks_count", 0)

        // Oldalak
        pageWelcome     = findViewById(R.id.pageWelcome)
        pagePermissions = findViewById(R.id.pagePermissions)
        pageDone        = findViewById(R.id.pageDone)

        // Gombok
        btnPrev  = findViewById(R.id.btnPrev)
        btnNext  = findViewById(R.id.btnNext)
        btnStart = findViewById(R.id.btnStart)

        btnPrev.setOnClickListener { goPrev() }
        btnNext.setOnClickListener { goNext() }
        btnStart.setOnClickListener { showPage(1) }

        // Címekre NothingDot
        setNothingFont(findViewById(R.id.titleWelcome))
        findViewById<TextView?>(R.id.titlePermissions)?.let { setNothingFont(it) }

        // Engedély widgetek
        statusUsage   = findViewById(R.id.statusUsage)
        statusBattery = findViewById(R.id.statusBattery)
        statusOverlay = findViewById(R.id.statusOverlay)

        btnGrantUsage   = findViewById(R.id.btnGrantUsage)
        btnDeclineUsage = findViewById(R.id.btnDeclineUsage)

        btnGrantBattery   = findViewById(R.id.btnGrantBattery)
        btnDeclineBattery = findViewById(R.id.btnDeclineBattery)

        btnGrantOverlay   = findViewById(R.id.btnGrantOverlay)
        btnDeclineOverlay = findViewById(R.id.btnDeclineOverlay)
        txtOverlayLeave   = findViewById(R.id.txtOverlayLeave)

        // Gomb logika
        btnGrantUsage.setOnClickListener { openUsageAccessSettings() }
        btnDeclineUsage.setOnClickListener {
            answeredUsage = true
            updatePermissionStatuses()
        }

        btnGrantBattery.setOnClickListener { openBatteryOptimizationSettings() }
        btnDeclineBattery.setOnClickListener {
            answeredBattery = true
            updatePermissionStatuses()
        }

        btnGrantOverlay.setOnClickListener { openOverlaySettings() }
        btnDeclineOverlay.setOnClickListener {
            overlayNoThanksCount++
            prefs.edit().putInt("overlay_no_thanks_count", overlayNoThanksCount).apply()

            val msg = if (overlayNoThanksCount <= 1)
                "You’re kidding, right? This one’s mandatory."
            else
                "Still no? This permission is required."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

            answeredOverlay = false
            updatePermissionStatuses()

            if (overlayNoThanksCount >= 10) {
                btnDeclineOverlay.visibility = View.GONE
                txtOverlayLeave?.visibility = View.VISIBLE
            }
        }

        // Kezdő állapot (nem animálva)
        showPage(0, animated = false)
        updatePermissionStatuses()

        // Easter egg állapot visszaállítása
        if (overlayNoThanksCount >= 10) {
            btnDeclineOverlay.visibility = View.GONE
            txtOverlayLeave?.visibility = View.VISIBLE
        }

        // Back: első képernyőn dupla vissza = kilépés, egyébként oldal-vissza
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pageIndex > 0) {
                    showPage(pageIndex - 1)
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressAt < 1500) {
                    finish()
                } else {
                    lastBackPressAt = now
                    Toast.makeText(
                        this@OnboardingActivity,
                        "Press back again to exit.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
    }

    // ---- Navigáció ----
    private fun goPrev() {
        if (pageIndex > 0) showPage(pageIndex - 1)
    }

    private fun goNext() {
        when (pageIndex) {
            0 -> showPage(1)
            1 -> {
                if (gatePermissions()) {
                    showPage(2)
                } else {
                    Toast.makeText(
                        this,
                        "Please answer all permissions to continue.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            2 -> {
                prefs.edit().putBoolean("onboarded_v1", true).apply()
                finish()
            }
        }
    }

    // Fő lapváltó – animáció opcionális
    private fun showPage(index: Int, animated: Boolean = true) {
        if (index == pageIndex) {
            // csak a nav állapotot frissítjük
            updateNavForIndex(index)
            btnNext.isEnabled = if (index == 1) gatePermissions() else true
            return
        }

        val old = when (pageIndex) {
            0 -> pageWelcome
            1 -> pagePermissions
            else -> pageDone
        }
        val new = when (index) {
            0 -> pageWelcome
            1 -> pagePermissions
            else -> pageDone
        }
        val forward = index > pageIndex
        pageIndex = index

        if (animated) {
            animatePageChange(old, new, forward)
        } else {
            old.visibility = View.GONE
            new.visibility = View.VISIBLE
        }

        updateNavForIndex(index)
        btnNext.isEnabled = if (index == 1) gatePermissions() else true
    }

    private fun updateNavForIndex(index: Int) {
        btnPrev.visibility  = if (index == 0) View.INVISIBLE else View.VISIBLE
        btnStart.visibility = if (index == 0) View.VISIBLE   else View.GONE
        btnNext.visibility  = if (index == 0) View.GONE      else View.VISIBLE
        btnNext.text        = if (index == 2) "Finish" else "Next"
    }

    // ---- Oldalváltó animáció ----
    private fun animatePageChange(from: View, to: View, forward: Boolean) {
        val outEdge = if (forward) Gravity.TOP else Gravity.BOTTOM
        val inEdge  = if (forward) Gravity.BOTTOM else Gravity.TOP

        val set = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            duration = 320
            interpolator = fastOutSlowIn
            addTransition(Slide(outEdge).addTarget(from.id))
            addTransition(Slide(inEdge ).addTarget(to.id))
        }

        TransitionManager.beginDelayedTransition(sceneRoot, set)
        from.visibility = View.GONE
        to.visibility   = View.VISIBLE
    }

    // ---- Státuszok + gate ----
    private fun updatePermissionStatuses() {
        val hasOverlay = hasOverlayPermission()
        statusOverlay.text = when {
            hasOverlay -> "✅"
            overlayNoThanksCount > 0 -> "🤔"
            else -> "❓"
        }
        answeredOverlay = hasOverlay

        val hasUsage = hasUsageAccess()
        statusUsage.text = when {
            hasUsage -> "✅"
            answeredUsage -> "❌"
            else -> "❓"
        }

        val hasBattery = hasBatteryOptException()
        statusBattery.text = when {
            hasBattery -> "✅"
            answeredBattery -> "❌"
            else -> "❓"
        }

        if (pageIndex == 1) {
            btnNext.isEnabled = gatePermissions()
        }
    }

    /**
     * Gate logika:
     *  - Overlay: KÖTELEZŐ (csak akkor oké, ha tényleg granted)
     *  - Usage és Battery: vagy granted, vagy megnyomta a „No thanks”-et
     */
    private fun gatePermissions(): Boolean {
        val overlayOk = answeredOverlay
        val usageOk   = hasUsageAccess() || answeredUsage
        val batteryOk = hasBatteryOptException() || answeredBattery
        return overlayOk && usageOk && batteryOk
    }

    // ---- Egyedi font a címekre ----
    private fun setNothingFont(tv: TextView) {
        runCatching {
            tv.typeface = Typeface.createFromAsset(assets, "fonts/nothingdot.ttf")
        }
    }

    // ---- Beállítások megnyitása ----
    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Usage Access settings not available.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Already ignoring battery optimizations.", Toast.LENGTH_SHORT).show()
                return
            }
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(this, "Battery optimization exception not supported on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Ellenőrzések ----
    private fun hasOverlayPermission(): Boolean =
        Settings.canDrawOverlays(this)

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        if (mode == AppOpsManager.MODE_ALLOWED) return true

        return try {
            (packageManager.checkPermission(
                "android.permission.PACKAGE_USAGE_STATS",
                packageName
            ) == PackageManager.PERMISSION_GRANTED) && mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun hasBatteryOptException(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
    }
}
