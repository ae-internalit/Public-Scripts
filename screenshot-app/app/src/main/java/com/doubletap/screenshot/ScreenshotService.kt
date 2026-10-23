package com.doubletap.screenshot

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenshotService : AccessibilityService(), SensorEventListener {

    private var touchWindow: View? = null
    private var windowManager: WindowManager? = null
    private var lastTouchTime = 0L
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastScreenshotTime = 0L
    private var recentTouchCount = 0
    private var burstWindowStart = 0L

    private var tapSequenceCount = 0
    private var tapSequenceStart = 0L
    private var tapSequenceX = 0f
    private var tapSequenceY = 0f

    private var firstTouchX = 0f
    private var firstTouchY = 0f
    private var firstTouchTime = 0L

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
    private val handler = Handler(Looper.getMainLooper())
    private var screenshotObserver: ContentObserver? = null

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

    private val requiredTapCount: Int
        get() = prefs.getInt(KEY_TAP_COUNT, 2)

    private val autoRenameEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RENAME, false)

    private val quickShareEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUICK_SHARE, true)

    private val watermarkEnabled: Boolean
        get() = prefs.getBoolean(KEY_WATERMARK, false)

    private val watermarkText: String
        get() = prefs.getString(KEY_WATERMARK_TEXT, "") ?: ""

    private var quickSharePopup: QuickSharePopup? = null

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
        const val KEY_DELAY_SECONDS = "delay_seconds"
        const val KEY_VIBRATION_PATTERN = "vibration_pattern"
        const val KEY_SCREENSHOT_COUNT = "screenshot_count"
        const val KEY_TAP_COUNT = "tap_count"
        const val KEY_AUTO_RENAME = "auto_rename"
        const val KEY_QUICK_SHARE = "quick_share"
        const val KEY_THEME = "theme_mode"
        const val KEY_WATERMARK = "watermark_enabled"
        const val KEY_WATERMARK_TEXT = "watermark_text"
        const val ACTION_TAKE_SCREENSHOT = "com.doubletap.screenshot.TAKE_SCREENSHOT"
        private const val MIN_TAP_GAP = 40L
        private const val SWIPE_VELOCITY_THRESHOLD = 0.8f
        private const val COOLDOWN_MS = 2000L
        private const val NOTIFICATION_CHANNEL_ID = "screenshot_service"
        private const val NOTIFICATION_ID = 1
        private const val MAX_TAP_DISTANCE = 100f
        private const val BURST_WINDOW_MS = 600L
        private const val MAX_TOUCHES_IN_BURST = 3
        private const val SHAKE_THRESHOLD = 12f
        private const val SENSOR_UPDATE_INTERVAL = 100L
        private const val TAP_SEQUENCE_TIMEOUT = 500L

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

        val recordIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, ScreenRecordActivity::class.java),
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
            .addAction(
                Notification.Action.Builder(
                    null, "Record", recordIntent
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
                    onScreenTouch(event.rawX, event.rawY)
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

    private fun resetTapState() {
        tapSequenceCount = 0
        tapSequenceStart = 0L
        tapSequenceX = 0f
        tapSequenceY = 0f
    }

    private fun isSwipeGesture(x: Float, y: Float, now: Long): Boolean {
        if (firstTouchTime == 0L) {
            firstTouchX = x
            firstTouchY = y
            firstTouchTime = now
            return false
        }

        val dx = x - firstTouchX
        val dy = y - firstTouchY
        val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val elapsed = (now - firstTouchTime).coerceAtLeast(1L)
        val velocity = distance / elapsed

        firstTouchX = x
        firstTouchY = y
        firstTouchTime = now

        return velocity > SWIPE_VELOCITY_THRESHOLD
    }

    private fun onScreenTouch(x: Float, y: Float) {
        if (!isActive) return
        if (triggerMode != MODE_DOUBLE_TAP) return
        if (smartKeyboardEnabled && checkKeyboardVisible()) return
        if (currentForegroundPackage in excludedApps) return

        val now = System.currentTimeMillis()
        val required = requiredTapCount

        if (now - lastScreenshotTime < COOLDOWN_MS) {
            resetTapState()
            return
        }

        if (isSwipeGesture(x, y, now)) {
            resetTapState()
            return
        }

        if (now - burstWindowStart > BURST_WINDOW_MS) {
            recentTouchCount = 1
            burstWindowStart = now
        } else {
            recentTouchCount++
        }

        if (recentTouchCount > MAX_TOUCHES_IN_BURST) {
            resetTapState()
            return
        }

        if (tapSequenceCount == 0 || now - tapSequenceStart > TAP_SEQUENCE_TIMEOUT) {
            tapSequenceCount = 1
            tapSequenceStart = now
            tapSequenceX = x
            tapSequenceY = y
            return
        }

        val gap = now - tapSequenceStart
        if (gap < MIN_TAP_GAP) return

        val dx = x - tapSequenceX
        val dy = y - tapSequenceY
        val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (distance > MAX_TAP_DISTANCE) {
            tapSequenceCount = 1
            tapSequenceStart = now
            tapSequenceX = x
            tapSequenceY = y
            return
        }

        tapSequenceCount++

        if (tapSequenceCount >= required) {
            resetTapState()
            lastScreenshotTime = now
            recentTouchCount = 0
            takeScreenshot()
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
        val delay = prefs.getInt(KEY_DELAY_SECONDS, 0)
        if (delay > 0) {
            startCountdown(delay)
        } else {
            performScreenshotNow()
        }
    }

    private fun startCountdown(seconds: Int) {
        for (i in seconds downTo 1) {
            handler.postDelayed({
                Toast.makeText(this, getString(R.string.delay_countdown, i), Toast.LENGTH_SHORT).show()
            }, ((seconds - i) * 1000).toLong())
        }
        handler.postDelayed({ performScreenshotNow() }, (seconds * 1000).toLong())
    }

    private fun performScreenshotNow() {
        val shouldRename = autoRenameEnabled
        val shouldWatermark = watermarkEnabled && watermarkText.isNotEmpty()
        if (shouldRename || shouldWatermark) {
            registerScreenshotObserver(shouldRename, shouldWatermark)
        }

        val showQuickShare = quickShareEnabled
        if (showQuickShare) {
            quickSharePopup?.dismiss()
            val popup = QuickSharePopup(this)
            quickSharePopup = popup
            popup.listenForScreenshot()
        }

        val success = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        if (success) {
            vibrate()
            val count = prefs.getInt(KEY_SCREENSHOT_COUNT, 0) + 1
            prefs.edit().putInt(KEY_SCREENSHOT_COUNT, count).apply()
        } else {
            if (shouldRename || shouldWatermark) {
                unregisterScreenshotObserver()
            }
            quickSharePopup?.dismiss()
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerScreenshotObserver(rename: Boolean = true, watermark: Boolean = false) {
        unregisterScreenshotObserver()

        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                uri?.let {
                    if (rename) renameScreenshot(it)
                    if (watermark) applyWatermark(it)
                }
                unregisterScreenshotObserver()
            }
        }
        screenshotObserver = observer

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )

        handler.postDelayed({ unregisterScreenshotObserver() }, 5000)
    }

    private fun unregisterScreenshotObserver() {
        screenshotObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
        screenshotObserver = null
    }

    private fun renameScreenshot(uri: Uri) {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            val dateStr = dateFormat.format(Date())
            val appName = currentForegroundPackage?.let { pkg ->
                try {
                    val appInfo = packageManager.getApplicationInfo(pkg, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                        .replace("[^a-zA-Z0-9]".toRegex(), "_")
                } catch (_: Exception) { null }
            } ?: "Unknown"

            val locationStr = getLocationString()
            val newName = buildString {
                append("Screenshot_${dateStr}_${appName}")
                if (locationStr.isNotEmpty()) {
                    append("_$locationStr")
                }
            }

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, newName)
            }
            contentResolver.update(uri, values, null, null)
        } catch (_: Exception) {}
    }

    private fun applyWatermark(uri: Uri) {
        try {
            val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            } ?: return

            val result = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(result)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(128, 255, 255, 255)
                textSize = result.width / 20f
                isAntiAlias = true
                setShadowLayer(4f, 2f, 2f, android.graphics.Color.argb(128, 0, 0, 0))
            }

            val text = watermarkText
            val textWidth = paint.measureText(text)
            val x = result.width - textWidth - 20f
            val y = result.height - 30f
            canvas.drawText(text, x, y, paint)

            contentResolver.openOutputStream(uri, "w")?.use { out ->
                result.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            result.recycle()
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun getLocationString(): String {
        try {
            val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val location = locationManager.getLastKnownLocation(
                android.location.LocationManager.NETWORK_PROVIDER
            ) ?: locationManager.getLastKnownLocation(
                android.location.LocationManager.GPS_PROVIDER
            ) ?: return ""

            val geocoder = Geocoder(this, Locale.getDefault())
            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val parts = mutableListOf<String>()
                address.locality?.let { parts.add(it) }
                address.countryCode?.let { parts.add(it) }
                return parts.joinToString("_").replace("[^a-zA-Z0-9_]".toRegex(), "")
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun vibrate() {
        val pattern = prefs.getString(KEY_VIBRATION_PATTERN, "short") ?: "short"
        if (pattern == "none") return

        val effect = when (pattern) {
            "long" -> VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
            "double" -> VibrationEffect.createWaveform(longArrayOf(0, 50, 80, 50), -1)
            else -> VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(effect)
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

    fun setDelaySeconds(seconds: Int) {
        prefs.edit().putInt(KEY_DELAY_SECONDS, seconds).apply()
    }

    fun setVibrationPattern(pattern: String) {
        prefs.edit().putString(KEY_VIBRATION_PATTERN, pattern).apply()
    }

    fun setTapCount(count: Int) {
        prefs.edit().putInt(KEY_TAP_COUNT, count).apply()
    }

    fun setAutoRename(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RENAME, enabled).apply()
    }

    fun setQuickShare(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUICK_SHARE, enabled).apply()
    }

    fun setWatermark(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WATERMARK, enabled).apply()
    }

    fun setWatermarkText(text: String) {
        prefs.edit().putString(KEY_WATERMARK_TEXT, text).apply()
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
        unregisterScreenshotObserver()
        quickSharePopup?.dismiss()
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
