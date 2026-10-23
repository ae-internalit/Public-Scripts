package com.doubletap.screenshot

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class ScreenshotService : AccessibilityService() {

    private var touchWindow: View? = null
    private var windowManager: WindowManager? = null
    private var lastTouchTime = 0L
    private var lastScreenshotTime = 0L

    private val prefs: SharedPreferences
        get() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private val sensitivity: Long
        get() = prefs.getLong(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)

    private val isActive: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, true)

    companion object {
        const val PREFS_NAME = "screenshot_prefs"
        const val KEY_SENSITIVITY = "sensitivity_ms"
        const val KEY_ACTIVE = "detection_active"
        const val DEFAULT_SENSITIVITY = 250L
        private const val MIN_TAP_GAP = 40L
        private const val COOLDOWN_MS = 2000L

        var instance: ScreenshotService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        createTouchDetector()
    }

    private fun createTouchDetector() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        touchWindow = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    onScreenTouch()
                }
                false
            }
        }

        windowManager?.addView(touchWindow, params)
    }

    private fun onScreenTouch() {
        if (!isActive) return

        val now = System.currentTimeMillis()

        if (now - lastScreenshotTime < COOLDOWN_MS) {
            lastTouchTime = 0
            return
        }

        val gap = now - lastTouchTime

        if (lastTouchTime > 0 && gap in MIN_TAP_GAP..sensitivity) {
            lastTouchTime = 0
            lastScreenshotTime = now
            takeScreenshot()
        } else {
            lastTouchTime = now
        }
    }

    private fun takeScreenshot() {
        val success = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        if (success) {
            vibrate()
        } else {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(
                VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }

    fun setDetectionActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_ACTIVE, active).apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        touchWindow?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}
