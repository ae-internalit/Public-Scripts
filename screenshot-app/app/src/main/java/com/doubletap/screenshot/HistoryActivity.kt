package com.doubletap.screenshot

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: HistoryAdapter
    private val screenshots = mutableListOf<ScreenshotItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        recyclerView = findViewById(R.id.historyRecycler)
        emptyText = findViewById(R.id.emptyText)
        val backBtn = findViewById<Button>(R.id.btnBack)
        val stitchBtn = findViewById<Button>(R.id.btnStitch)

        backBtn.setOnClickListener { finish() }
        stitchBtn.setOnClickListener { openStitch() }

        // A tap shows what this app can do with the screenshot. Handing it
        // straight to an external viewer hid every feature behind a long-press.
        adapter = HistoryAdapter(screenshots, object : HistoryAdapter.OnItemClickListener {
            override fun onClick(item: ScreenshotItem) {
                showOptions(item)
            }

            override fun onLongClick(item: ScreenshotItem) {
                showOptions(item)
            }
        })

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        if (hasMediaPermission()) {
            loadScreenshots()
        } else {
            requestMediaPermission()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MEDIA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                loadScreenshots()
            } else {
                emptyText.setText(R.string.history_needs_permission)
                emptyText.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            }
        }
    }

    private fun loadScreenshots() {
        screenshots.clear()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%Screenshots%", "%creenshot%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val date = cursor.getLong(dateCol)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                screenshots.add(ScreenshotItem(id, name, date, uri))
            }
        }

        adapter.notifyDataSetChanged()
        emptyText.visibility = if (screenshots.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (screenshots.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun viewScreenshot(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun showOptions(item: ScreenshotItem) {
        val options = arrayOf(
            "Copy Text (OCR)",
            "Edit — blur, draw, crop",
            "Share",
            "Open in photo viewer",
            "Delete"
        )
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> extractText(item.uri)
                    1 -> editScreenshot(item.uri)
                    2 -> shareScreenshot(item.uri)
                    3 -> viewScreenshot(item.uri)
                    4 -> deleteScreenshot(item)
                }
            }
            .show()
    }

    private fun shareScreenshot(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share"))
    }

    private fun extractText(uri: Uri) {
        val intent = Intent(this, BlurEditorActivity::class.java).apply {
            data = uri
            putExtra(BlurEditorActivity.EXTRA_RUN_OCR, true)
        }
        startActivity(intent)
    }

    private fun editScreenshot(uri: Uri) {
        val intent = Intent(this, BlurEditorActivity::class.java).apply {
            data = uri
        }
        startActivity(intent)
    }

    private fun deleteScreenshot(item: ScreenshotItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Screenshot")
            .setMessage("Delete ${item.name}?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    contentResolver.delete(item.uri, null, null)
                    loadScreenshots()
                } catch (_: Exception) {}
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openStitch() {
        val intent = Intent(this, StitchActivity::class.java)
        startActivity(intent)
    }
}

data class ScreenshotItem(
    val id: Long,
    val name: String,
    val date: Long,
    val uri: Uri
)

const val MEDIA_PERMISSION_REQUEST = 2001

fun mediaPermission(): String =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.READ_MEDIA_IMAGES
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }

fun android.app.Activity.hasMediaPermission(): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(this, mediaPermission()) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

fun android.app.Activity.requestMediaPermission() {
    androidx.core.app.ActivityCompat.requestPermissions(
        this, arrayOf(mediaPermission()), MEDIA_PERMISSION_REQUEST
    )
}
