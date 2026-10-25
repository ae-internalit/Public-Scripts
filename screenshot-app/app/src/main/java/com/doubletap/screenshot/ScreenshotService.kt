package com.doubletap.screenshot

import android.accessibilityservice.AccessibilityButtonController
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
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenshotService : AccessibilityService() {

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

    private var lastTextChangeTime = 0L
    private var currentForegroundPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var screenshotObserver: ContentObserver? = null
    private var accessibilityButtonCallback:
            AccessibilityButtonController.AccessibilityButtonCallback? = null
    private var scrollCapture: ScrollCapture? = null

    private val screenshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_TAKE_SCREENSHOT -> takeScreenshot()
                ACTION_SCROLL_CAPTURE -> startScrollCapture()
            }
        }
    }

    private val prefs: SharedPreferences
        get() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private val isActive: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, true)

    private val triggerMode: String
        get() = prefs.getString(KEY_TRIGGER_MODE, MODE_DOUBLE_TAP) ?: MODE_DOUBLE_TAP

    private val smartKeyboardEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMART_KEYBOARD, true)

    private val excludedApps: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()

    private val autoRenameEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RENAME, false)

    private val quickShareEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUICK_SHARE, true)

    private var quickSharePopup: QuickSharePopup? = null

    companion object {
        const val PREFS_NAME = "screenshot_prefs"
        const val KEY_ACTIVE = "detection_active"
        const val KEY_TRIGGER_MODE = "trigger_mode"
        const val KEY_SMART_KEYBOARD = "smart_keyboard"
        const val MODE_DOUBLE_TAP = "double_tap"
        const val MODE_A11Y_BUTTON = "a11y_button"
        const val KEY_EXCLUDED_APPS = "excluded_apps"
        const val KEY_VIBRATION_PATTERN = "vibration_pattern"
        const val KEY_AUTO_RENAME = "auto_rename"
        const val KEY_QUICK_SHARE = "quick_share"
        const val KEY_THEME = "theme_mode"
        const val KEY_LAST_CRASH = "last_crash"
        const val KEY_LAST_CRASH_VERSION = "last_crash_version"
        const val KEY_AUDIO_MODE = "record_audio_mode"
        const val ACTION_TAKE_SCREENSHOT = "com.doubletap.screenshot.TAKE_SCREENSHOT"
        const val ACTION_SCROLL_CAPTURE = "com.doubletap.screenshot.SCROLL_CAPTURE"

        /** Long enough for the notification shade to close and the app to redraw. */
        private const val SHADE_CLOSE_MS = 600L
        private const val MIN_TAP_GAP = 40L
        private const val SWIPE_VELOCITY_THRESHOLD = 0.8f
        private const val COOLDOWN_MS = 2000L
        private const val NOTIFICATION_CHANNEL_ID = "screenshot_service"
        private const val NOTIFICATION_ID = 1
        private const val MAX_TAP_DISTANCE = 100f
        private const val BURST_WINDOW_MS = 600L
        private const val MAX_TOUCHES_IN_BURST = 3
        private const val TYPING_WINDOW_MS = 2000L
        private const val TAP_SEQUENCE_TIMEOUT = 500L
        private const val CORNER_DP = 160f
        private const val TAPS_TO_CAPTURE = 2
        private const val SYSTEM_GESTURE_MARGIN_DP = 48f

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
        setupAccessibilityButton()
    }

    /**
     * The system draws and positions this button itself, so it cannot be
     * confused with a navigation swipe or an app's own gesture.
     */
    private fun setupAccessibilityButton() {
        try {
            accessibilityButtonCallback =
                object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) {
                        try {
                            if (!isActive) return
                            if (currentForegroundPackage in excludedApps) return
                            val now = System.currentTimeMillis()
                            if (now - lastScreenshotTime < COOLDOWN_MS) return
                            lastScreenshotTime = now
                            takeScreenshot()
                        } catch (e: Exception) {
                            recordError("accessibility button", e)
                            Toast.makeText(
                                this@ScreenshotService,
                                "Screenshot failed: $e",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            accessibilityButtonCallback?.let {
                accessibilityButtonController.registerAccessibilityButtonCallback(it)
            }
        } catch (_: Exception) {}
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

        val scrollIntent = PendingIntent.getBroadcast(
            this, 3,
            Intent(ACTION_SCROLL_CAPTURE).apply { setPackage(packageName) },
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
                    null, "Scroll Capture", scrollIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null, "Record", recordIntent
                ).build()
            )
            .build()
    }

    private fun startScrollCapture() {
        if (scrollCapture != null) return

        val capture = ScrollCapture(this)
        if (!capture.isSupported()) {
            Toast.makeText(this, R.string.scroll_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        scrollCapture = capture
        vibrate()

        // Started from the notification shade, so the target app is not yet
        // back in front and there is nothing to scroll until it is.
        handler.postDelayed({
            capture.start { message ->
                scrollCapture = null
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }, SHADE_CLOSE_MS)
    }

    private fun registerScreenshotReceiver() {
        val filter = IntentFilter(ACTION_TAKE_SCREENSHOT).apply {
            addAction(ACTION_SCROLL_CAPTURE)
        }
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

    /**
     * Many keyboards never expose a TYPE_INPUT_METHOD window to a non-IME
     * accessibility service, so the window check alone misses most typing.
     * Recent text edits and a focused editable field cover those cases.
     */
    private fun isTypingLikely(): Boolean {
        if (System.currentTimeMillis() - lastTextChangeTime < TYPING_WINDOW_MS) return true
        if (checkKeyboardVisible()) return true
        return isEditableFieldFocused()
    }

    @Suppress("DEPRECATION")
    private fun isEditableFieldFocused(): Boolean {
        var root: AccessibilityNodeInfo? = null
        var focused: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow ?: return false
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            return focused.isEditable ||
                    focused.className?.toString()?.contains("EditText") == true
        } catch (_: Exception) {
            return false
        } finally {
            try { focused?.recycle() } catch (_: Exception) {}
            try { root?.recycle() } catch (_: Exception) {}
        }
    }

    private fun screenSize(): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = (windowManager ?: getSystemService(WINDOW_SERVICE) as WindowManager)
                .maximumWindowMetrics.bounds
            return bounds.width().toFloat() to bounds.height().toFloat()
        }
        val dm = resources.displayMetrics
        return dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
    }

    private fun isInTriggerZone(x: Float, y: Float): Boolean {
        val (width, height) = screenSize()
        if (width <= 0f || height <= 0f) return true

        val density = resources.displayMetrics.density

        // Android owns the screen edges: notification pull at the top, the
        // home/recents swipe at the bottom, back swipes on the sides. Those
        // gestures start with a touch down, which is indistinguishable from a
        // tap here, so the margins are never a trigger area.
        val margin = SYSTEM_GESTURE_MARGIN_DP * density
        if (y <= margin || y >= height - margin) return false
        if (x <= margin || x >= width - margin) return false

        // Corners only. A double-tap in the middle of the screen is the same
        // gesture apps use for zoom, like and select, so it cannot be told
        // apart from one; corners are effectively unused by app gestures.
        val box = CORNER_DP * density
        val nearX = x <= box || x >= width - box
        val nearY = y <= box || y >= height - box
        return nearX && nearY
    }

    private fun resetTapState() {
        tapSequenceCount = 0
        tapSequenceStart = 0L
        tapSequenceX = 0f
        tapSequenceY = 0f
    }

    /**
     * Failures on the capture path are caught so they cannot kill the process,
     * which would otherwise make them invisible. Record them where the app can
     * show them.
     */
    fun recordError(where: String, error: Throwable) {
        try {
            val text = buildString {
                append(errorHeader(this@ScreenshotService))
                append("Non-fatal in ").append(where).append('\n')
                append(error.toString()).append('\n')
                error.stackTrace.take(10).forEach { append("  at ").append(it).append('\n') }
            }
            prefs.edit()
                .putString(KEY_LAST_CRASH, text)
                .putString(KEY_LAST_CRASH_VERSION, appVersion(this))
                .apply()
        } catch (_: Exception) {
        }
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
        if (!isInTriggerZone(x, y)) {
            resetTapState()
            return
        }
        if (smartKeyboardEnabled && isTypingLikely()) return
        if (currentForegroundPackage in excludedApps) return

        val now = System.currentTimeMillis()

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

        if (tapSequenceCount >= TAPS_TO_CAPTURE) {
            resetTapState()
            lastScreenshotTime = now
            recentTouchCount = 0
            takeScreenshot()
        }
    }

    private fun takeScreenshot() {
        performScreenshotNow()
    }

    private fun performScreenshotNow() {
        val shouldRename = autoRenameEnabled
        if (shouldRename) registerScreenshotObserver()

        // The capture itself must survive anything the popup does wrong.
        if (quickShareEnabled) {
            try {
                quickSharePopup?.dismiss()
                val popup = QuickSharePopup(this)
                quickSharePopup = popup
                popup.listenForScreenshot()
            } catch (e: Exception) {
                quickSharePopup = null
                recordError("quick share setup", e)
            }
        }

        val success = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        if (success) {
            vibrate()
        } else {
            if (shouldRename) unregisterScreenshotObserver()
            quickSharePopup?.dismiss()
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerScreenshotObserver() {
        unregisterScreenshotObserver()

        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                uri?.let { renameScreenshot(it) }
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

    fun setVibrationPattern(pattern: String) {
        prefs.edit().putString(KEY_VIBRATION_PATTERN, pattern).apply()
    }

    fun setAutoRename(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RENAME, enabled).apply()
    }

    fun setQuickShare(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUICK_SHARE, enabled).apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.toString()?.let { pkg ->
                    if (pkg != "com.android.systemui") {
                        currentForegroundPackage = pkg
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                lastTextChangeTime = System.currentTimeMillis()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        accessibilityButtonCallback?.let {
            try {
                accessibilityButtonController.unregisterAccessibilityButtonCallback(it)
            } catch (_: Exception) {}
        }
        accessibilityButtonCallback = null
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
