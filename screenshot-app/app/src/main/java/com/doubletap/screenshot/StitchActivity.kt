package com.doubletap.screenshot

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class StitchActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var previewImage: ImageView
    private lateinit var selectedCount: TextView
    private lateinit var stitchBtn: Button
    private val screenshots = mutableListOf<ScreenshotItem>()
    private val selectedUris = mutableListOf<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stitch)

        recyclerView = findViewById(R.id.stitchRecycler)
        previewImage = findViewById(R.id.stitchPreview)
        selectedCount = findViewById(R.id.selectedCount)
        stitchBtn = findViewById(R.id.btnDoStitch)
        val backBtn = findViewById<Button>(R.id.btnStitchBack)
        val clearBtn = findViewById<Button>(R.id.btnClearSelection)

        backBtn.setOnClickListener { finish() }
        clearBtn.setOnClickListener { clearSelection() }
        stitchBtn.setOnClickListener { performStitch() }

        loadScreenshots()

        val adapter = StitchAdapter(screenshots) { item, selected ->
            if (selected) {
                selectedUris.add(item.uri)
            } else {
                selectedUris.remove(item.uri)
            }
            updateSelection()
        }

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = adapter
    }

    private fun loadScreenshots() {
        screenshots.clear()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
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
    }

    private fun updateSelection() {
        selectedCount.text = "${selectedUris.size} selected"
        stitchBtn.isEnabled = selectedUris.size >= 2
    }

    private fun clearSelection() {
        selectedUris.clear()
        updateSelection()
        (recyclerView.adapter as? StitchAdapter)?.clearSelection()
        previewImage.visibility = View.GONE
    }

    private fun performStitch() {
        if (selectedUris.size < 2) {
            Toast.makeText(this, "Select at least 2 screenshots", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmaps = mutableListOf<Bitmap>()
        for (uri in selectedUris) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { bitmaps.add(it) }
                }
            } catch (_: Exception) {}
        }

        if (bitmaps.size < 2) {
            Toast.makeText(this, "Failed to load images", Toast.LENGTH_SHORT).show()
            return
        }

        val maxWidth = bitmaps.maxOf { it.width }
        val totalHeight = bitmaps.sumOf { it.height }

        val result = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        var currentY = 0
        for (bitmap in bitmaps) {
            val x = (maxWidth - bitmap.width) / 2f
            canvas.drawBitmap(bitmap, x, currentY.toFloat(), null)
            currentY += bitmap.height
        }

        for (bitmap in bitmaps) {
            bitmap.recycle()
        }

        val uri = saveBitmapToGallery(result)
        result.recycle()

        if (uri != null) {
            Toast.makeText(this, "Stitched image saved", Toast.LENGTH_SHORT).show()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share stitched image"))
        } else {
            Toast.makeText(this, "Failed to save", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Stitched_${System.currentTimeMillis()}")
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

class StitchAdapter(
    private val items: List<ScreenshotItem>,
    private val onSelectionChanged: (ScreenshotItem, Boolean) -> Unit
) : RecyclerView.Adapter<StitchAdapter.ViewHolder>() {

    private val selectedIds = mutableSetOf<Long>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.screenshotThumbnail)
        val dateText: TextView = view.findViewById(R.id.screenshotDate)
        val checkMark: View = view.findViewById(R.id.checkMark)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_screenshot, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val isSelected = selectedIds.contains(item.id)

        holder.thumbnail.setImageURI(item.uri)
        holder.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
        holder.checkMark.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.itemView.alpha = if (isSelected) 0.7f else 1.0f

        val dateFormat = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
        holder.dateText.text = dateFormat.format(java.util.Date(item.date * 1000))

        holder.itemView.setOnClickListener {
            if (selectedIds.contains(item.id)) {
                selectedIds.remove(item.id)
                onSelectionChanged(item, false)
            } else {
                selectedIds.add(item.id)
                onSelectionChanged(item, true)
            }
            notifyItemChanged(position)
        }
    }

    override fun getItemCount() = items.size

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
    }
}
