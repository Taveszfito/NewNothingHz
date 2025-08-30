package com.nothing120hzunlock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator

class MainActivity : ComponentActivity() {

    private lateinit var switchOverlay: SwitchMaterial

    private var suppressPermWatchChange = false
    private lateinit var statusText: TextView
    private lateinit var btnCheckOverlay: Button
    private lateinit var btnRequestOverlay: Button
    private lateinit var btnBatteryOpt: Button
    private lateinit var switchAutostart: SwitchMaterial

    // Permission-watcher UI elemek
    private lateinit var switchPermWatch: SwitchMaterial
    private lateinit var btnOpenA11y: Button

    // hogy a programozott .isChecked beállítás ne triggelje a listenert
    private var suppressSwitchCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ----- View-k -----
        switchOverlay     = findViewById(R.id.switchOverlay)
        statusText        = findViewById(R.id.statusText)
        btnCheckOverlay   = findViewById(R.id.btnCheckOverlay)
        btnRequestOverlay = findViewById(R.id.btnRequestOverlay)
        btnBatteryOpt     = findViewById(R.id.btnBatteryOpt)
        switchAutostart   = findViewById(R.id.switchAutostart)
        switchPermWatch   = findViewById(R.id.switchPermWatch)
        btnOpenA11y       = findViewById(R.id.btnOpenA11y)

        // ÚJ: Fejlesztői beállítások gomb (ha az XML-ben jelen van)
        findViewById<Button?>(R.id.btnOpenDevOptions)?.setOnClickListener { openDeveloperOptions() }

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // kezdeti szinkron (overlay állapot)
        syncUi()

        // ----- Overlay be/ki -----
        switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener

            // ENGEDÉLY GUARD: csak ON esetén vizsgálunk
            if (isChecked && !Settings.canDrawOverlays(this)) {
                // visszapattintjuk OFF-ra és szólunk
                suppressSwitchCallback = true
                switchOverlay.isChecked = false
                suppressSwitchCallback = false

                Toast.makeText(
                    this,
                    "To enable the overlay, allow “Display over other apps” for this app first.",
                    Toast.LENGTH_LONG
                ).show()
                openOverlaySettings()
                return@setOnCheckedChangeListener
            }

            // ideiglenesen tiltjuk a kapcsolót, amíg a service állapot vált
            switchOverlay.isEnabled = false

            if (isChecked) {
                startService(Intent(this, FloatingService::class.java))
                Toast.makeText(this, "120Hz unlocked", Toast.LENGTH_SHORT).show()
            } else {
                stopService(Intent(this, FloatingService::class.java))
                Toast.makeText(this, "Overlay stopped.", Toast.LENGTH_SHORT).show()
            }

            Handler(Looper.getMainLooper()).postDelayed({
                syncUi()
                switchOverlay.isEnabled = true
            }, 200)
        }

        // ----- Overlay permission ellenőrzés -----
        btnCheckOverlay.setOnClickListener {
            val ok = Settings.canDrawOverlays(this)
            Toast.makeText(
                this,
                if (ok) "Overlay permission: GRANTED" else "Overlay permission: MISSING",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Overlay permission kérése
        btnRequestOverlay.setOnClickListener { openOverlaySettings() }

        // Akkumulátor-optimalizálás kivétel (opcionális)
        btnBatteryOpt.setOnClickListener { requestBatteryOptException() }

        // ----- Autostart kapcsoló -----
        switchAutostart.isChecked = prefs.getBoolean("autostart", false)
        switchAutostart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("autostart", checked).apply()
            Toast.makeText(
                this,
                if (checked) "Auto-start enabled." else "Auto-start disabled.",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ===== Permission-watcher kapcsoló + gyorslink =====
        switchPermWatch.isChecked = prefs.getBoolean("perm_watch", false)

        switchPermWatch.setOnCheckedChangeListener { _, checked ->
            if (suppressPermWatchChange) return@setOnCheckedChangeListener

            if (checked) {
                if (!isAccessibilityEnabled()) {
                    // visszapattintjuk OFF-ra és szólunk
                    suppressPermWatchChange = true
                    switchPermWatch.isChecked = false
                    suppressPermWatchChange = false

                    Toast.makeText(
                        this,
                        "To use auto-pause, enable the app’s Accessibility service first.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnCheckedChangeListener
                } else {
                    prefs.edit().putBoolean("perm_watch", true).apply()
                    Toast.makeText(this, "Permission-watcher ON", Toast.LENGTH_SHORT).show()
                }
            } else {
                prefs.edit().putBoolean("perm_watch", false).apply()
                Toast.makeText(this, "Permission-watcher OFF", Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenA11y.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.onFailure {
                Toast.makeText(this, "Accessibility settings not available on this device.", Toast.LENGTH_SHORT).show()
            }
        }

        // --- Kinyitható kártyák bekötése ---
        setupExpandableSections()
    }

    override fun onResume() {
        super.onResume()
        // ha az engedély képernyőről jövünk vissza, vagy a service máshol indult/leállt
        syncUi()

        // Infó: perm-watch be van kapcsolva, de Accessibility ki van
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val wanted = prefs.getBoolean("perm_watch", false)
        if (wanted && !isAccessibilityEnabled()) {
            Toast.makeText(
                this,
                "Accessibility service is OFF — auto-pause won't run until you enable it.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Mindig a FloatingService.isRunning alapján frissítünk mindent. */
    private fun syncUi() {
        val running = FloatingService.isRunning
        suppressSwitchCallback = true
        switchOverlay.isChecked = running
        suppressSwitchCallback = false
        statusText.text = if (running) "Overlay: ON" else "Overlay: OFF"
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Developer options screen is not available on this device.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestBatteryOptException() {
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
            Toast.makeText(this, "Not supported on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    // Gyors ellenőrzés, hogy az AccessibilityService engedélyezve van-e
    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val shortName = "$packageName/.PermissionWatcherService"
            val longName  = PermissionWatcherService::class.java.name

            enabled.split(':').any { entry ->
                entry.equals(shortName, ignoreCase = true) ||
                        entry.contains(longName, ignoreCase = true) ||
                        (entry.contains(packageName) && entry.contains("PermissionWatcherService"))
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---- Expandable card helpers (Permissions / About / Privacy) ----
    private fun setupExpandableSections() {
        // Permissions
        val permHeader  = findViewById<View>(R.id.permissionsHeader)
        val permContent = findViewById<View>(R.id.permissionsContent)
        val permChevron = findViewById<ImageView>(R.id.permissionsChevron)
        permChevron.rotation = if (permContent.visibility == View.VISIBLE) 180f else 0f
        permHeader.setOnClickListener { toggleSectionAnimated(permContent, permChevron) }

        // About
        val aboutHeader  = findViewById<View>(R.id.aboutHeader)
        val aboutContent = findViewById<View>(R.id.aboutContent)
        val aboutChevron = findViewById<ImageView>(R.id.aboutChevron)
        aboutChevron.rotation = if (aboutContent.visibility == View.VISIBLE) 180f else 0f
        aboutHeader.setOnClickListener { toggleSectionAnimated(aboutContent, aboutChevron) }

        // Privacy
        val privacyHeader  = findViewById<View>(R.id.privacyHeader)
        val privacyContent = findViewById<View>(R.id.privacyContent)
        val privacyChevron = findViewById<ImageView>(R.id.privacyChevron)
        privacyChevron.rotation = if (privacyContent.visibility == View.VISIBLE) 180f else 0f
        privacyHeader.setOnClickListener { toggleSectionAnimated(privacyContent, privacyChevron) }
    }

    private fun toggleSectionAnimated(section: View, chevron: ImageView) {
        if (section.visibility == View.VISIBLE) {
            collapse(section)
            chevron.animate()
                .rotation(0f)
                .setDuration(150)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            expand(section)
            chevron.animate()
                .rotation(180f)
                .setDuration(150)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    private fun expand(section: View) {
        // lemérjük a cél magasságot
        val parentWidth = (section.parent as View).width
        section.measure(
            View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val target = section.measuredHeight.coerceAtLeast(1)

        section.layoutParams.height = 0
        section.visibility = View.VISIBLE

        val anim = ValueAnimator.ofInt(0, target)
        anim.addUpdateListener { va ->
            val lp = section.layoutParams
            lp.height = va.animatedValue as Int
            section.layoutParams = lp
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // vissza wrap_content-re, hogy később rugalmas maradjon
                val lp = section.layoutParams
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                section.layoutParams = lp
            }
        })
        anim.duration = 150
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.start()
    }

    private fun collapse(section: View) {
        val parentWidth = (section.parent as View).width
        if (section.measuredHeight <= 0) {
            section.measure(
                View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }
        val startH = section.measuredHeight.coerceAtLeast(1)

        val anim = ValueAnimator.ofInt(startH, 0)
        anim.addUpdateListener { va ->
            val lp = section.layoutParams
            lp.height = va.animatedValue as Int
            section.layoutParams = lp
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                section.visibility = View.GONE
                val lp = section.layoutParams
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                section.layoutParams = lp
            }
        })
        anim.duration = 150
        anim.interpolator = AccelerateDecelerateInterpolator()
        anim.start()
    }
}
