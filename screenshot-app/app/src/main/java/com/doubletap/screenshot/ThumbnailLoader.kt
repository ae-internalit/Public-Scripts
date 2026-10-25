package com.doubletap.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Grid thumbnails, off the main thread and cached.
 *
 * The gallery used to call ImageView.setImageURI() in onBindViewHolder, which
 * decodes the whole file synchronously while the list is scrolling. That was
 * merely slow for screenshots; with camera photos in the list it stutters badly
 * and runs the app out of memory. MediaStore already keeps small thumbnails, so
 * ask it for one instead of decoding the original.
 */
object ThumbnailLoader {

    private const val TARGET = 400

    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    // Sized in bytes rather than entries: a 400px thumbnail is about 640KB, so
    // a count-based limit would mean very different totals on different phones.
    private val cache = object : LruCache<Long, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    /**
     * Binding is racy by nature: a recycled view may be asked for a second
     * image before the first arrives. The id is stashed on the view and checked
     * again on delivery, so a late decode cannot paint over a reused cell.
     */
    fun load(view: ImageView, id: Long, uri: Uri) {
        view.setTag(R.id.thumbnail_id, id)

        val cached = cache.get(id)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }

        view.setImageDrawable(null)
        val context = view.context.applicationContext

        executor.execute {
            val bitmap = decode(context, uri) ?: return@execute
            cache.put(id, bitmap)
            main.post {
                if (view.getTag(R.id.thumbnail_id) == id) view.setImageBitmap(bitmap)
            }
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(TARGET, TARGET), null)
        } else {
            BitmapLoader.decode(context, uri, (TARGET * TARGET).toLong())
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}
