package com.doubletap.screenshot

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: HistoryAdapter
    private lateinit var selectBtn: Button
    private lateinit var exportBtn: Button
    private val screenshots = mutableListOf<ScreenshotItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        recyclerView = findViewById(R.id.historyRecycler)
        emptyText = findViewById(R.id.emptyText)
        val backBtn = findViewById<Button>(R.id.btnBack)
        selectBtn = findViewById(R.id.btnSelect)
        exportBtn = findViewById(R.id.btnExportPdf)

        backBtn.setOnClickListener { finish() }
        selectBtn.setOnClickListener { toggleSelectionMode() }
        exportBtn.setOnClickListener { exportSelectedAsPdf() }

        // A tap opens the screenshot; every action lives on the viewer's own
        // bottom bar. The menu that used to replace the tap meant there was no
        // way to simply look at a screenshot.
        adapter = HistoryAdapter(screenshots, object : HistoryAdapter.OnItemClickListener {
            override fun onClick(item: ScreenshotItem) {
                ViewerActivity.start(this@HistoryActivity, item.uri, item.name)
            }

            // Long-press is how the PDF export gets found: it turns on
            // selection and ticks the screenshot that was pressed.
            override fun onLongClick(item: ScreenshotItem) {
                if (!adapter.selectionMode) toggleSelectionMode()
                adapter.select(item.id)
                exportBtn.isEnabled = true
                exportBtn.text = resources.getQuantityString(
                    R.plurals.export_pdf_count, adapter.selected.size, adapter.selected.size
                )
            }

            override fun onSelectionChanged(count: Int) {
                exportBtn.isEnabled = count > 0
                exportBtn.text = if (count == 0) {
                    getString(R.string.export_pdf)
                } else {
                    resources.getQuantityString(R.plurals.export_pdf_count, count, count)
                }
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

    private fun toggleSelectionMode() {
        adapter.selectionMode = !adapter.selectionMode
        adapter.clearSelection()

        val on = adapter.selectionMode
        selectBtn.setText(if (on) R.string.select_done else R.string.select_button)
        exportBtn.visibility = if (on) View.VISIBLE else View.GONE
        exportBtn.isEnabled = false
        exportBtn.setText(R.string.export_pdf)
        if (on) Toast.makeText(this, R.string.select_hint, Toast.LENGTH_SHORT).show()
    }

    private fun exportSelectedAsPdf() {
        val uris = adapter.selectedUris()
        if (uris.isEmpty()) return

        exportBtn.isEnabled = false
        exportBtn.setText(R.string.export_pdf_working)

        // A dozen full-size screenshots is far too slow for the main thread.
        Thread {
            val result = PdfExporter.export(this, uris)
            runOnUiThread {
                if (result == null) {
                    exportBtn.isEnabled = true
                    exportBtn.setText(R.string.export_pdf)
                    Toast.makeText(this, R.string.export_pdf_failed, Toast.LENGTH_LONG).show()
                } else {
                    toggleSelectionMode()
                    Toast.makeText(this, R.string.export_pdf_saved, Toast.LENGTH_LONG).show()
                    openPdf(result)
                }
            }
        }.start()
    }

    private fun openPdf(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    // The system delete confirmation reports back here.
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != MediaDelete.REQUEST_DELETE) return
        if (resultCode == RESULT_OK) {
            Toast.makeText(this, R.string.delete_done, Toast.LENGTH_SHORT).show()
        }
        loadScreenshots()
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
