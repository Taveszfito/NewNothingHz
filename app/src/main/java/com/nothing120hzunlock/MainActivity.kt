package com.nothing120hzunlock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ComponentName
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
import com.nothing120hzunlock.policy.PolicyReceiver
import com.nothing120hzunlock.onboarding.OnboardingActivity   // <-- IMPORT az onboardinghoz

class MainActivity : ComponentActivity() {

    private lateinit var switchOverlay: SwitchMaterial
    private lateinit var statusText: TextView
    private lateinit var btnCheckOverlay: Button
    private lateinit var btnRequestOverlay: Button
    private lateinit var btnBatteryOpt: Button

    private var switchPauseOnSaver: SwitchMaterial? = null
    private var sliderBatteryThreshold: Slider? = null
    private var textBatteryThresholdValue: TextView? = null

    private var blacklistSearchInput: TextInputEditText? = null
    private var switchShowSystemApps: SwitchMaterial? = null
    private var recyclerBlacklist: RecyclerView? = null
    private var textBlacklistEmpty: TextView? = null

    private val allApps = mutableListOf<AppEntry>()
    private val filteredApps = mutableListOf<AppEntry>()
    private lateinit var blacklistAdapter: AppListAdapter

    private var suppressSwitchCallback = false

    private val PREFS = "prefs"
    private val KEY_PAUSE_ON_SAVER = "pause_on_saver"
    private val KEY_BATT_THRESHOLD = "battery_threshold_percent"
    private val KEY_BLACKLIST_SET = "blacklist_packages"
    private val KEY_SHOW_SYSTEM = "show_system_apps"
    private val KEY_USER_WANTS = "user_wants_overlay"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // === FIRST-RUN ONBOARDING GATE ===
        val firstRunDone = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("onboarded_v1", false)
        if (!firstRunDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            // Nem zárjuk be a MainActivity-t; visszatérés után innen folytatódik.
        }

        setContentView(R.layout.activity_main)

        switchOverlay     = findViewById(R.id.switchOverlay)
        statusText        = findViewById(R.id.statusText)
        btnCheckOverlay   = findViewById(R.id.btnCheckOverlay)
        btnRequestOverlay = findViewById(R.id.btnRequestOverlay)
        btnBatteryOpt     = findViewById(R.id.btnBatteryOpt)
        findViewById<Button?>(R.id.btnOpenDevOptions)?.setOnClickListener { openDeveloperOptions() }

        switchPauseOnSaver        = findViewById(R.id.switchPauseOnSaver)
        sliderBatteryThreshold    = findViewById(R.id.sliderBatteryThreshold)
        textBatteryThresholdValue = findViewById(R.id.textBatteryThresholdValue)

        blacklistSearchInput   = findViewById(R.id.inputBlacklistSearch)
        switchShowSystemApps   = findViewById(R.id.switchShowSystemApps)
        recyclerBlacklist      = findViewById(R.id.recyclerBlacklistApps)
        textBlacklistEmpty     = findViewById(R.id.textBlacklistEmpty)

        syncUi()

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener

            if (isChecked && !Settings.canDrawOverlays(this)) {
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
                evalPolicy()
                return@setOnCheckedChangeListener
            }

            prefs.edit().putBoolean(KEY_USER_WANTS, isChecked).apply()
            evalPolicy()

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

        btnCheckOverlay.setOnClickListener {
            val ok = Settings.canDrawOverlays(this)
            Toast.makeText(
                this,
                if (ok) "Overlay permission: GRANTED" else "Overlay permission: MISSING",
                Toast.LENGTH_SHORT
            ).show()
        }
        btnRequestOverlay.setOnClickListener { openOverlaySettings() }

        btnBatteryOpt.setOnClickListener { requestBatteryOptException() }

        findViewById<Button?>(R.id.btnOpenUsageAccess)?.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Usage Access settings not available on this device.", Toast.LENGTH_SHORT).show()
            }
        }

        switchPauseOnSaver?.let { sw ->
            sw.isChecked = prefs.getBoolean(KEY_PAUSE_ON_SAVER, false)
            sw.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(KEY_PAUSE_ON_SAVER, checked).apply()
                evalPolicy()
                Toast.makeText(
                    this,
                    if (checked) "Will pause overlay when Battery Saver is active." else "Won't pause on Battery Saver.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        sliderBatteryThreshold?.let { slider ->
            val saved = prefs.getInt(KEY_BATT_THRESHOLD, 0).coerceIn(0, 50)
            slider.value = saved.toFloat()
            textBatteryThresholdValue?.text = "${slider.value.toInt()}%"
            slider.addOnChangeListener { _, value, fromUser ->
                val pct = value.toInt().coerceIn(0, 50)
                if (pct != value.toInt()) slider.value = pct.toFloat()
                textBatteryThresholdValue?.text = "$pct%"
                prefs.edit().putInt(KEY_BATT_THRESHOLD, pct).apply()
                if (fromUser) evalPolicy()
            }
        }

        recyclerBlacklist?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(false)
        }

        val blacklisted = prefs.getStringSet(KEY_BLACKLIST_SET, emptySet())?.toMutableSet() ?: mutableSetOf()
        blacklistAdapter = AppListAdapter(
            data = filteredApps,
            isBlacklisted = { pkg -> pkg in blacklisted },
            onToggle = { pkg, makeBlacklisted ->
                if (makeBlacklisted) blacklisted.add(pkg) else blacklisted.remove(pkg)
                prefs.edit().putStringSet(KEY_BLACKLIST_SET, blacklisted).apply()
                evalPolicy()
            }
        )
        recyclerBlacklist?.adapter = blacklistAdapter

        val showSys = prefs.getBoolean(KEY_SHOW_SYSTEM, false)
        switchShowSystemApps?.isChecked = showSys
        switchShowSystemApps?.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_SHOW_SYSTEM, checked).apply()
            filterAndShowApps(
                query = blacklistSearchInput?.text?.toString().orEmpty(),
                showSystem = checked
            )
        }

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

        loadAppsAsync {
            filterAndShowApps(
                query = blacklistSearchInput?.text?.toString().orEmpty(),
                showSystem = switchShowSystemApps?.isChecked ?: false
            )
        }

        setupExpandableSections()
    }

    override fun onResume() {
        super.onResume()
        syncUi()
    }

    private fun syncUi() {
        val running = FloatingService.isRunning
        suppressSwitchCallback = true
        switchOverlay.isChecked = running
        suppressSwitchCallback = false
        statusText.text = if (running) "Overlay: ON" else "Overlay: OFF"
    }

    private fun evalPolicy() {
        val intent = Intent(PolicyReceiver.ACTION_EVAL_POLICY).apply {
            component = ComponentName(this@MainActivity, PolicyReceiver::class.java)
        }
        sendBroadcast(intent)
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

    private fun setupExpandableSections() {
        runCatching {
            val header  = findViewById<View>(R.id.permissionsHeader)
            val content = findViewById<View>(R.id.permissionsContent)
            val chev    = findViewById<ImageView>(R.id.permissionsChevron)
            chev.rotation = if (content.visibility == View.VISIBLE) 180f else 0f
            header.setOnClickListener { toggleSectionAnimated(content, chev) }
        }
        runCatching {
            val header  = findViewById<View>(R.id.batteryHeader)
            val content = findViewById<View>(R.id.batteryContent)
            val chev    = findViewById<ImageView>(R.id.batteryChevron)
            chev.rotation = if (content.visibility == View.VISIBLE) 180f else 0f
            header.setOnClickListener { toggleSectionAnimated(content, chev) }
        }
        runCatching {
            val header  = findViewById<View>(R.id.blacklistHeader)
            val content = findViewById<View>(R.id.blacklistContent)
            val chev    = findViewById<ImageView>(R.id.blacklistChevron)
            chev.rotation = if (content.visibility == View.VISIBLE) 180f else 0f
            header.setOnClickListener { toggleSectionAnimated(content, chev) }
        }
        runCatching {
            val header  = findViewById<View>(R.id.aboutHeader)
            val content = findViewById<View>(R.id.aboutContent)
            val chev    = findViewById<ImageView>(R.id.aboutChevron)
            chev.rotation = if (content.visibility == View.VISIBLE) 180f else 0f
            header.setOnClickListener { toggleSectionAnimated(content, chev) }
        }
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

    private fun loadAppsAsync(onDone: () -> Unit) {
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

    data class AppEntry(
        val packageName: String,
        val appName: String,
        val isSystem: Boolean,
        val iconRes: android.graphics.drawable.Drawable,
        val isBlacklisted: Boolean = false
    )

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
