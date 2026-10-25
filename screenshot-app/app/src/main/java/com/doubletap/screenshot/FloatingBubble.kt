package com.doubletap.screenshot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

/**
 * The app's own floating button.
 *
 * Android's accessibility button cannot do this: the system owns it and only
 * ever reports "it was clicked", with no press, hold or release. It also sits
 * in every screenshot, because it is a system window this app cannot hide.
 * Owning the window solves both — every touch is visible here, and it can step
 * out of frame before a capture.
 *
 * It is a TYPE_ACCESSIBILITY_OVERLAY, which an accessibility service may add
 * without the "draw over other apps" permission.
 */
class FloatingBubble(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onHold: () -> Unit
) {

    companion object {
        private const val SIZE_DP = 52
        private const val LONG_PRESS_MS = 400L
        private const val TOUCH_SLOP_DP = 12f
        const val KEY_X = "bubble_x"
        const val KEY_Y = "bubble_y"
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val prefs =
        context.getSharedPreferences(ScreenshotService.PREFS_NAME, Context.MODE_PRIVATE)

    private val density = context.resources.displayMetrics.density
    private val sizePx = (SIZE_DP * density).toInt()

    private val gesture = BubbleGesture(TOUCH_SLOP_DP * density, LONG_PRESS_MS)

    private var view: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var hidden = false

    private var startX = 0
    private var startY = 0
    private var rawDownX = 0f
    private var rawDownY = 0f

    /** Fires the hold while the finger is still down, not when it is lifted. */
    private val holdCheck = Runnable {
        if (gesture.tick(SystemClock.uptimeMillis()) == BubbleGesture.Phase.HELD) {
            onHold()
        }
    }

    val isShowing: Boolean get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return

        val layout = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, screenWidth() - sizePx - (16 * density).toInt())
            y = prefs.getInt(KEY_Y, screenHeight() / 3)
        }

        val bubble = ImageView(context).apply {
            setImageResource(R.drawable.ic_camera)
            setBackgroundResource(R.drawable.bubble_bg)
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = context.getString(R.string.bubble_description)
            setOnTouchListener { _, event -> onTouch(event) }
        }

        try {
            windowManager.addView(bubble, layout)
            view = bubble
            params = layout
            hidden = false
        } catch (e: Exception) {
            ScreenshotService.instance?.recordError("bubble add", e)
        }
    }

    fun hide() {
        val bubble = view ?: return
        handler.removeCallbacks(holdCheck)
        gesture.cancel()
        try {
            windowManager.removeView(bubble)
        } catch (_: Exception) {
        }
        view = null
        params = null
    }

    /** Steps out of frame for a capture without forgetting where it was. */
    fun hideForCapture() {
        view?.let {
            hidden = true
            it.visibility = View.GONE
        }
    }

    fun showAfterCapture() {
        view?.let {
            hidden = false
            it.visibility = View.VISIBLE
        }
    }

    /** Red with a stop square while recording, so the same button ends it. */
    fun setRecording(recording: Boolean) {
        val bubble = view ?: return
        bubble.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_camera)
        bubble.setBackgroundResource(
            if (recording) R.drawable.bubble_bg_recording else R.drawable.bubble_bg
        )
    }

    private fun onTouch(event: MotionEvent): Boolean {
        val layout = params ?: return false
        val bubble = view ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture.down(event.rawX, event.rawY, SystemClock.uptimeMillis())
                startX = layout.x
                startY = layout.y
                rawDownX = event.rawX
                rawDownY = event.rawY
                bubble.alpha = 0.75f
                handler.postDelayed(holdCheck, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val phase = gesture.move(event.rawX, event.rawY, SystemClock.uptimeMillis())
                if (phase == BubbleGesture.Phase.DRAGGING) {
                    handler.removeCallbacks(holdCheck)
                    layout.x = (startX + (event.rawX - rawDownX)).toInt()
                        .coerceIn(0, screenWidth() - sizePx)
                    layout.y = (startY + (event.rawY - rawDownY)).toInt()
                        .coerceIn(0, screenHeight() - sizePx)
                    try {
                        windowManager.updateViewLayout(bubble, layout)
                    } catch (_: Exception) {
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdCheck)
                bubble.alpha = 1f
                when (gesture.up()) {
                    BubbleGesture.Release.TAP -> onTap()
                    BubbleGesture.Release.DRAG_END -> snapToEdge(bubble, layout)
                    BubbleGesture.Release.HOLD_END -> Unit
                    BubbleGesture.Release.NONE -> Unit
                }
                return true
            }
        }
        return false
    }

    /** Parks it against whichever side it was dropped nearest. */
    private fun snapToEdge(bubble: ImageView, layout: WindowManager.LayoutParams) {
        val margin = (8 * density).toInt()
        val middle = screenWidth() / 2
        layout.x = if (layout.x + sizePx / 2 < middle) margin else screenWidth() - sizePx - margin

        try {
            windowManager.updateViewLayout(bubble, layout)
        } catch (_: Exception) {
        }
        prefs.edit().putInt(KEY_X, layout.x).putInt(KEY_Y, layout.y).apply()
    }

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels
}
