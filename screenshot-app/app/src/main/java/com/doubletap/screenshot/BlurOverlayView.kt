package com.doubletap.screenshot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class BlurOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { BLUR, DRAW, ARROW, TEXT, CROP }

    var currentMode = Mode.BLUR
    var pendingText: String? = null

    private val operations = mutableListOf<DrawOp>()
    private var currentPath = mutableListOf<Pair<Float, Float>>()
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var isDragging = false

    private var viewWidth = 0
    private var viewHeight = 0
    private var imageWidth = 0
    private var imageHeight = 0

    private val blurPaint = Paint().apply {
        color = Color.argb(80, 255, 0, 0)
        style = Paint.Style.FILL
    }

    private val blurBorderPaint = Paint().apply {
        color = Color.argb(180, 255, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val drawPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val arrowPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.RED
        textSize = 40f
        isAntiAlias = true
    }

    private val watermarkPaint = Paint().apply {
        color = Color.argb(100, 255, 255, 255)
        textSize = 30f
        isAntiAlias = true
    }

    private val cropPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
    }

    private val cropDimPaint = Paint().apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
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
                isDragging = true

                if (currentMode == Mode.DRAW) {
                    currentPath = mutableListOf(Pair(startX, startY))
                } else if (currentMode == Mode.TEXT) {
                    pendingText?.let { text ->
                        operations.add(DrawOp.TextDraw(text, event.x, event.y))
                        pendingText = null
                        invalidate()
                    }
                    return true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x
                endY = event.y
                if (currentMode == Mode.DRAW) {
                    currentPath.add(Pair(endX, endY))
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                endX = event.x
                endY = event.y

                when (currentMode) {
                    Mode.BLUR -> {
                        val rect = makeRect(startX, startY, endX, endY)
                        if (rect.width() > 10 && rect.height() > 10) {
                            operations.add(DrawOp.BlurRect(rect.left, rect.top, rect.right, rect.bottom))
                        }
                    }
                    Mode.DRAW -> {
                        if (currentPath.size > 1) {
                            operations.add(DrawOp.FreeDraw(currentPath.toList()))
                        }
                        currentPath.clear()
                    }
                    Mode.ARROW -> {
                        val dx = endX - startX
                        val dy = endY - startY
                        if (Math.sqrt((dx * dx + dy * dy).toDouble()) > 20) {
                            operations.add(DrawOp.ArrowDraw(startX, startY, endX, endY))
                        }
                    }
                    Mode.CROP -> {
                        val rect = makeRect(startX, startY, endX, endY)
                        if (rect.width() > 20 && rect.height() > 20) {
                            operations.add(DrawOp.CropRect(rect.left, rect.top, rect.right, rect.bottom))
                        }
                    }
                    Mode.TEXT -> {}
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun makeRect(x1: Float, y1: Float, x2: Float, y2: Float): RectF {
        return RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (op in operations) {
            drawOp(canvas, op)
        }

        if (isDragging) {
            when (currentMode) {
                Mode.BLUR -> {
                    val rect = makeRect(startX, startY, endX, endY)
                    canvas.drawRect(rect, blurPaint)
                    canvas.drawRect(rect, blurBorderPaint)
                }
                Mode.DRAW -> {
                    for (i in 1 until currentPath.size) {
                        canvas.drawLine(
                            currentPath[i - 1].first, currentPath[i - 1].second,
                            currentPath[i].first, currentPath[i].second, drawPaint
                        )
                    }
                }
                Mode.ARROW -> {
                    canvas.drawLine(startX, startY, endX, endY, arrowPaint)
                    drawArrowHead(canvas, startX, startY, endX, endY)
                }
                Mode.CROP -> {
                    val rect = makeRect(startX, startY, endX, endY)
                    canvas.drawRect(0f, 0f, width.toFloat(), rect.top, cropDimPaint)
                    canvas.drawRect(0f, rect.top, rect.left, rect.bottom, cropDimPaint)
                    canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, cropDimPaint)
                    canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), cropDimPaint)
                    canvas.drawRect(rect, cropPaint)
                }
                Mode.TEXT -> {}
            }
        }
    }

    private fun drawOp(canvas: Canvas, op: DrawOp) {
        when (op) {
            is DrawOp.BlurRect -> {
                val rect = RectF(op.left, op.top, op.right, op.bottom)
                canvas.drawRect(rect, blurPaint)
                canvas.drawRect(rect, blurBorderPaint)
            }
            is DrawOp.FreeDraw -> {
                for (i in 1 until op.points.size) {
                    canvas.drawLine(
                        op.points[i - 1].first, op.points[i - 1].second,
                        op.points[i].first, op.points[i].second, drawPaint
                    )
                }
            }
            is DrawOp.ArrowDraw -> {
                canvas.drawLine(op.startX, op.startY, op.endX, op.endY, arrowPaint)
                drawArrowHead(canvas, op.startX, op.startY, op.endX, op.endY)
            }
            is DrawOp.TextDraw -> {
                canvas.drawText(op.text, op.x, op.y, textPaint)
            }
            is DrawOp.WatermarkDraw -> {
                val tw = watermarkPaint.measureText(op.text)
                canvas.drawText(op.text, width - tw - 10f, height - 15f, watermarkPaint)
            }
            is DrawOp.CropRect -> {
                val rect = RectF(op.left, op.top, op.right, op.bottom)
                canvas.drawRect(0f, 0f, width.toFloat(), rect.top, cropDimPaint)
                canvas.drawRect(0f, rect.top, rect.left, rect.bottom, cropDimPaint)
                canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, cropDimPaint)
                canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), cropDimPaint)
                canvas.drawRect(rect, cropPaint)
            }
        }
    }

    private fun drawArrowHead(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val angle = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val arrowLen = 25f
        val arrowAngle = Math.toRadians(25.0)

        val fillPaint = Paint(arrowPaint).apply {
            style = Paint.Style.FILL_AND_STROKE
        }
        val path = Path().apply {
            moveTo(x2, y2)
            lineTo(
                (x2 - arrowLen * Math.cos(angle - arrowAngle)).toFloat(),
                (y2 - arrowLen * Math.sin(angle - arrowAngle)).toFloat()
            )
            lineTo(
                (x2 - arrowLen * Math.cos(angle + arrowAngle)).toFloat(),
                (y2 - arrowLen * Math.sin(angle + arrowAngle)).toFloat()
            )
            close()
        }
        canvas.drawPath(path, fillPaint)
    }

    fun addWatermark(text: String) {
        operations.add(DrawOp.WatermarkDraw(text))
        invalidate()
    }

    fun undo() {
        if (operations.isNotEmpty()) {
            operations.removeAt(operations.size - 1)
            invalidate()
        }
    }

    fun getDrawingOperations(): List<DrawOp> = operations.toList()

    fun getBlurRegions(): List<BlurRegion> {
        if (viewWidth == 0 || viewHeight == 0 || imageWidth == 0 || imageHeight == 0) {
            return emptyList()
        }
        val scaleX = imageWidth.toFloat() / viewWidth
        val scaleY = imageHeight.toFloat() / viewHeight
        return operations.filterIsInstance<DrawOp.BlurRect>().map { rect ->
            BlurRegion(
                (rect.left * scaleX).toInt(),
                (rect.top * scaleY).toInt(),
                (rect.right * scaleX).toInt(),
                (rect.bottom * scaleY).toInt()
            )
        }
    }

    fun clearRegions() {
        operations.clear()
        invalidate()
    }
}
