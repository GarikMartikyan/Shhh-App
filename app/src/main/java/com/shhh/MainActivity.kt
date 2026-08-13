package com.shhh

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var gauge: TiltGauge
    private lateinit var powerPill: TextView
    private lateinit var coach: TextView
    private lateinit var permissionCard: LinearLayout
    private lateinit var permissionText: TextView
    private lateinit var dndButton: Button
    private lateinit var notifButton: Button
    private lateinit var batteryButton: Button
    private lateinit var sensBlurb: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var historySummary: TextView
    private lateinit var notificationSwitch: Switch
    private lateinit var notificationSettings: Button
    private lateinit var diagnosticsToggle: TextView
    private lateinit var diagnosticsBody: LinearLayout
    private lateinit var readout: TextView

    private lateinit var sensPills: Map<Sensitivity, TextView>

    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        // Targeting SDK 35+ forces edge-to-edge, so the layout sits behind the status bar. Add the
        // real inset rather than a guessed dp, or the wordmark collides with the clock.
        val content = findViewById<LinearLayout>(R.id.content)
        val basePaddingTop = content.paddingTop
        val basePaddingBottom = content.paddingBottom
        content.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                basePaddingTop + bars.top,
                v.paddingRight,
                basePaddingBottom + bars.bottom,
            )
            insets
        }

        gauge = findViewById(R.id.gauge)
        powerPill = findViewById(R.id.power_pill)
        coach = findViewById(R.id.coach)
        permissionCard = findViewById(R.id.permission_card)
        permissionText = findViewById(R.id.permission_text)
        dndButton = findViewById(R.id.dnd_button)
        notifButton = findViewById(R.id.notif_button)
        batteryButton = findViewById(R.id.battery_button)
        sensBlurb = findViewById(R.id.sens_blurb)
        historyList = findViewById(R.id.history_list)
        historySummary = findViewById(R.id.history_summary)
        notificationSwitch = findViewById(R.id.notification_switch)
        notificationSettings = findViewById(R.id.notification_settings_button)
        diagnosticsToggle = findViewById(R.id.diagnostics_toggle)
        diagnosticsBody = findViewById(R.id.diagnostics_body)
        readout = findViewById(R.id.readout)

        sensPills = mapOf(
            Sensitivity.STRICT to findViewById(R.id.sens_strict),
            Sensitivity.BALANCED to findViewById(R.id.sens_balanced),
            Sensitivity.RELAXED to findViewById(R.id.sens_relaxed),
        )
        sensPills.forEach { (level, pill) ->
            pill.text = level.label
            pill.setOnClickListener {
                prefs.sensitivity = level
                // Applies to the running service without restarting it, so a change mid-session
                // does not drop the sensor registration.
                ShhhService.reload(this)
                refresh()
            }
        }

        powerPill.setOnClickListener {
            val turningOn = !prefs.enabled
            if (turningOn && !DndController.hasAccess(this)) {
                openDndAccess()
                return@setOnClickListener
            }
            prefs.enabled = turningOn
            if (turningOn) ShhhService.start(this) else ShhhService.stop(this)
            refresh()
        }

        notificationSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.showNotification) return@setOnCheckedChangeListener
            prefs.showNotification = checked
            ShhhService.reload(this)
        }

        notificationSettings.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, ShhhService.CHANNEL_ID)
            )
        }

        dndButton.setOnClickListener { openDndAccess() }
        notifButton.setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        batteryButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        diagnosticsToggle.setOnClickListener {
            diagnosticsBody.visibility =
                if (diagnosticsBody.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Test hooks used while tuning against the real device:
     *   adb shell am start -n com.shhh/.MainActivity --ez enable true
     *   adb shell am start -n com.shhh/.MainActivity --es force on
     */
    private fun handleIntent(intent: Intent) {
        if (intent.hasExtra(EXTRA_ENABLE)) {
            val on = intent.getBooleanExtra(EXTRA_ENABLE, false)
            prefs.enabled = on
            if (on) ShhhService.start(this) else ShhhService.stop(this)
        }
        intent.getStringExtra(EXTRA_FORCE)?.let { DndController.setEngaged(this, it == "on") }
    }

    private fun openDndAccess() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    override fun onResume() {
        super.onResume()
        ShhhState.observe { info -> main.post { render(info) } }
        refresh()
    }

    override fun onPause() {
        ShhhState.observe(null)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val hasDnd = DndController.hasAccess(this)
        val hasNotif = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        val on = prefs.enabled
        powerPill.text = if (on) "On" else "Off"
        powerPill.backgroundTintList =
            ColorStateList.valueOf(getColor(if (on) R.color.awake else R.color.track))
        powerPill.setTextColor(getColor(if (on) R.color.bg else R.color.ink_dim))

        dndButton.visibility = if (hasDnd) View.GONE else View.VISIBLE
        notifButton.visibility = if (hasNotif) View.GONE else View.VISIBLE
        permissionCard.visibility = if (hasDnd && hasNotif) View.GONE else View.VISIBLE
        permissionText.text =
            if (!hasDnd) "Shhh needs Do Not Disturb access before it can silence anything."
            else "Allow notifications so Shhh can keep running in the background."

        val level = prefs.sensitivity
        sensPills.forEach { (pillLevel, pill) ->
            val selected = pillLevel == level
            pill.backgroundTintList =
                ColorStateList.valueOf(getColor(if (selected) R.color.awake else R.color.track))
            pill.setTextColor(getColor(if (selected) R.color.bg else R.color.ink_dim))
        }
        sensBlurb.text = level.blurb

        notificationSwitch.isChecked = prefs.showNotification

        renderHistory()
        render(ShhhState.info)
    }

    /** Bars are scaled against the longest silence today, so the shape of the day is readable. */
    private fun renderHistory() {
        historyList.removeAllViews()
        val entries = History.today(this)

        if (entries.isEmpty()) {
            historyList.addView(TextView(this).apply {
                text = getString(R.string.history_empty)
                setTextColor(getColor(R.color.ink_dim))
                textSize = 15f
            })
            historySummary.text = ""
            return
        }

        val longest = entries.maxOf { it.durationMs }.coerceAtLeast(1L)
        val maxBarPx = (120 * resources.displayMetrics.density).toInt()

        entries.take(8).forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() }
            }

            val barWidth = max(
                (6 * resources.displayMetrics.density).toInt(),
                (maxBarPx * entry.durationMs / longest).toInt(),
            )
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    barWidth,
                    (8 * resources.displayMetrics.density).toInt(),
                )
                setBackgroundResource(R.drawable.bar)
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.quiet))
            })

            row.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = clock.format(Date(entry.startMs))
                setTextColor(getColor(R.color.ink_dim))
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 13f
                setPadding((12 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            })

            row.addView(TextView(this).apply {
                text = formatDuration(entry.durationMs)
                setTextColor(getColor(R.color.ink))
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 13f
            })

            historyList.addView(row)
        }

        val total = entries.sumOf { it.durationMs }
        val count = entries.size
        historySummary.text =
            "${count} ${if (count == 1) "silence" else "silences"} · ${formatDuration(total)} total"
    }

    private fun render(info: ShhhState.Info) {
        val s = info.sample
        val level = prefs.sensitivity

        val tilt = if (s == null) 180f else {
            Math.toDegrees(acos((-s.gravityZ / 9.81f).coerceIn(-1f, 1f).toDouble())).toFloat()
        }
        val heldFraction =
            if (s == null || s.holdTargetMs == 0L) 0f else s.heldMs.toFloat() / s.holdTargetMs
        val engaged = s?.engaged == true

        gauge.setState(tilt, heldFraction, engaged, level.thresholdDeg)

        coach.text = when {
            !DndController.hasAccess(this) -> "Grant Do Not Disturb access to begin."
            !prefs.enabled -> "Turn on to start watching."
            engaged -> "Silenced. Pick it up to stop."
            s == null -> "Starting up."
            !s.steady -> "Still tilting — let go of it."
            s.heldMs > 0 -> "Almost — hold it there."
            s.faceDown && !s.still -> "Hold still."
            else -> "Put the phone face down on a flat surface."
        }

        readout.text = buildString {
            append("service    ").append(if (info.running) "running" else "stopped").append('\n')
            append("sensor     ").append(info.sensorName.ifEmpty { "—" }).append('\n')
            append("wake-up    ").append(if (info.wakeUpSensor) "yes" else "no (screen-on backstop)")
                .append('\n')
            append("rate       ").append(String.format(Locale.US, "%.1f Hz", info.eventsPerSec))
                .append("\n\n")
            append("profile    ").append(level.label).append('\n')
            if (s == null) {
                append("no samples yet")
            } else {
                append("gravity z  ").append(String.format(Locale.US, "%+6.2f", s.gravityZ))
                    .append("   engage ≤ ").append(level.engageGz).append('\n')
                append("motion     ").append(String.format(Locale.US, "%6.3f", s.motion))
                    .append("   still < ").append(level.stillMax).append('\n')
                append("drift      ").append(String.format(Locale.US, "%6.2f", s.driftDeg))
                    .append("°  steady < ").append(level.maxDriftDeg).append("°\n")
                append("held       ").append(s.heldMs).append(" / ").append(s.holdTargetMs)
                    .append(" ms\n")
                append("proximity  ").append(
                    s.proximity?.let {
                        String.format(Locale.US, "%.1f", it) +
                            if (s.proximityNear == true) "  near" else "  far"
                    } ?: "not exposed"
                )
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000.0).roundToInt()
        return when {
            totalSeconds < 60 -> "${totalSeconds}s"
            totalSeconds < 3600 -> "${totalSeconds / 60}m ${totalSeconds % 60}s"
            else -> "${totalSeconds / 3600}h ${(totalSeconds % 3600) / 60}m"
        }
    }

    private companion object {
        const val EXTRA_ENABLE = "enable"
        const val EXTRA_FORCE = "force"
    }
}
