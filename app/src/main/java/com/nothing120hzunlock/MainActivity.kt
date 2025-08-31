package com.nothing120hzunlock

import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class MainActivity : ComponentActivity() {

    // === Existing views ===
    private lateinit var switchOverlay: SwitchMaterial
    private lateinit var statusText: TextView
    private lateinit var btnCheckOverlay: Button
    private lateinit var btnRequestOverlay: Button
    private lateinit var btnBatteryOpt: Button
    private lateinit var switchAutostart: SwitchMaterial
    private lateinit var switchPermWatch: SwitchMaterial
    private lateinit var btnOpenA11y: Button

    // === New: Battery Saving section views ===
    private var switchPauseOnSaver: SwitchMaterial? = null
    private var sliderBatteryThreshold: Slider? = null
    private var textBatteryThresholdValue: TextView? = null

    // === New: App Blacklist section views ===
    private var blacklistSearchInput: TextInputEditText? = null
    private var switchShowSystemApps: SwitchMaterial? = null
    private var recyclerBlacklist: RecyclerView? = null
    private var textBlacklistEmpty: TextView? = null

    // === Adapter/data ===
    private val allApps = mutableListOf<AppEntry>()
    private val filteredApps = mutableListOf<AppEntry>()
    private lateinit var blacklistAdapter: AppListAdapter

    // === Flags ===
    private var suppressPermWatchChange = false
    private var suppressSwitchCallback = false

    // === Pref keys ===
    private val PREFS = "prefs"
    private val KEY_AUTOSTART = "autostart"
    private val KEY_PERM_WATCH = "perm_watch"
    private val KEY_PAUSE_ON_SAVER = "pause_on_saver"
    private val KEY_BATT_THRESHOLD = "battery_threshold_percent" // int 0..50 (0 = off)
    private val KEY_BLACKLIST_SET = "blacklist_packages" // string set
    private val KEY_SHOW_SYSTEM = "show_system_apps" // bool
    private val KEY_USER_WANTS = "user_wants_overlay"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ====== Existing bindings ======
        switchOverlay     = findViewById(R.id.switchOverlay)
        statusText        = findViewById(R.id.statusText)
        btnCheckOverlay   = findViewById(R.id.btnCheckOverlay)
        btnRequestOverlay = findViewById(R.id.btnRequestOverlay)
        btnBatteryOpt     = findViewById(R.id.btnBatteryOpt)
        switchAutostart   = findViewById(R.id.switchAutostart)
        switchPermWatch   = findViewById(R.id.switchPermWatch)
        btnOpenA11y       = findViewById(R.id.btnOpenA11y)
        findViewById<Button?>(R.id.btnOpenDevOptions)?.setOnClickListener { openDeveloperOptions() }

        // ====== New bindings (Battery Saving) ======
        switchPauseOnSaver        = findViewById(R.id.switchPauseOnSaver)
        sliderBatteryThreshold    = findViewById(R.id.sliderBatteryThreshold)
        textBatteryThresholdValue = findViewById(R.id.textBatteryThresholdValue)

        // ====== New bindings (App Blacklist) ======
        blacklistSearchInput   = findViewById(R.id.inputBlacklistSearch)
        switchShowSystemApps   = findViewById(R.id.switchShowSystemApps)
        recyclerBlacklist      = findViewById(R.id.recyclerBlacklistApps)
        textBlacklistEmpty     = findViewById(R.id.textBlacklistEmpty)

        // ====== Initial UI sync ======
        syncUi()

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // ----- Overlay ON/OFF -----
        switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener

            // Guard: permission must exist to turn ON
            if (isChecked && !Settings.canDrawOverlays(this)) {
                // Ensure pref stays false if permission missing
                prefs.edit().putBoolean(KEY_USER_WANTS, false).apply()
                suppressSwitchCallback = true
                switchOverlay.isChecked = false
                suppressSwitchCallback = false

                Toast.makeText(
                    this,
                    "To enable the overlay, allow “Display over other apps” for this app first.",
                    Toast.LENGTH_LONG
                ).show()
                openOverlaySettings()
                // Also ask policy to re-evaluate based on false state
                sendBroadcast(Intent(com.nothing120hzunlock.policy.PolicyReceiver.ACTION_EVAL_POLICY))
                return@setOnCheckedChangeListener
            }

            // Save user's intent now that the state is valid
            prefs.edit().putBoolean(KEY_USER_WANTS, isChecked).apply()
            // ask policy to (re)evaluate immediately
            sendBroadcast(Intent(com.nothing120hzunlock.policy.PolicyReceiver.ACTION_EVAL_POLICY))

            // Temporarily disable switch while service flips
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

        // ----- Overlay permission check/request -----
        btnCheckOverlay.setOnClickListener {
            val ok = Settings.canDrawOverlays(this)
            Toast.makeText(
                this,
                if (ok) "Overlay permission: GRANTED" else "Overlay permission: MISSING",
                Toast.LENGTH_SHORT
            ).show()
        }
        btnRequestOverlay.setOnClickListener { openOverlaySettings() }

        // ----- Battery optimization exception -----
        btnBatteryOpt.setOnClickListener { requestBatteryOptException() }

        // ----- Autostart toggle -----
        switchAutostart.isChecked = prefs.getBoolean(KEY_AUTOSTART, false)
        switchAutostart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTOSTART, checked).apply()
            Toast.makeText(
                this,
                if (checked) "Auto-start enabled." else "Auto-start disabled.",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ===== Permission-watcher toggle + quick link =====
        switchPermWatch.isChecked = prefs.getBoolean(KEY_PERM_WATCH, false)
        switchPermWatch.setOnCheckedChangeListener { _, checked ->
            if (suppressPermWatchChange) return@setOnCheckedChangeListener

            if (checked) {
                if (!isAccessibilityEnabled()) {
                    suppressPermWatchChange = true
                    switchPermWatch.isChecked = false
                    suppressPermWatchChange = false
                    Toast.makeText(
                        this,
                        "To use auto-pause, enable the app’s Accessibility service first.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    prefs.edit().putBoolean(KEY_PERM_WATCH, true).apply()
                    Toast.makeText(this, "Permission-watcher ON", Toast.LENGTH_SHORT).show()
                }
            } else {
                prefs.edit().putBoolean(KEY_PERM_WATCH, false).apply()
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

        // === NEW: Battery Saving bindings ===
        // Pause on Battery Saver
        switchPauseOnSaver?.let { sw ->
            sw.isChecked = prefs.getBoolean(KEY_PAUSE_ON_SAVER, false)
            sw.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(KEY_PAUSE_ON_SAVER, checked).apply()
                // re-evaluate immediately
                sendBroadcast(Intent(com.nothing120hzunlock.policy.PolicyReceiver.ACTION_EVAL_POLICY))
                Toast.makeText(
                    this,
                    if (checked) "Will pause overlay when Battery Saver is active." else "Won't pause on Battery Saver.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Threshold slider live value + save
        sliderBatteryThreshold?.let { slider ->
            val saved = prefs.getInt(KEY_BATT_THRESHOLD, 0)
            if (saved in 0..50) slider.value = saved.toFloat()
            textBatteryThresholdValue?.text = "${slider.value.toInt()}%"

            slider.addOnChangeListener { _, value, fromUser ->
                val pct = value.toInt()
                textBatteryThresholdValue?.text = "$pct%"
                prefs.edit().putInt(KEY_BATT_THRESHOLD, pct).apply()
                if (fromUser) {
                    // re-evaluate immediately when user moves the slider
                    sendBroadcast(Intent(com.nothing120hzunlock.policy.PolicyReceiver.ACTION_EVAL_POLICY))
                }
            }
        }

        // === NEW: Blacklist list + search ===
        // RecyclerView setup
        recyclerBlacklist?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(false)
        }

        val blacklisted = prefs.getStringSet(KEY_BLACKLIST_SET, emptySet())?.toMutableSet() ?: mutableSetOf()
        blacklistAdapter = AppListAdapter(
            data = filteredApps,
            isBlacklisted = { pkg -> pkg in blacklisted },
            onToggle = { pkg, makeBlacklisted ->
                if (makeBlacklisted) {
                    blacklisted.add(pkg)
                } else {
                    blacklisted.remove(pkg)
                }
                prefs.edit().putStringSet(KEY_BLACKLIST_SET, blacklisted).apply()
                // ask policy to re-evaluate (useful once blacklist policy is wired)
                sendBroadcast(Intent(com.nothing120hzunlock.policy.PolicyReceiver.ACTION_EVAL_POLICY))
            }
        )
        recyclerBlacklist?.adapter = blacklistAdapter

        // Show system apps toggle
        val showSys = prefs.getBoolean(KEY_SHOW_SYSTEM, false)
        switchShowSystemApps?.isChecked = showSys
        switchShowSystemApps?.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_SYSTEM, checked).apply()
            filterAndShowApps(
                query = blacklistSearchInput?.text?.toString().orEmpty(),
                showSystem = checked
            )
        }

        // Search field
        blacklistSearchInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterAndShowApps(
                    query = s?.toString().orEmpty(),
                    showSystem = switchShowSystemApps?.isChecked ?: false
                )
            }
        })

        // Load apps in background once the section exists (loading is cheap, but do it async)
        loadAppsAsync {
            // After load, apply initial filter
            filterAndShowApps(
                query = blacklistSearchInput?.text?.toString().orEmpty(),
                showSystem = switchShowSystemApps?.isChecked ?: false
            )
        }

        // --- Expandable sections (add new sections too) ---
        setupExpandableSections()
    }

    override fun onResume() {
        super.onResume()
        syncUi()

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wanted = prefs.getBoolean(KEY_PERM_WATCH, false)
        if (wanted && !isAccessibilityEnabled()) {
            Toast.makeText(
                this,
                "Accessibility service is OFF — auto-pause won't run until you enable it.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Keep overlay switch & status in sync with service. */
    private fun syncUi() {
        val running = FloatingService.isRunning
        suppressSwitchCallback = true
        switchOverlay.isChecked = running
        suppressSwitchCallback = false
        statusText.text = if (running) "Overlay: ON" else "Overlay: OFF"
    }

    // ===== Helpers: permissions / intents =====

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

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            list.any { it.resolveInfo?.serviceInfo?.packageName == packageName }
        } catch (_: Exception) {
            false
        }
    }

    // ===== Expandable card wiring =====

    private fun setupExpandableSections() {
        // Permissions
        runCatching {
            val header  = findViewById<View>(R.id.permissionsHeader)
            val content = findViewById<View>(R.id.permissionsContent)
            val chev    = findViewById<ImageView>(R.id.permissionsChevron)
            chev.rotation = if (content.visibility == View.VISIBLE) 180f else 0f
            header.setOnClickListener { toggleSectionAnimated(content, chev) }
        }

        // Battery Saving (NEW)
        runCatching {
            val header  = findViewById<View>(R.id.batteryHeader)
            val content = findViewById<View>(R.id.batteryContent)
            val chev    = findViewById<ImageView>(R.id.batteryChevron)
            chev.rotation = if (content.visibility == View.VISIBLE) 180f else 0f
            header.setOnClickListener { toggleSectionAnimated(content, chev) }
        }

        // App Blacklist (NEW)
        runCatching {
            val header  = findViewById<View>(R.id.blacklistHeader)
            val content = findViewById<View>(R.id.blacklistContent)
            val chev    = findViewById<ImageView>(R.id.blacklistChevron)
            chev.rotation = if (content.visibility == View.VISIBLE) 180f else 0f
            header.setOnClickListener { toggleSectionAnimated(content, chev) }
        }

        // About
        runCatching {
            val header  = findViewById<View>(R.id.aboutHeader)
            val content = findViewById<View>(R.id.aboutContent)
            val chev    = findViewById<ImageView>(R.id.aboutChevron)
            chev.rotation = if (content.visibility == View.VISIBLE) 180f else 0f
            header.setOnClickListener { toggleSectionAnimated(content, chev) }
        }

        // Privacy
        runCatching {
            val header  = findViewById<View>(R.id.privacyHeader)
            val content = findViewById<View>(R.id.privacyContent)
            val chev    = findViewById<ImageView>(R.id.privacyChevron)
            chev.rotation = if (content.visibility == View.VISIBLE) 180f else 0f
            header.setOnClickListener { toggleSectionAnimated(content, chev) }
        }
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

    // ===== App list loading & adapter =====

    private fun loadAppsAsync(onDone: () -> Unit) {
        // Heavy-ish query: do it off the main thread
        Thread {
            try {
                val pm = packageManager
                val apps = pm.getInstalledApplications(0)

                val list = apps.mapNotNull { ai ->
                    try {
                        val label = pm.getApplicationLabel(ai)?.toString() ?: ai.packageName
                        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        val icon = pm.getApplicationIcon(ai.packageName)
                        AppEntry(
                            packageName = ai.packageName,
                            appName = label,
                            isSystem = isSystem,
                            iconRes = icon
                        )
                    } catch (_: Exception) {
                        null
                    }
                }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName })

                allApps.clear()
                allApps.addAll(list)
            } catch (_: Exception) {
                allApps.clear()
            }

            Handler(Looper.getMainLooper()).post { onDone() }
        }.start()
    }

    private fun filterAndShowApps(query: String, showSystem: Boolean) {
        filteredApps.clear()
        val q = query.trim().lowercase()

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val blacklisted = prefs.getStringSet(KEY_BLACKLIST_SET, emptySet()) ?: emptySet()

        allApps.forEach { app ->
            if (!showSystem && app.isSystem) return@forEach
            if (q.isNotEmpty()) {
                val hit = app.appName.lowercase().contains(q) || app.packageName.lowercase().contains(q)
                if (!hit) return@forEach
            }
            filteredApps.add(app.copy(isBlacklisted = blacklisted.contains(app.packageName)))
        }

        textBlacklistEmpty?.visibility = if (filteredApps.isEmpty()) View.VISIBLE else View.GONE
        blacklistAdapter.notifyDataSetChanged()
    }

    // Data for each app row
    data class AppEntry(
        val packageName: String,
        val appName: String,
        val isSystem: Boolean,
        val iconRes: android.graphics.drawable.Drawable,
        val isBlacklisted: Boolean = false
    )

    // Simple Material-like row: icon | (title + subtitle) | switch
    inner class AppListAdapter(
        private val data: MutableList<AppEntry>,
        private val isBlacklisted: (String) -> Boolean,
        private val onToggle: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.VH>() {

        inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
            val icon: ImageView = ImageView(root.context).apply {
                val size = dp(40)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = dp(12) }
            }
            val title: TextView = TextView(root.context).apply {
                setTextColor(ContextCompat.getColor(context, android.R.color.primary_text_light))
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }
            val subtitle: TextView = TextView(root.context).apply {
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                textSize = 12f
            }
            val toggle: SwitchMaterial = SwitchMaterial(root.context)

            init {
                val textCol = LinearLayout(root.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(title)
                    addView(subtitle)
                }
                root.addView(icon)
                root.addView(textCol)
                root.addView(toggle)

                root.minimumHeight = dp(56)
                root.setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            return VH(row)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.icon.setImageDrawable(item.iconRes)
            holder.title.text = item.appName
            holder.subtitle.text = item.packageName

            holder.toggle.setOnCheckedChangeListener(null)
            holder.toggle.isChecked = isBlacklisted(item.packageName)
            holder.toggle.setOnCheckedChangeListener { _, checked ->
                onToggle(item.packageName, checked)
            }

            holder.root.setOnClickListener {
                holder.toggle.isChecked = !holder.toggle.isChecked
            }
        }

        override fun getItemCount(): Int = data.size

        private fun dp(v: Int): Int =
            (v * resources.displayMetrics.density).toInt()
    }
}
