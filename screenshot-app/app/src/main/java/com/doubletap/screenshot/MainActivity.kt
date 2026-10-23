package com.doubletap.screenshot

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

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
    private lateinit var delayGroup: RadioGroup
    private lateinit var vibrationGroup: RadioGroup
    private lateinit var screenshotCount: TextView
    private lateinit var themeGroup: RadioGroup
    private lateinit var tapCountGroup: RadioGroup
    private lateinit var tapCountLabel: TextView
    private lateinit var autoRenameSwitch: SwitchCompat

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
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
        delayGroup = findViewById(R.id.delayGroup)
        vibrationGroup = findViewById(R.id.vibrationGroup)
        screenshotCount = findViewById(R.id.screenshotCount)
        themeGroup = findViewById(R.id.themeGroup)
        tapCountGroup = findViewById(R.id.tapCountGroup)
        tapCountLabel = findViewById(R.id.tapCountLabel)
        autoRenameSwitch = findViewById(R.id.autoRenameSwitch)

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

        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.themeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.themeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            val themeKey = when (checkedId) {
                R.id.themeLight -> "light"
                R.id.themeDark -> "dark"
                else -> "system"
            }
            getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(ScreenshotService.KEY_THEME, themeKey)
                .apply()
            AppCompatDelegate.setDefaultNightMode(mode)
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
            updateTapCountVisibility(mode)
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

        autoRenameSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasLocationPermission()) {
                requestLocationPermission()
            }
            getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(ScreenshotService.KEY_AUTO_RENAME, isChecked)
                .apply()
            ScreenshotService.instance?.setAutoRename(isChecked)
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

        tapCountGroup.setOnCheckedChangeListener { _, checkedId ->
            val count = when (checkedId) {
                R.id.tapCountTriple -> 3
                else -> 2
            }
            getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(ScreenshotService.KEY_TAP_COUNT, count)
                .apply()
            ScreenshotService.instance?.setTapCount(count)
            updateInstructions()
        }

        delayGroup.setOnCheckedChangeListener { _, checkedId ->
            val seconds = when (checkedId) {
                R.id.delay3s -> 3
                R.id.delay5s -> 5
                else -> 0
            }
            getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(ScreenshotService.KEY_DELAY_SECONDS, seconds)
                .apply()
            ScreenshotService.instance?.setDelaySeconds(seconds)
        }

        vibrationGroup.setOnCheckedChangeListener { _, checkedId ->
            val pattern = when (checkedId) {
                R.id.vibrationNone -> "none"
                R.id.vibrationLong -> "long"
                R.id.vibrationDouble -> "double"
                else -> "short"
            }
            getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(ScreenshotService.KEY_VIBRATION_PATTERN, pattern)
                .apply()
            ScreenshotService.instance?.setVibrationPattern(pattern)
        }
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
        val theme = prefs.getString(ScreenshotService.KEY_THEME, "system") ?: "system"
        val mode = when (theme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            LOCATION_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        loadSettings()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)

        val themeKey = prefs.getString(ScreenshotService.KEY_THEME, "system") ?: "system"
        val themeId = when (themeKey) {
            "light" -> R.id.themeLight
            "dark" -> R.id.themeDark
            else -> R.id.themeSystem
        }
        findViewById<RadioButton>(themeId).isChecked = true

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
        updateTapCountVisibility(mode)
        updateSubtitle(mode)

        val smartKeyboard = prefs.getBoolean(ScreenshotService.KEY_SMART_KEYBOARD, true)
        smartKeyboardSwitch.isChecked = smartKeyboard

        val autoRename = prefs.getBoolean(ScreenshotService.KEY_AUTO_RENAME, false)
        autoRenameSwitch.isChecked = autoRename

        val excludedCount = (prefs.getStringSet(ScreenshotService.KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()).size
        excludedAppsButton.text = if (excludedCount == 0) {
            getString(R.string.excluded_apps_button)
        } else {
            getString(R.string.excluded_apps_count, excludedCount)
        }

        val tapCount = prefs.getInt(ScreenshotService.KEY_TAP_COUNT, 2)
        val tapCountId = when (tapCount) {
            3 -> R.id.tapCountTriple
            else -> R.id.tapCountDouble
        }
        findViewById<RadioButton>(tapCountId).isChecked = true

        val delaySeconds = prefs.getInt(ScreenshotService.KEY_DELAY_SECONDS, 0)
        val delayId = when (delaySeconds) {
            3 -> R.id.delay3s
            5 -> R.id.delay5s
            else -> R.id.delayNone
        }
        findViewById<RadioButton>(delayId).isChecked = true

        val vibPattern = prefs.getString(ScreenshotService.KEY_VIBRATION_PATTERN, "short") ?: "short"
        val vibId = when (vibPattern) {
            "none" -> R.id.vibrationNone
            "long" -> R.id.vibrationLong
            "double" -> R.id.vibrationDouble
            else -> R.id.vibrationShort
        }
        findViewById<RadioButton>(vibId).isChecked = true
    }

    private fun updateSensitivityVisibility(mode: String) {
        val visibility = if (mode == "double_tap") View.VISIBLE else View.GONE
        sensitivityLabel.visibility = visibility
        sensitivityGroup.visibility = visibility
    }

    private fun updateTapCountVisibility(mode: String) {
        val visibility = if (mode == "double_tap") View.VISIBLE else View.GONE
        tapCountLabel.visibility = visibility
        tapCountGroup.visibility = visibility
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
        val tapCount = prefs.getInt(ScreenshotService.KEY_TAP_COUNT, 2)
        instructionText.text = when {
            mode == "shake" -> getString(R.string.instruction_active_shake)
            tapCount == 3 -> getString(R.string.instruction_active_triple)
            else -> getString(R.string.instruction_active)
        }
    }

    private fun updateStatus() {
        val serviceEnabled = isServiceEnabled()
        val prefs = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
        val detectionActive = prefs.getBoolean(ScreenshotService.KEY_ACTIVE, true)
        val mode = prefs.getString(ScreenshotService.KEY_TRIGGER_MODE, "double_tap") ?: "double_tap"
        val tapCount = prefs.getInt(ScreenshotService.KEY_TAP_COUNT, 2)

        val count = prefs.getInt(ScreenshotService.KEY_SCREENSHOT_COUNT, 0)
        screenshotCount.text = if (count == 0) {
            getString(R.string.screenshot_count_zero)
        } else {
            getString(R.string.screenshot_count_format, count)
        }

        if (serviceEnabled && detectionActive) {
            statusIcon.setImageResource(R.drawable.ic_check_circle)
            statusIcon.setColorFilter(getColor(R.color.green))
            statusText.text = getString(R.string.status_active)
            statusText.setTextColor(getColor(R.color.green))
            actionButton.text = getString(R.string.button_settings)
            instructionText.text = when {
                mode == "shake" -> getString(R.string.instruction_active_shake)
                tapCount == 3 -> getString(R.string.instruction_active_triple)
                else -> getString(R.string.instruction_active)
            }
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
