package com.doubletap.screenshot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast

class QuickSharePopup(private val context: Context) {

    private var popupView: LinearLayout? = null
    private var windowManager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var screenshotObserver: ContentObserver? = null
    private var latestScreenshotUri: Uri? = null

    fun listenForScreenshot() {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                try {
                    unregisterObserver()
                    findLatestScreenshot()?.let { screenshotUri ->
                        latestScreenshotUri = screenshotUri
                        handler.post { showPopup() }
                    }
                } catch (_: Exception) {
                }
            }
        }
        screenshotObserver = observer

        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )

        handler.postDelayed({ unregisterObserver() }, 5000)
    }

    private fun unregisterObserver() {
        screenshotObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        screenshotObserver = null
    }

    private fun findLatestScreenshot(): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }

    private fun showPopup() {
        // Re-entrant: the observer can deliver several onChange callbacks for a
        // single capture, and a second addView would leak the first overlay.
        if (popupView != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.popup_quick_share, null) as LinearLayout
        popupView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 200
        }

        view.findViewById<ImageButton>(R.id.btnShare).setOnClickListener {
            latestScreenshotUri?.let { uri -> shareScreenshot(uri) }
            dismiss()
        }

        view.findViewById<ImageButton>(R.id.btnBlur).setOnClickListener {
            latestScreenshotUri?.let { uri -> openBlurEditor(uri) }
            dismiss()
        }

        view.findViewById<ImageButton>(R.id.btnOcr).setOnClickListener {
            latestScreenshotUri?.let { uri -> OcrActivity.start(context, uri) }
            dismiss()
        }

        view.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener {
            latestScreenshotUri?.let { uri -> deleteScreenshot(uri) }
            dismiss()
        }

        view.findViewById<ImageButton>(R.id.btnDismiss).setOnClickListener {
            dismiss()
        }

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            popupView = null
            ScreenshotService.instance?.recordError("quick share popup", e)
            Toast.makeText(context, "Quick Share popup failed: $e", Toast.LENGTH_LONG).show()
            return
        }

        handler.postDelayed({ dismiss() }, 8000)
    }

    private fun shareScreenshot(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share screenshot").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun openBlurEditor(uri: Uri) {
        val intent = Intent(context, BlurEditorActivity::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun deleteScreenshot(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {}
    }

    fun dismiss() {
        popupView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        popupView = null
        handler.removeCallbacksAndMessages(null)
    }
}
