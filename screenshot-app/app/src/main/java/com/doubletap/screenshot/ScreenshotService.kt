package com.doubletap.screenshot

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs

class ScreenshotService : AccessibilityService() {

    private var overlayView: FrameLayout? = null
    private var windowManager: WindowManager? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var isDragging = false

    companion object {
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val TAP_THRESHOLD = 10
        private const val TRIGGER_SIZE_DP = 48
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        createOverlay()
    }

    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val size = dpToPx(TRIGGER_SIZE_DP)

        layoutParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dpToPx(8)
            y = resources.displayMetrics.heightPixels / 2
        }

        overlayView = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.ic_trigger)
            alpha = 0.5f
            contentDescription = getString(R.string.trigger_description)

            val icon = ImageView(this@ScreenshotService).apply {
                setImageResource(R.drawable.ic_camera)
                val pad = dpToPx(12)
                setPadding(pad, pad, pad, pad)
            }
            addView(icon, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            setOnTouchListener { _, event ->
                handleTouch(event)
                true
            }
        }

        windowManager?.addView(overlayView, layoutParams)
    }

    private fun handleTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()

                if (abs(dx) > TAP_THRESHOLD || abs(dy) > TAP_THRESHOLD) {
                    isDragging = true
                    layoutParams.x = initialX + dx
                    layoutParams.y = initialY + dy
                    windowManager?.updateViewLayout(overlayView, layoutParams)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < DOUBLE_TAP_TIMEOUT) {
                        onDoubleTap()
                        lastTapTime = 0
                    } else {
                        lastTapTime = now
                    }
                }
            }
        }
    }

    private fun onDoubleTap() {
        overlayView?.let { view ->
            view.alpha = 0f
            view.postDelayed({
                takeScreenshot()
                view.postDelayed({ view.alpha = 0.5f }, 500)
            }, 100)
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}
