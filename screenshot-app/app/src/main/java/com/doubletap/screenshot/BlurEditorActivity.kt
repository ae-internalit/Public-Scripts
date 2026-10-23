package com.doubletap.screenshot

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException

class BlurEditorActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var blurOverlay: BlurOverlayView
    private var originalBitmap: Bitmap? = null
    private var sourceUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blur_editor)

        imageView = findViewById(R.id.editorImage)
        blurOverlay = findViewById(R.id.blurOverlay)
        val saveBtn = findViewById<Button>(R.id.btnSave)
        val shareBtn = findViewById<Button>(R.id.btnShareBlurred)
        val cancelBtn = findViewById<Button>(R.id.btnCancel)

        sourceUri = intent.data
        sourceUri?.let { loadImage(it) }

        saveBtn.setOnClickListener { saveBlurredImage(share = false) }
        shareBtn.setOnClickListener { saveBlurredImage(share = true) }
        cancelBtn.setOnClickListener { finish() }
    }

    private fun loadImage(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                originalBitmap = BitmapFactory.decodeStream(stream)
                imageView.setImageBitmap(originalBitmap)
                blurOverlay.post {
                    blurOverlay.setImageDimensions(
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

    private fun saveBlurredImage(share: Boolean) {
        val bitmap = originalBitmap ?: return
        val regions = blurOverlay.getBlurRegions()
        if (regions.isEmpty()) {
            Toast.makeText(this, "Draw on areas to blur first", Toast.LENGTH_SHORT).show()
            return
        }

        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        for (region in regions) {
            applyPixelatedBlur(result, canvas, region)
        }

        val uri = saveBitmapToGallery(result) ?: run {
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
            Toast.makeText(this, "Saved to gallery", Toast.LENGTH_SHORT).show()
        }
        finish()
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
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_blurred_${System.currentTimeMillis()}")
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
