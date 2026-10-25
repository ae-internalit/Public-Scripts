package com.doubletap.screenshot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen view of one screenshot, with everything the app can do to it.
 *
 * It holds the whole list rather than a single image so a sideways swipe moves
 * to the next one, the way every gallery behaves. The list is re-read from
 * MediaQuery rather than passed in: a few thousand ids would not survive the
 * intent size limit, and the query is the same one the grid used.
 */
class ViewerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "viewer_id"
        private const val EXTRA_ALL_PHOTOS = "viewer_all_photos"

        fun start(context: Context, id: Long, allPhotos: Boolean) {
            context.startActivity(
                Intent(context, ViewerActivity::class.java)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_ALL_PHOTOS, allPhotos)
            )
        }
    }

    private lateinit var image: ZoomableImageView
    private lateinit var progress: ProgressBar
    private lateinit var nameText: TextView
    private lateinit var countText: TextView

    private var items: List<ScreenshotItem> = emptyList()
    private var index = 0
    private var allPhotos = false

    /** Bumped on every navigation so a slow decode for an image the user has
     *  already swiped past is dropped instead of being shown. */
    private var loadToken = 0

    private val current: ScreenshotItem? get() = items.getOrNull(index)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        image = findViewById(R.id.viewerImage)
        progress = findViewById(R.id.viewerProgress)
        nameText = findViewById(R.id.viewerName)
        countText = findViewById(R.id.viewerCount)

        allPhotos = intent.getBooleanExtra(EXTRA_ALL_PHOTOS, false)
        val startId = intent.getLongExtra(EXTRA_ID, -1L)

        items = MediaQuery.load(this, allPhotos)
        index = items.indexOfFirst { it.id == startId }.coerceAtLeast(0)

        if (items.isEmpty()) {
            Toast.makeText(this, R.string.viewer_load_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        findViewById<Button>(R.id.btnViewerBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnViewerOcr).setOnClickListener {
            current?.let { OcrActivity.start(this, it.uri) }
        }
        findViewById<Button>(R.id.btnViewerEdit).setOnClickListener {
            current?.let {
                startActivity(Intent(this, BlurEditorActivity::class.java).apply { data = it.uri })
            }
        }
        findViewById<Button>(R.id.btnViewerShare).setOnClickListener { share() }
        findViewById<Button>(R.id.btnViewerDelete).setOnClickListener { confirmDelete() }
        findViewById<Button>(R.id.btnViewerMore).setOnClickListener { showMore() }

        image.onSwipe = { step -> go(step) }

        show()
    }

    /** Moves by one, stopping at the ends rather than wrapping around. */
    private fun go(step: Int) {
        val next = index + step
        if (next !in items.indices) return
        index = next
        show()
    }

    private fun show() {
        val item = current ?: run { finish(); return }

        nameText.text = item.name
        countText.text = getString(R.string.viewer_position, index + 1, items.size)
        image.setImageDrawable(null)
        progress.visibility = View.VISIBLE

        val token = ++loadToken
        val uri = item.uri

        Thread {
            val bitmap = try {
                BitmapLoader.decode(this, uri, BitmapLoader.VIEW_PIXELS)
            } catch (e: Exception) {
                ScreenshotService.instance?.recordError("viewer decode", e)
                null
            } catch (e: OutOfMemoryError) {
                ScreenshotService.instance?.recordError("viewer decode", e)
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || token != loadToken) return@runOnUiThread
                progress.visibility = View.GONE
                if (bitmap == null) {
                    Toast.makeText(this, R.string.viewer_load_failed, Toast.LENGTH_SHORT).show()
                } else {
                    image.setImageBitmap(bitmap)
                    image.fitToView()
                }
            }
        }.start()
    }

    private fun share() {
        val item = current ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.viewer_share)))
    }

    private fun showMore() {
        val options = arrayOf(
            getString(R.string.more_open_with),
            getString(R.string.more_copy_image),
            getString(R.string.more_details)
        )
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openWith()
                    1 -> copyImage()
                    2 -> showDetails()
                }
            }
            .show()
    }

    private fun openWith() {
        val item = current ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.more_open_with)))
        } catch (e: Exception) {
            // Swallowing this is what made the old menu entry look like it did
            // nothing at all.
            Toast.makeText(this, R.string.more_no_app, Toast.LENGTH_LONG).show()
        }
    }

    /** Puts the image itself on the clipboard, so it can be pasted into a chat. */
    private fun copyImage() {
        val item = current ?: return
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newUri(contentResolver, item.name, item.uri)
            )
            Toast.makeText(this, R.string.more_copied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            ScreenshotService.instance?.recordError("copy image", e)
            Toast.makeText(this, R.string.more_copy_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDetails() {
        val item = current ?: return

        var size = -1L
        var width = 0
        var height = 0
        try {
            contentResolver.query(
                item.uri,
                arrayOf(
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT
                ),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    size = cursor.getLong(0)
                    width = cursor.getInt(1)
                    height = cursor.getInt(2)
                }
            }
        } catch (_: Exception) {
        }

        val when1 = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
            .format(Date(item.date * 1000))

        val body = buildString {
            append(item.name).append("\n\n")
            append(when1).append("\n")
            if (width > 0 && height > 0) append("$width × $height\n")
            if (size >= 0) append(Formatter.formatShortFileSize(this@ViewerActivity, size))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.more_details)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        val item = current ?: return
        when (MediaDelete.delete(this, listOf(item.uri))) {
            is MediaDelete.Result.Deleted -> afterDelete()
            is MediaDelete.Result.Confirming -> Unit
            is MediaDelete.Result.Failed ->
                Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Stays in the viewer and moves to the next image, rather than kicking the
     *  user back to the grid after every delete. */
    private fun afterDelete() {
        Toast.makeText(this, R.string.delete_done, Toast.LENGTH_SHORT).show()

        items = MediaQuery.load(this, allPhotos)
        if (items.isEmpty()) {
            finish()
            return
        }
        if (index > items.lastIndex) index = items.lastIndex
        show()
    }

    // The system delete confirmation reports back here.
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != MediaDelete.REQUEST_DELETE) return
        if (resultCode == RESULT_OK) afterDelete()
    }
}
