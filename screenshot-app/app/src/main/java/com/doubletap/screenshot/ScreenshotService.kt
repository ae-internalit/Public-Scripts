package com.doubletap.screenshot

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

class ScreenshotService : AccessibilityService(), SensorEventListener {

    private var touchWindow: View? = null
    private var windowManager: WindowManager? = null
    private var lastTouchTime = 0L
    private var lastScreenshotTime = 0L

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastShakeTime = 0L
    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = 0f
    private var lastSensorUpdate = 0L
    private var isFirstSensorEvent = true

    private var isKeyboardVisible = false
    private var currentForegroundPackage: String? = null

    private val screenshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TAKE_SCREENSHOT) {
                takeScreenshot()
            }
        }
    }

    private val prefs: SharedPreferences
        get() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private val sensitivity: Long
        get() = prefs.getLong(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)

    private val isActive: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, true)

    private val triggerMode: String
        get() = prefs.getString(KEY_TRIGGER_MODE, MODE_DOUBLE_TAP) ?: MODE_DOUBLE_TAP

    private val smartKeyboardEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMART_KEYBOARD, true)

    private val excludedApps: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()

    companion object {
        const val PREFS_NAME = "screenshot_prefs"
        const val KEY_SENSITIVITY = "sensitivity_ms"
        const val KEY_ACTIVE = "detection_active"
        const val KEY_TRIGGER_MODE = "trigger_mode"
        const val KEY_SMART_KEYBOARD = "smart_keyboard"
        const val DEFAULT_SENSITIVITY = 250L
        const val MODE_DOUBLE_TAP = "double_tap"
        const val MODE_SHAKE = "shake"
        const val KEY_EXCLUDED_APPS = "excluded_apps"
        const val ACTION_TAKE_SCREENSHOT = "com.doubletap.screenshot.TAKE_SCREENSHOT"
        private const val MIN_TAP_GAP = 40L
        private const val COOLDOWN_MS = 2000L
        private const val NOTIFICATION_CHANNEL_ID = "screenshot_service"
        private const val NOTIFICATION_ID = 1
        private const val SHAKE_THRESHOLD = 12f
        private const val SENSOR_UPDATE_INTERVAL = 100L

        var instance: ScreenshotService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerScreenshotReceiver()
        createTouchDetector()
        setupShakeDetector()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Screenshot Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification for the screenshot service"
            setShowBadge(false)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val screenshotIntent = Intent(ACTION_TAKE_SCREENSHOT).apply {
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, screenshotIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Screenshot Ready")
            .setContentText("Tap to take a screenshot")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, "Screenshot", pendingIntent
                ).build()
            )
            .build()
    }

    private fun registerScreenshotReceiver() {
        val filter = IntentFilter(ACTION_TAKE_SCREENSHOT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenshotReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenshotReceiver, filter)
        }
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

    private fun setupShakeDetector() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun checkKeyboardVisible(): Boolean {
        try {
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun onScreenTouch() {
        if (!isActive) return
        if (triggerMode != MODE_DOUBLE_TAP) return
        if (smartKeyboardEnabled && isKeyboardVisible) return
        if (currentForegroundPackage in excludedApps) return

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

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        if (!isActive) return
        if (triggerMode != MODE_SHAKE) return
        if (currentForegroundPackage in excludedApps) return

        val now = System.currentTimeMillis()
        if (now - lastSensorUpdate < SENSOR_UPDATE_INTERVAL) return
        lastSensorUpdate = now

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (isFirstSensorEvent) {
            lastAccelX = x
            lastAccelY = y
            lastAccelZ = z
            isFirstSensorEvent = false
            return
        }

        val deltaX = x - lastAccelX
        val deltaY = y - lastAccelY
        val deltaZ = z - lastAccelZ

        lastAccelX = x
        lastAccelY = y
        lastAccelZ = z

        val acceleration = Math.sqrt(
            (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).toDouble()
        ).toFloat()

        if (acceleration > SHAKE_THRESHOLD) {
            if (now - lastShakeTime > COOLDOWN_MS && now - lastScreenshotTime > COOLDOWN_MS) {
                lastShakeTime = now
                lastScreenshotTime = now
                takeScreenshot()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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

    fun setTriggerMode(mode: String) {
        prefs.edit().putString(KEY_TRIGGER_MODE, mode).apply()
    }

    fun setSmartKeyboard(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMART_KEYBOARD, enabled).apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { pkg ->
                if (pkg != "com.android.systemui") {
                    currentForegroundPackage = pkg
                }
            }
            isKeyboardVisible = checkKeyboardVisible()
        } else if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            isKeyboardVisible = checkKeyboardVisible()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        sensorManager?.unregisterListener(this)
        try {
            unregisterReceiver(screenshotReceiver)
        } catch (_: Exception) {}
        touchWindow?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}
