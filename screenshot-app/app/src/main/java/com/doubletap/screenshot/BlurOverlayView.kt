package com.doubletap.screenshot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class BlurOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val regions = mutableListOf<RectF>()
    private var currentRect: RectF? = null
    private var startX = 0f
    private var startY = 0f

    private var viewWidth = 0
    private var viewHeight = 0
    private var imageWidth = 0
    private var imageHeight = 0

    private val paint = Paint().apply {
        color = Color.argb(80, 255, 0, 0)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.argb(180, 255, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    fun setImageDimensions(vw: Int, vh: Int, iw: Int, ih: Int) {
        viewWidth = vw
        viewHeight = vh
        imageWidth = iw
        imageHeight = ih
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentRect = RectF(startX, startY, startX, startY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentRect = RectF(
                    minOf(startX, event.x), minOf(startY, event.y),
                    maxOf(startX, event.x), maxOf(startY, event.y)
                )
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentRect?.let {
                    if (it.width() > 10 && it.height() > 10) {
                        regions.add(it)
                    }
                }
                currentRect = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (rect in regions) {
            canvas.drawRect(rect, paint)
            canvas.drawRect(rect, borderPaint)
        }
        currentRect?.let {
            canvas.drawRect(it, paint)
            canvas.drawRect(it, borderPaint)
        }
    }

    fun getBlurRegions(): List<BlurRegion> {
        if (viewWidth == 0 || viewHeight == 0 || imageWidth == 0 || imageHeight == 0) {
            return emptyList()
        }
        val scaleX = imageWidth.toFloat() / viewWidth
        val scaleY = imageHeight.toFloat() / viewHeight
        return regions.map { rect ->
            BlurRegion(
                (rect.left * scaleX).toInt(),
                (rect.top * scaleY).toInt(),
                (rect.right * scaleX).toInt(),
                (rect.bottom * scaleY).toInt()
            )
        }
    }

    fun clearRegions() {
        regions.clear()
        invalidate()
    }
}
