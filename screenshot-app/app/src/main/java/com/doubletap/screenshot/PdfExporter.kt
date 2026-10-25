package com.doubletap.screenshot

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes selected screenshots into a single PDF, one image per page. */
object PdfExporter {

    /**
     * Returns the PDF's uri, or null if nothing could be written.
     *
     * Images that fail to decode are skipped rather than aborting the export:
     * one unreadable file should not cost the user the other nine pages.
     */
    fun export(context: Context, images: List<Uri>): Uri? {
        if (images.isEmpty()) return null

        val document = PdfDocument()
        var pagesWritten = 0

        try {
            images.forEachIndexed { index, uri ->
                val bitmap = decode(context, uri) ?: return@forEachIndexed

                val fit = PdfLayout.fit(bitmap.width, bitmap.height)
                val pageInfo = PdfDocument.PageInfo.Builder(
                    PdfLayout.PAGE_WIDTH, PdfLayout.PAGE_HEIGHT, index + 1
                ).create()

                val page = document.startPage(pageInfo)
                page.canvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(fit.left, fit.top, fit.right, fit.bottom),
                    null
                )
                document.finishPage(page)
                pagesWritten++
                bitmap.recycle()
            }

            if (pagesWritten == 0) return null
            return write(context, document)
        } catch (e: Exception) {
            ScreenshotService.instance?.recordError("pdf export", e)
            return null
        } finally {
            document.close()
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            // Screenshots are far larger than a 72dpi page needs, and decoding
            // a dozen of them at full size risks running out of memory.
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    } catch (_: Exception) {
        null
    }

    private fun write(context: Context, document: PdfDocument): Uri? {
        // MediaStore.Downloads arrived in API 29 and the app supports 28.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return writeLegacy(document)

        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "Screenshots_$stamp.pdf")
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return null

        context.contentResolver.openOutputStream(uri)?.use { out ->
            document.writeTo(out)
        } ?: return null

        return uri
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(document: PdfDocument): Uri? = try {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = java.io.File(dir, "Screenshots_$stamp.pdf")
        file.outputStream().use { document.writeTo(it) }
        Uri.fromFile(file)
    } catch (_: Exception) {
        null
    }
}
