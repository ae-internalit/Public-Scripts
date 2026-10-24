package com.doubletap.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class OcrSelectView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    class TextRegion(val rect: Rect, val text: String, var selected: Boolean = false)

    private var bitmap: Bitmap? = null
    private val regions = mutableListOf<TextRegion>()

    /** Bitmap-to-view transform, recomputed on every draw. */
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    var onSelectionChanged: (() -> Unit)? = null

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(150, 90, 169, 216)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 90, 169, 216)
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 76, 175, 80)
    }
    private val selectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(230, 76, 175, 80)
    }

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        invalidate()
    }

    fun setRegions(found: List<TextRegion>) {
        regions.clear()
        regions.addAll(found)
        invalidate()
        onSelectionChanged?.invoke()
    }

    fun hasRegions(): Boolean = regions.isNotEmpty()

    fun selectedText(): String = regions.filter { it.selected }.joinToString("\n") { it.text }

    fun selectedCount(): Int = regions.count { it.selected }

    fun setAllSelected(selected: Boolean) {
        regions.forEach { it.selected = selected }
        invalidate()
        onSelectionChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return

        scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        offsetX = (width - bmp.width * scale) / 2f
        offsetY = (height - bmp.height * scale) / 2f

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        canvas.restore()

        for (region in regions) {
            val r = RectF(
                offsetX + region.rect.left * scale,
                offsetY + region.rect.top * scale,
                offsetX + region.rect.right * scale,
                offsetY + region.rect.bottom * scale
            )
            if (region.selected) {
                canvas.drawRect(r, selectedPaint)
                canvas.drawRect(r, selectedStroke)
            } else {
                canvas.drawRect(r, fillPaint)
                canvas.drawRect(r, boxPaint)
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true

        if (scale <= 0f) return true
        val bx = (event.x - offsetX) / scale
        val by = (event.y - offsetY) / scale

        // Smallest hit wins, so a line inside a block stays selectable.
        val hit = regions
            .filter { it.rect.contains(bx.toInt(), by.toInt()) }
            .minByOrNull { it.rect.width().toLong() * it.rect.height() }

        if (hit != null) {
            hit.selected = !hit.selected
            invalidate()
            onSelectionChanged?.invoke()
            performClick()
        }
        return true
    }
}
