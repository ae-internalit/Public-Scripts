package com.doubletap.screenshot

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var actionButton: Button
    private lateinit var pauseButton: Button
    private lateinit var instructionText: TextView
    private lateinit var sensitivityGroup: RadioGroup
    private lateinit var sensitivityLabel: TextView
    private lateinit var triggerGroup: RadioGroup
    private lateinit var smartKeyboardSwitch: SwitchCompat
    private lateinit var excludedAppsButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusIcon = findViewById(R.id.statusIcon)
        statusText = findViewById(R.id.statusText)
        subtitleText = findViewById(R.id.subtitleText)
        actionButton = findViewById(R.id.actionButton)
        pauseButton = findViewById(R.id.pauseButton)
        instructionText = findViewById(R.id.instructionText)
        sensitivityGroup = findViewById(R.id.sensitivityGroup)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)
        triggerGroup = findViewById(R.id.triggerGroup)
        smartKeyboardSwitch = findViewById(R.id.smartKeyboardSwitch)
        excludedAppsButton = findViewById(R.id.excludedAppsButton)

        actionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        pauseButton.setOnClickListener {
            val service = ScreenshotService.instance ?: return@setOnClickListener
            val prefs = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
            val currentlyActive = prefs.getBoolean(ScreenshotService.KEY_ACTIVE, true)
            service.setDetectionActive(!currentlyActive)
            updateStatus()
        }

        triggerGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.triggerShake -> "shake"
                else -> "double_tap"
            }
            getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(ScreenshotService.KEY_TRIGGER_MODE, mode)
                .apply()
            ScreenshotService.instance?.setTriggerMode(mode)
            updateSensitivityVisibility(mode)
            updateSubtitle(mode)
            updateInstructions()
        }

        smartKeyboardSwitch.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(ScreenshotService.KEY_SMART_KEYBOARD, isChecked)
                .apply()
            ScreenshotService.instance?.setSmartKeyboard(isChecked)
        }

        excludedAppsButton.setOnClickListener {
            startActivity(Intent(this, ExcludedAppsActivity::class.java))
        }

        sensitivityGroup.setOnCheckedChangeListener { _, checkedId ->
            val ms = when (checkedId) {
                R.id.sensitivityFast -> 180L
                R.id.sensitivityNormal -> 250L
                R.id.sensitivitySlow -> 350L
                else -> 250L
            }
            getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong(ScreenshotService.KEY_SENSITIVITY, ms)
                .apply()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        loadSettings()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)

        val ms = prefs.getLong(ScreenshotService.KEY_SENSITIVITY, ScreenshotService.DEFAULT_SENSITIVITY)
        val sensitivityId = when {
            ms <= 180L -> R.id.sensitivityFast
            ms <= 250L -> R.id.sensitivityNormal
            else -> R.id.sensitivitySlow
        }
        findViewById<RadioButton>(sensitivityId).isChecked = true

        val mode = prefs.getString(ScreenshotService.KEY_TRIGGER_MODE, "double_tap") ?: "double_tap"
        val triggerId = when (mode) {
            "shake" -> R.id.triggerShake
            else -> R.id.triggerDoubleTap
        }
        findViewById<RadioButton>(triggerId).isChecked = true
        updateSensitivityVisibility(mode)
        updateSubtitle(mode)

        val smartKeyboard = prefs.getBoolean(ScreenshotService.KEY_SMART_KEYBOARD, true)
        smartKeyboardSwitch.isChecked = smartKeyboard

        val excludedCount = (prefs.getStringSet(ScreenshotService.KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()).size
        excludedAppsButton.text = if (excludedCount == 0) {
            getString(R.string.excluded_apps_button)
        } else {
            getString(R.string.excluded_apps_count, excludedCount)
        }
    }

    private fun updateSensitivityVisibility(mode: String) {
        val visibility = if (mode == "double_tap") View.VISIBLE else View.GONE
        sensitivityLabel.visibility = visibility
        sensitivityGroup.visibility = visibility
    }

    private fun updateSubtitle(mode: String) {
        subtitleText.text = getString(
            if (mode == "shake") R.string.app_subtitle_shake else R.string.app_subtitle
        )
    }

    private fun updateInstructions() {
        if (!isServiceEnabled()) return
        val prefs = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
        val active = prefs.getBoolean(ScreenshotService.KEY_ACTIVE, true)
        if (!active) return
        val mode = prefs.getString(ScreenshotService.KEY_TRIGGER_MODE, "double_tap") ?: "double_tap"
        instructionText.text = getString(
            if (mode == "shake") R.string.instruction_active_shake else R.string.instruction_active
        )
    }

    private fun updateStatus() {
        val serviceEnabled = isServiceEnabled()
        val prefs = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
        val detectionActive = prefs.getBoolean(ScreenshotService.KEY_ACTIVE, true)
        val mode = prefs.getString(ScreenshotService.KEY_TRIGGER_MODE, "double_tap") ?: "double_tap"

        if (serviceEnabled && detectionActive) {
            statusIcon.setImageResource(R.drawable.ic_check_circle)
            statusIcon.setColorFilter(getColor(R.color.green))
            statusText.text = getString(R.string.status_active)
            statusText.setTextColor(getColor(R.color.green))
            actionButton.text = getString(R.string.button_settings)
            instructionText.text = getString(
                if (mode == "shake") R.string.instruction_active_shake else R.string.instruction_active
            )
            pauseButton.text = getString(R.string.button_pause)
            pauseButton.isEnabled = true
        } else if (serviceEnabled && !detectionActive) {
            statusIcon.setImageResource(R.drawable.ic_pause)
            statusIcon.setColorFilter(getColor(R.color.orange))
            statusText.text = getString(R.string.status_paused)
            statusText.setTextColor(getColor(R.color.orange))
            actionButton.text = getString(R.string.button_settings)
            instructionText.text = getString(R.string.instruction_paused)
            pauseButton.text = getString(R.string.button_resume)
            pauseButton.isEnabled = true
        } else {
            statusIcon.setImageResource(R.drawable.ic_warning)
            statusIcon.setColorFilter(getColor(R.color.orange))
            statusText.text = getString(R.string.status_inactive)
            statusText.setTextColor(getColor(R.color.orange))
            actionButton.text = getString(R.string.button_enable)
            instructionText.text = getString(R.string.instruction_setup)
            pauseButton.text = getString(R.string.button_pause)
            pauseButton.isEnabled = false
        }
    }

    private fun isServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }
}
