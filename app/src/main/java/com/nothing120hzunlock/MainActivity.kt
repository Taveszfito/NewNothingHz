package com.nothing120hzunlock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : ComponentActivity() {

    private lateinit var switchOverlay: SwitchMaterial
    private lateinit var statusText: TextView
    private lateinit var btnCheckOverlay: Button
    private lateinit var btnRequestOverlay: Button
    private lateinit var btnBatteryOpt: Button
    private lateinit var switchAutostart: SwitchMaterial

    // hogy a programozott .isChecked beállítás ne triggelje a listenert
    private var suppressSwitchCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchOverlay = findViewById(R.id.switchOverlay)
        statusText = findViewById(R.id.statusText)
        btnCheckOverlay = findViewById(R.id.btnCheckOverlay)
        btnRequestOverlay = findViewById(R.id.btnRequestOverlay)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)
        switchAutostart = findViewById(R.id.switchAutostart)

        // kezdeti szinkron
        syncUi()

        // Overlay be/ki kapcsolás
        switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission missing.", Toast.LENGTH_SHORT).show()
                openOverlaySettings()
                // ne állítsuk azonnal vissza, hagyjuk hogy a syncUi majd hozza szinkronba
                return@setOnCheckedChangeListener
            }

            // ideiglenesen tiltjuk a kapcsolót, amíg a service állapot vált
            switchOverlay.isEnabled = false

            if (isChecked) {
                // csak sima startService kell!
                startService(Intent(this, FloatingService::class.java))
                Toast.makeText(this, "120Hz unlocked", Toast.LENGTH_SHORT).show()
            } else {
                stopService(Intent(this, FloatingService::class.java))
                Toast.makeText(this, "Overlay stopped.", Toast.LENGTH_SHORT).show()
            }

            // kis késleltetés, hogy a FloatingService.isRunning már a valós állapotot adja
            Handler(Looper.getMainLooper()).postDelayed({
                syncUi()
                switchOverlay.isEnabled = true
            }, 200)
        }

        // Engedély ellenőrzése
        btnCheckOverlay.setOnClickListener {
            val ok = Settings.canDrawOverlays(this)
            Toast.makeText(
                this,
                if (ok) "Overlay permission: GRANTED" else "Overlay permission: MISSING",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Engedély kérése
        btnRequestOverlay.setOnClickListener { openOverlaySettings() }

        // Akkumulátor-optimalizálás kivétel (opcionális)
        btnBatteryOpt.setOnClickListener { requestBatteryOptException() }

        // Autostart kapcsoló állapot mentése
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        switchAutostart.isChecked = prefs.getBoolean("autostart", false)
        switchAutostart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("autostart", checked).apply()
            Toast.makeText(
                this,
                if (checked) "Auto-start enabled." else "Auto-start disabled.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // ha az engedély képernyőről jövünk vissza, vagy a service máshol indult/leállt
        syncUi()
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
}
