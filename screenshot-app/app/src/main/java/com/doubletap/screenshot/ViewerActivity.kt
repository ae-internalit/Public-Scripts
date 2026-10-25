package com.doubletap.screenshot

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen view of one screenshot, with everything the app can do to it.
 *
 * Tapping a thumbnail used to open a menu, which meant there was no way to
 * simply look at a screenshot; the one menu entry that did open it relied on
 * an external viewer and failed silently when the intent went unanswered.
 */
class ViewerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URI = "viewer_uri"
        private const val EXTRA_NAME = "viewer_name"

        // A stitched long screenshot can run to tens of millions of pixels,
        // which decodes straight into an OutOfMemoryError at full size.
        private const val MAX_PIXELS = 16_000_000L

        fun start(context: Context, uri: Uri, name: String) {
            context.startActivity(
                Intent(context, ViewerActivity::class.java)
                    .putExtra(EXTRA_URI, uri.toString())
                    .putExtra(EXTRA_NAME, name)
            )
        }
    }

    private lateinit var uri: Uri
    private lateinit var image: ZoomableImageView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        val raw = intent.getStringExtra(EXTRA_URI)
        if (raw == null) {
            finish()
            return
        }
        uri = Uri.parse(raw)

        image = findViewById(R.id.viewerImage)
        progress = findViewById(R.id.viewerProgress)
        findViewById<TextView>(R.id.viewerName).text =
            intent.getStringExtra(EXTRA_NAME) ?: ""

        findViewById<Button>(R.id.btnViewerBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnViewerOcr).setOnClickListener { OcrActivity.start(this, uri) }
        findViewById<Button>(R.id.btnViewerEdit).setOnClickListener {
            startActivity(Intent(this, BlurEditorActivity::class.java).apply { data = uri })
        }
        findViewById<Button>(R.id.btnViewerShare).setOnClickListener { share() }
        findViewById<Button>(R.id.btnViewerDelete).setOnClickListener { confirmDelete() }

        loadImage()
    }

    private fun loadImage() {
        progress.visibility = View.VISIBLE
        Thread {
            val bitmap = try {
                decodeScaled()
            } catch (e: Exception) {
                ScreenshotService.instance?.recordError("viewer decode", e)
                null
            } catch (e: OutOfMemoryError) {
                ScreenshotService.instance?.recordError("viewer decode", e)
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                if (bitmap == null) {
                    Toast.makeText(this, R.string.viewer_load_failed, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    image.setImageBitmap(bitmap)
                    image.fitToView()
                }
            }
        }.start()
    }

    private fun decodeScaled(): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_PIXELS) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.viewer_share)))
    }

    private fun confirmDelete() {
        // On API 30+ the platform shows its own confirmation, so a second one
        // of ours would just be two dialogs in a row.
        if (MediaDelete.systemConfirms()) {
            runDelete()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(R.string.delete_message)
            .setPositiveButton(R.string.viewer_delete) { _, _ -> runDelete() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runDelete() {
        when (MediaDelete.delete(this, listOf(uri))) {
            is MediaDelete.Result.Deleted -> {
                Toast.makeText(this, R.string.delete_done, Toast.LENGTH_SHORT).show()
                finish()
            }
            is MediaDelete.Result.Confirming -> Unit
            is MediaDelete.Result.Failed ->
                Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show()
        }
    }

    // The system delete confirmation reports back here.
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != MediaDelete.REQUEST_DELETE) return
        if (resultCode == RESULT_OK) {
            Toast.makeText(this, R.string.delete_done, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
