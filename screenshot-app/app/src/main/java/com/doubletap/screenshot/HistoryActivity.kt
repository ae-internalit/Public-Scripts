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

        adapter = HistoryAdapter(screenshots, object : HistoryAdapter.OnItemClickListener {
            override fun onClick(item: ScreenshotItem) {
                viewScreenshot(item.uri)
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
        loadScreenshots()
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

        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Screenshots%")
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
        val options = arrayOf("Share", "Edit", "Delete")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> shareScreenshot(item.uri)
                    1 -> editScreenshot(item.uri)
                    2 -> deleteScreenshot(item)
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
