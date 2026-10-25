package com.doubletap.screenshot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Pinch to zoom, drag to pan, double-tap to toggle.
 *
 * The gallery used to hand the image to whatever external viewer happened to be
 * installed and silently did nothing when none accepted the intent. Owning the
 * viewer means opening a screenshot always works, and a long screenshot can be
 * zoomed into rather than squeezed onto one screen.
 */
private const val SWIPE_VELOCITY = 800f

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val values = FloatArray(9)
    private val m = Matrix()
    private var baseScale = 1f
    private var maxScale = 1f

    // The superclass constructor can call setImageDrawable() from android:src
    // before this class's fields exist, so nothing below runs until init does.
    private var ready = false

    /**
     * Called with -1 for the previous image and +1 for the next, on a
     * horizontal fling. Only fires while the image is zoomed out: once it is
     * zoomed in, a sideways drag means panning, and stealing that for paging
     * would make the zoom useless.
     */
    var onSwipe: ((Int) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val current = currentScale()
                if (current > baseScale * 1.2f) {
                    scaleBy(baseScale / current, e.x, e.y)
                } else {
                    scaleBy(2.5f, e.x, e.y)
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                m.postTranslate(-dx, -dy)
                applyBounds()
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
            ): Boolean {
                val handler = onSwipe ?: return false
                if (!atRestingZoom()) return false
                // Mostly sideways, and fast enough to be deliberate.
                if (kotlin.math.abs(vx) < kotlin.math.abs(vy)) return false
                if (kotlin.math.abs(vx) < SWIPE_VELOCITY) return false
                handler(if (vx > 0) -1 else 1)
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        ready = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        fitToView()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed) fitToView()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!ready) return super.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    /** Scales the image down to fit and centres it. The zoomed-out resting state. */
    fun fitToView() {
        if (!ready) return
        val d = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val drawW = d.intrinsicWidth.toFloat()
        val drawH = d.intrinsicHeight.toFloat()
        if (viewW <= 0f || viewH <= 0f || drawW <= 0f || drawH <= 0f) return

        baseScale = minOf(viewW / drawW, viewH / drawH)
        maxScale = baseScale * 8f

        m.reset()
        m.postScale(baseScale, baseScale)
        m.postTranslate((viewW - drawW * baseScale) / 2f, (viewH - drawH * baseScale) / 2f)
        imageMatrix = m
    }

    /** True while the image is at its zoomed-out size, within rounding. */
    fun atRestingZoom(): Boolean = currentScale() <= baseScale * 1.01f

    private fun currentScale(): Float {
        m.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun scaleBy(factor: Float, focusX: Float, focusY: Float) {
        if (drawable == null) return
        val current = currentScale()
        var f = factor
        if (current * f < baseScale) f = baseScale / current
        if (current * f > maxScale) f = maxScale / current
        m.postScale(f, f, focusX, focusY)
        applyBounds()
    }

    /** Keeps the image centred while it fits, and inside the edges once it does not. */
    private fun applyBounds() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(rect)

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        val dx = when {
            rect.width() <= viewW -> (viewW - rect.width()) / 2f - rect.left
            rect.left > 0f -> -rect.left
            rect.right < viewW -> viewW - rect.right
            else -> 0f
        }
        val dy = when {
            rect.height() <= viewH -> (viewH - rect.height()) / 2f - rect.top
            rect.top > 0f -> -rect.top
            rect.bottom < viewH -> viewH - rect.bottom
            else -> 0f
        }

        m.postTranslate(dx, dy)
        imageMatrix = m
    }
}
