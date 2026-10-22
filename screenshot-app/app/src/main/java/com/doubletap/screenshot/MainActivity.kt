package com.doubletap.screenshot

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var instructionText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusIcon = findViewById(R.id.statusIcon)
        statusText = findViewById(R.id.statusText)
        actionButton = findViewById(R.id.actionButton)
        instructionText = findViewById(R.id.instructionText)

        actionButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        if (isServiceEnabled()) {
            statusIcon.setImageResource(R.drawable.ic_check_circle)
            statusIcon.setColorFilter(getColor(R.color.green))
            statusText.text = getString(R.string.status_active)
            statusText.setTextColor(getColor(R.color.green))
            actionButton.text = getString(R.string.button_settings)
            instructionText.text = getString(R.string.instruction_active)
        } else {
            statusIcon.setImageResource(R.drawable.ic_warning)
            statusIcon.setColorFilter(getColor(R.color.orange))
            statusText.text = getString(R.string.status_inactive)
            statusText.setTextColor(getColor(R.color.orange))
            actionButton.text = getString(R.string.button_enable)
            instructionText.text = getString(R.string.instruction_setup)
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
