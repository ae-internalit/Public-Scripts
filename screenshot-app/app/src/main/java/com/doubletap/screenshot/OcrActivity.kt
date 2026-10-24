package com.doubletap.screenshot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Tap individual lines of recognised text and copy only those. Copying the
 * whole page is one button away, but it is no longer the only option.
 */
class OcrActivity : AppCompatActivity() {

    private lateinit var selectView: OcrSelectView
    private lateinit var statusText: TextView
    private lateinit var copyButton: Button
    private lateinit var selectAllButton: Button

    companion object {
        fun start(context: Context, uri: Uri) {
            val intent = Intent(context, OcrActivity::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocr)

        selectView = findViewById(R.id.ocrSelectView)
        statusText = findViewById(R.id.ocrStatus)
        copyButton = findViewById(R.id.ocrCopyButton)
        selectAllButton = findViewById(R.id.ocrSelectAllButton)

        findViewById<Button>(R.id.ocrCloseButton).setOnClickListener { finish() }

        selectView.onSelectionChanged = { updateStatus() }

        copyButton.setOnClickListener { copySelection() }

        selectAllButton.setOnClickListener {
            val selectingAll = selectView.selectedCount() < 1
            selectView.setAllSelected(selectingAll)
        }

        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        recognise(uri)
    }

    private fun recognise(uri: Uri) {
        statusText.setText(R.string.ocr_working)

        val bitmap = try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }

        if (bitmap == null) {
            statusText.setText(R.string.ocr_failed)
            return
        }

        selectView.setBitmap(bitmap)

        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    val regions = mutableListOf<OcrSelectView.TextRegion>()
                    for (block in text.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox ?: continue
                            regions.add(OcrSelectView.TextRegion(box, line.text))
                        }
                    }
                    selectView.setRegions(regions)
                    updateStatus()
                }
                .addOnFailureListener {
                    statusText.setText(R.string.ocr_failed)
                }
        } catch (_: Exception) {
            statusText.setText(R.string.ocr_failed)
        }
    }

    private fun updateStatus() {
        if (!selectView.hasRegions()) {
            statusText.setText(R.string.ocr_no_text)
            copyButton.isEnabled = false
            selectAllButton.visibility = View.GONE
            return
        }

        selectAllButton.visibility = View.VISIBLE
        val count = selectView.selectedCount()
        copyButton.isEnabled = count > 0
        statusText.text = if (count == 0) {
            getString(R.string.ocr_tap_hint)
        } else {
            resources.getQuantityString(R.plurals.ocr_selected, count, count)
        }
        selectAllButton.setText(
            if (count == 0) R.string.ocr_select_all else R.string.ocr_select_none
        )
    }

    private fun copySelection() {
        val text = selectView.selectedText()
        if (text.isEmpty()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OCR", text))
        Toast.makeText(this, R.string.ocr_copied, Toast.LENGTH_SHORT).show()
    }
}
