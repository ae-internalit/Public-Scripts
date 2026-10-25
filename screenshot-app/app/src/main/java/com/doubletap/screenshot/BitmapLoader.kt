package com.doubletap.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Decodes an image down to a size that fits in memory.
 *
 * A screenshot is around 3 megapixels and decodes safely at full size. A photo
 * off the Pixel's main camera is 50, which is 200MB as a bitmap before the
 * editor makes its working copy. Anything that can be handed an arbitrary image
 * has to sub-sample, or it dies on the first real photo.
 */
object BitmapLoader {

    /** Roomy enough that a screenshot is never touched. */
    const val VIEW_PIXELS = 16_000_000L

    /** Lower, because the editor holds a second full copy while it draws. */
    const val EDIT_PIXELS = 12_000_000L

    fun decode(context: Context, uri: Uri, maxPixels: Long): Bitmap? {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > maxPixels) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}
