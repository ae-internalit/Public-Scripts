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
import android.widget.EditText
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
    private lateinit var triggerGroup: RadioGroup
    private lateinit var smartKeyboardSwitch: SwitchCompat
    private lateinit var excludedAppsButton: Button
    private lateinit var vibrationGroup: RadioGroup
    private lateinit var themeGroup: RadioGroup
    private lateinit var autoRenameSwitch: SwitchCompat
    private lateinit var quickShareSwitch: SwitchCompat
    private lateinit var audioGroup: RadioGroup
    private lateinit var historyButton: Button
    private lateinit var recordButton: Button
    private lateinit var crashCard: View
    private lateinit var crashText: TextView
    private lateinit var crashCopyButton: Button
    private lateinit var crashClearButton: Button

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val AUDIO_PERMISSION_REQUEST = 1002
        private const val STARTUP_PERMISSION_REQUEST = 1003
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
        triggerGroup = findViewById(R.id.triggerGroup)
        smartKeyboardSwitch = findViewById(R.id.smartKeyboardSwitch)
        excludedAppsButton = findViewById(R.id.excludedAppsButton)
        vibrationGroup = findViewById(R.id.vibrationGroup)
        themeGroup = findViewById(R.id.themeGroup)
        autoRenameSwitch = findViewById(R.id.autoRenameSwitch)
        quickShareSwitch = findViewById(R.id.quickShareSwitch)
        audioGroup = findViewById(R.id.audioGroup)
        historyButton = findViewById(R.id.historyButton)
        recordButton = findViewById(R.id.recordButton)
        crashCard = findViewById(R.id.crashCard)
        crashText = findViewById(R.id.crashText)
        crashCopyButton = findViewById(R.id.crashCopyButton)
        crashClearButton = findViewById(R.id.crashClearButton)

        findViewById<TextView>(R.id.versionText).text =
            getString(R.string.version_format, appVersion(this))

        requestStartupPermissions()

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

        historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        recordButton.setOnClickListener {
            if (ScreenRecordService.isRunning) {
                ScreenRecordService.stop(this)
                recordButton.text = getString(R.string.record_start)
            } else {
                ScreenRecordActivity.start(this)
            }
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
                R.id.triggerA11y -> ScreenshotService.MODE_A11Y_BUTTON
                else -> ScreenshotService.MODE_DOUBLE_TAP
            }
            getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(ScreenshotService.KEY_TRIGGER_MODE, mode)
                .apply()
            ScreenshotService.instance?.setTriggerMode(mode)
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

        audioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.audioMic -> ScreenRecorder.AUDIO_MIC
                R.id.audioBoth -> ScreenRecorder.AUDIO_BOTH
                R.id.audioNone -> ScreenRecorder.AUDIO_NONE
                else -> ScreenRecorder.AUDIO_DEVICE
            }

            // Device capture uses AudioRecord too, so it needs RECORD_AUDIO as well.
            if (mode != ScreenRecorder.AUDIO_NONE && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST
                )
            }

            val needsDevice =
                mode == ScreenRecorder.AUDIO_DEVICE || mode == ScreenRecorder.AUDIO_BOTH
            if (needsDevice && !ScreenRecorder.deviceAudioSupported()) {
                Toast.makeText(this, R.string.record_audio_unsupported, Toast.LENGTH_LONG).show()
            }

            getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(ScreenshotService.KEY_AUDIO_MODE, mode)
                .apply()
        }

        quickShareSwitch.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(ScreenshotService.KEY_QUICK_SHARE, isChecked)
                .apply()
            ScreenshotService.instance?.setQuickShare(isChecked)
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

    /**
     * Asked together in one prompt. Recording needs RECORD_AUDIO even for
     * device audio, and without it Android refuses to start the recording
     * service at all rather than merely dropping the sound.
     */
    private fun requestStartupPermissions() {
        val needed = mutableListOf<String>()

        if (!hasMediaPermission()) needed += mediaPermission()

        val audioMode = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
            .getString(ScreenshotService.KEY_AUDIO_MODE, ScreenRecorder.AUDIO_DEVICE)
            ?: ScreenRecorder.AUDIO_DEVICE
        if (audioMode != ScreenRecorder.AUDIO_NONE &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.RECORD_AUDIO
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, needed.toTypedArray(), STARTUP_PERMISSION_REQUEST
            )
        }
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
        } else if (requestCode == STARTUP_PERMISSION_REQUEST) {
            loadSettings()
        } else if (requestCode == AUDIO_PERMISSION_REQUEST) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                findViewById<RadioButton>(R.id.audioNone).isChecked = true
                Toast.makeText(this, R.string.record_audio_needed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Shown as a card rather than a dialog: a dialog is easy to dismiss by
     * accident, and the report is then gone for good.
     */
    private fun showLastCrashIfAny() {
        val prefs = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
        val crash = prefs.getString(ScreenshotService.KEY_LAST_CRASH, null)

        if (crash.isNullOrEmpty()) {
            crashCard.visibility = View.GONE
            return
        }

        crashCard.visibility = View.VISIBLE
        crashText.text = crash

        crashCopyButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash", crash))
            Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_LONG).show()
        }

        crashClearButton.setOnClickListener {
            prefs.edit().remove(ScreenshotService.KEY_LAST_CRASH).apply()
            crashCard.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        showLastCrashIfAny()
        updateStatus()
        loadSettings()
        recordButton.text = if (ScreenRecordService.isRunning) {
            getString(R.string.record_stop)
        } else {
            getString(R.string.record_start)
        }
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

        val mode = prefs.getString(ScreenshotService.KEY_TRIGGER_MODE, ScreenshotService.MODE_DOUBLE_TAP)
            ?: ScreenshotService.MODE_DOUBLE_TAP
        val triggerId = when (mode) {
            ScreenshotService.MODE_A11Y_BUTTON -> R.id.triggerA11y
            else -> R.id.triggerDoubleTap
        }
        findViewById<RadioButton>(triggerId).isChecked = true

        updateSubtitle(mode)

        val smartKeyboard = prefs.getBoolean(ScreenshotService.KEY_SMART_KEYBOARD, true)
        smartKeyboardSwitch.isChecked = smartKeyboard

        val quickShare = prefs.getBoolean(ScreenshotService.KEY_QUICK_SHARE, true)
        quickShareSwitch.isChecked = quickShare

        val audioMode = prefs.getString(
            ScreenshotService.KEY_AUDIO_MODE, ScreenRecorder.AUDIO_DEVICE
        ) ?: ScreenRecorder.AUDIO_DEVICE
        findViewById<RadioButton>(
            when (audioMode) {
                ScreenRecorder.AUDIO_MIC -> R.id.audioMic
                ScreenRecorder.AUDIO_BOTH -> R.id.audioBoth
                ScreenRecorder.AUDIO_NONE -> R.id.audioNone
                else -> R.id.audioDevice
            }
        ).isChecked = true

        val autoRename = prefs.getBoolean(ScreenshotService.KEY_AUTO_RENAME, false)
        autoRenameSwitch.isChecked = autoRename

        val excludedCount = (prefs.getStringSet(ScreenshotService.KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()).size
        excludedAppsButton.text = if (excludedCount == 0) {
            getString(R.string.excluded_apps_button)
        } else {
            getString(R.string.excluded_apps_count, excludedCount)
        }

        val vibPattern = prefs.getString(ScreenshotService.KEY_VIBRATION_PATTERN, "short") ?: "short"
        val vibId = when (vibPattern) {
            "none" -> R.id.vibrationNone
            "long" -> R.id.vibrationLong
            "double" -> R.id.vibrationDouble
            else -> R.id.vibrationShort
        }
        findViewById<RadioButton>(vibId).isChecked = true
    }

    private fun updateSubtitle(mode: String) {
        subtitleText.text = getString(
            if (mode == ScreenshotService.MODE_A11Y_BUTTON) R.string.app_subtitle_a11y
            else R.string.app_subtitle
        )
    }

    private fun instructionFor(mode: String): String =
        if (mode == ScreenshotService.MODE_A11Y_BUTTON) {
            getString(R.string.instruction_active_a11y)
        } else {
            getString(R.string.instruction_active)
        }

    private fun updateInstructions() {
        if (!isServiceEnabled()) return
        val prefs = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
        val active = prefs.getBoolean(ScreenshotService.KEY_ACTIVE, true)
        if (!active) return
        val mode = prefs.getString(ScreenshotService.KEY_TRIGGER_MODE, ScreenshotService.MODE_DOUBLE_TAP)
            ?: ScreenshotService.MODE_DOUBLE_TAP
        instructionText.text = instructionFor(mode)
    }

    private fun updateStatus() {
        val serviceEnabled = isServiceEnabled()
        val prefs = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
        val detectionActive = prefs.getBoolean(ScreenshotService.KEY_ACTIVE, true)
        val mode = prefs.getString(ScreenshotService.KEY_TRIGGER_MODE, ScreenshotService.MODE_DOUBLE_TAP)
            ?: ScreenshotService.MODE_DOUBLE_TAP

        if (serviceEnabled && detectionActive) {
            statusIcon.setImageResource(R.drawable.ic_check_circle)
            statusIcon.setColorFilter(getColor(R.color.green))
            statusText.text = getString(R.string.status_active)
            statusText.setTextColor(getColor(R.color.green))
            actionButton.text = getString(R.string.button_settings)
            instructionText.text = instructionFor(mode)
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
