package com.doubletap.screenshot

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var pauseButton: Button
    private lateinit var instructionText: TextView
    private lateinit var sensitivityGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusIcon = findViewById(R.id.statusIcon)
        statusText = findViewById(R.id.statusText)
        actionButton = findViewById(R.id.actionButton)
        pauseButton = findViewById(R.id.pauseButton)
        instructionText = findViewById(R.id.instructionText)
        sensitivityGroup = findViewById(R.id.sensitivityGroup)

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
        loadSensitivity()
    }

    private fun loadSensitivity() {
        val ms = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
            .getLong(ScreenshotService.KEY_SENSITIVITY, ScreenshotService.DEFAULT_SENSITIVITY)
        val id = when {
            ms <= 180L -> R.id.sensitivityFast
            ms <= 250L -> R.id.sensitivityNormal
            else -> R.id.sensitivitySlow
        }
        findViewById<RadioButton>(id).isChecked = true
    }

    private fun updateStatus() {
        val serviceEnabled = isServiceEnabled()
        val prefs = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)
        val detectionActive = prefs.getBoolean(ScreenshotService.KEY_ACTIVE, true)

        if (serviceEnabled && detectionActive) {
            statusIcon.setImageResource(R.drawable.ic_check_circle)
            statusIcon.setColorFilter(getColor(R.color.green))
            statusText.text = getString(R.string.status_active)
            statusText.setTextColor(getColor(R.color.green))
            actionButton.text = getString(R.string.button_settings)
            instructionText.text = getString(R.string.instruction_active)
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
