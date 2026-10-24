package com.doubletap.screenshot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException

class BlurEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RUN_OCR = "run_ocr"
    }

    private lateinit var imageView: ImageView
    private lateinit var drawingOverlay: BlurOverlayView
    private lateinit var toolIndicator: TextView
    private var originalBitmap: Bitmap? = null
    private var sourceUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blur_editor)

        imageView = findViewById(R.id.editorImage)
        drawingOverlay = findViewById(R.id.blurOverlay)
        toolIndicator = findViewById(R.id.toolIndicator)
        val saveBtn = findViewById<Button>(R.id.btnSave)
        val shareBtn = findViewById<Button>(R.id.btnShareBlurred)
        val cancelBtn = findViewById<Button>(R.id.btnCancel)

        val btnBlur = findViewById<ImageButton>(R.id.btnToolBlur)
        val btnDraw = findViewById<ImageButton>(R.id.btnToolDraw)
        val btnArrow = findViewById<ImageButton>(R.id.btnToolArrow)
        val btnText = findViewById<ImageButton>(R.id.btnToolText)
        val btnCrop = findViewById<ImageButton>(R.id.btnToolCrop)
        val btnWatermark = findViewById<ImageButton>(R.id.btnToolWatermark)
        val btnUndo = findViewById<ImageButton>(R.id.btnToolUndo)
        val btnOcr = findViewById<ImageButton>(R.id.btnToolOcr)

        sourceUri = intent.data
        sourceUri?.let { loadImage(it) }

        if (intent.getBooleanExtra(EXTRA_RUN_OCR, false)) extractText()

        saveBtn.setOnClickListener { saveImage(share = false) }
        shareBtn.setOnClickListener { saveImage(share = true) }
        cancelBtn.setOnClickListener { finish() }

        btnBlur.setOnClickListener { setTool(BlurOverlayView.Mode.BLUR) }
        btnDraw.setOnClickListener { setTool(BlurOverlayView.Mode.DRAW) }
        btnArrow.setOnClickListener { setTool(BlurOverlayView.Mode.ARROW) }
        btnText.setOnClickListener { promptText() }
        btnCrop.setOnClickListener { setTool(BlurOverlayView.Mode.CROP) }
        btnWatermark.setOnClickListener { promptWatermark() }
        btnUndo.setOnClickListener { drawingOverlay.undo() }
        btnOcr.setOnClickListener { extractText() }
    }

    private fun setTool(mode: BlurOverlayView.Mode) {
        drawingOverlay.currentMode = mode
        toolIndicator.text = when (mode) {
            BlurOverlayView.Mode.BLUR -> getString(R.string.tool_blur)
            BlurOverlayView.Mode.DRAW -> getString(R.string.tool_draw)
            BlurOverlayView.Mode.ARROW -> getString(R.string.tool_arrow)
            BlurOverlayView.Mode.TEXT -> getString(R.string.tool_text)
            BlurOverlayView.Mode.CROP -> getString(R.string.tool_crop)
        }
    }

    private fun promptText() {
        val input = EditText(this)
        input.hint = getString(R.string.editor_text_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_add_text)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString()
                if (text.isNotEmpty()) {
                    drawingOverlay.pendingText = text
                    setTool(BlurOverlayView.Mode.TEXT)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptWatermark() {
        val input = EditText(this)
        input.hint = getString(R.string.watermark_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.watermark_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString()
                if (text.isNotEmpty()) {
                    drawingOverlay.addWatermark(text)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun extractText() {
        val bitmap = originalBitmap ?: return
        try {
            val inputImage = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
            )
            recognizer.process(inputImage)
                .addOnSuccessListener { text ->
                    if (text.text.isNotEmpty()) {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("OCR", text.text))
                        AlertDialog.Builder(this)
                            .setTitle(R.string.ocr_result_title)
                            .setMessage(text.text)
                            .setPositiveButton(R.string.ocr_copy) { _, _ ->
                                Toast.makeText(this, R.string.ocr_copied, Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        Toast.makeText(this, R.string.ocr_no_text, Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, R.string.ocr_failed, Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.ocr_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadImage(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                originalBitmap = BitmapFactory.decodeStream(stream)
                imageView.setImageBitmap(originalBitmap)
                drawingOverlay.post {
                    drawingOverlay.setImageDimensions(
                        imageView.width, imageView.height,
                        originalBitmap?.width ?: 0, originalBitmap?.height ?: 0
                    )
                }
            }
        } catch (_: IOException) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun saveImage(share: Boolean) {
        val bitmap = originalBitmap ?: return
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val drawings = drawingOverlay.getDrawingOperations()
        if (drawings.isEmpty()) {
            Toast.makeText(this, R.string.editor_no_changes, Toast.LENGTH_SHORT).show()
            return
        }

        val scaleX = result.width.toFloat() / drawingOverlay.width
        val scaleY = result.height.toFloat() / drawingOverlay.height

        for (op in drawings) {
            when (op) {
                is DrawOp.BlurRect -> applyPixelatedBlur(result, canvas, op.toBlurRegion(scaleX, scaleY))
                is DrawOp.FreeDraw -> drawFreehand(canvas, op, scaleX, scaleY)
                is DrawOp.ArrowDraw -> drawArrow(canvas, op, scaleX, scaleY)
                is DrawOp.TextDraw -> drawText(canvas, op, scaleX, scaleY)
                is DrawOp.WatermarkDraw -> drawWatermark(canvas, op, result.width, result.height)
                is DrawOp.CropRect -> {
                    val cropped = cropBitmap(result, op, scaleX, scaleY)
                    if (cropped != null) {
                        saveCropped(cropped, share)
                        cropped.recycle()
                        result.recycle()
                        return
                    }
                }
            }
        }

        val uri = saveBitmapToGallery(result) ?: run {
            Toast.makeText(this, "Failed to save", Toast.LENGTH_SHORT).show()
            return
        }
        result.recycle()

        if (share) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share"))
        } else {
            Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun saveCropped(bitmap: Bitmap, share: Boolean) {
        val uri = saveBitmapToGallery(bitmap) ?: run {
            Toast.makeText(this, "Failed to save", Toast.LENGTH_SHORT).show()
            return
        }
        if (share) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share"))
        } else {
            Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun drawFreehand(canvas: Canvas, op: DrawOp.FreeDraw, sx: Float, sy: Float) {
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5f * sx
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }
        val points = op.points
        for (i in 1 until points.size) {
            canvas.drawLine(
                points[i - 1].first * sx, points[i - 1].second * sy,
                points[i].first * sx, points[i].second * sy, paint
            )
        }
    }

    private fun drawArrow(canvas: Canvas, op: DrawOp.ArrowDraw, sx: Float, sy: Float) {
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5f * sx
            isAntiAlias = true
        }
        val x1 = op.startX * sx
        val y1 = op.startY * sy
        val x2 = op.endX * sx
        val y2 = op.endY * sy
        canvas.drawLine(x1, y1, x2, y2, paint)

        val angle = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val arrowLen = 30f * sx
        val arrowAngle = Math.toRadians(25.0)

        paint.style = Paint.Style.FILL_AND_STROKE
        val path = android.graphics.Path().apply {
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
        canvas.drawPath(path, paint)
    }

    private fun drawText(canvas: Canvas, op: DrawOp.TextDraw, sx: Float, sy: Float) {
        val paint = Paint().apply {
            color = Color.RED
            textSize = 40f * sx
            isAntiAlias = true
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }
        canvas.drawText(op.text, op.x * sx, op.y * sy, paint)
    }

    private fun drawWatermark(canvas: Canvas, op: DrawOp.WatermarkDraw, w: Int, h: Int) {
        val paint = Paint().apply {
            color = Color.argb(100, 255, 255, 255)
            textSize = w / 20f
            isAntiAlias = true
            setShadowLayer(4f, 2f, 2f, Color.argb(128, 0, 0, 0))
        }
        val textWidth = paint.measureText(op.text)
        canvas.drawText(op.text, w - textWidth - 20f, h - 30f, paint)
    }

    private fun cropBitmap(bitmap: Bitmap, op: DrawOp.CropRect, sx: Float, sy: Float): Bitmap? {
        val left = (op.left * sx).toInt().coerceIn(0, bitmap.width)
        val top = (op.top * sy).toInt().coerceIn(0, bitmap.height)
        val right = (op.right * sx).toInt().coerceIn(0, bitmap.width)
        val bottom = (op.bottom * sy).toInt().coerceIn(0, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(bitmap, left, top, w, h)
    }

    private fun applyPixelatedBlur(bitmap: Bitmap, canvas: Canvas, region: BlurRegion) {
        val pixelSize = 20
        val paint = Paint()

        val left = region.left.coerceIn(0, bitmap.width)
        val top = region.top.coerceIn(0, bitmap.height)
        val right = region.right.coerceIn(0, bitmap.width)
        val bottom = region.bottom.coerceIn(0, bitmap.height)

        var x = left
        while (x < right) {
            var y = top
            while (y < bottom) {
                val blockRight = (x + pixelSize).coerceAtMost(right)
                val blockBottom = (y + pixelSize).coerceAtMost(bottom)

                var r = 0L; var g = 0L; var b = 0L; var count = 0
                for (px in x until blockRight) {
                    for (py in y until blockBottom) {
                        val pixel = bitmap.getPixel(px, py)
                        r += (pixel shr 16) and 0xFF
                        g += (pixel shr 8) and 0xFF
                        b += pixel and 0xFF
                        count++
                    }
                }

                if (count > 0) {
                    val avgColor = (0xFF shl 24) or
                            ((r / count).toInt() shl 16) or
                            ((g / count).toInt() shl 8) or
                            (b / count).toInt()
                    paint.color = avgColor
                    canvas.drawRect(
                        x.toFloat(), y.toFloat(),
                        blockRight.toFloat(), blockBottom.toFloat(),
                        paint
                    )
                }

                y += pixelSize
            }
            x += pixelSize
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_edited_${System.currentTimeMillis()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots")
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        return uri
    }
}

data class BlurRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

sealed class DrawOp {
    data class BlurRect(val left: Float, val top: Float, val right: Float, val bottom: Float) : DrawOp() {
        fun toBlurRegion(sx: Float, sy: Float) = BlurRegion(
            (left * sx).toInt(), (top * sy).toInt(),
            (right * sx).toInt(), (bottom * sy).toInt()
        )
    }
    data class FreeDraw(val points: List<Pair<Float, Float>>) : DrawOp()
    data class ArrowDraw(val startX: Float, val startY: Float, val endX: Float, val endY: Float) : DrawOp()
    data class TextDraw(val text: String, val x: Float, val y: Float) : DrawOp()
    data class WatermarkDraw(val text: String) : DrawOp()
    data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) : DrawOp()
}
