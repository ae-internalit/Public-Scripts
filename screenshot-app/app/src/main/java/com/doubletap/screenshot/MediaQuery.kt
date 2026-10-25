package com.doubletap.screenshot

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

/**
 * The one definition of what the gallery lists.
 *
 * The viewer needs the same list the grid shows in order to swipe through it,
 * and two copies of this query would drift the moment either changed.
 */
object MediaQuery {

    fun load(context: Context, allPhotos: Boolean): List<ScreenshotItem> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )

        val selection = if (allPhotos) null else
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = if (allPhotos) null else arrayOf("%Screenshots%", "%creenshot%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val items = mutableListOf<ScreenshotItem>()

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    items += ScreenshotItem(
                        id = id,
                        name = cursor.getString(nameCol) ?: "",
                        date = cursor.getLong(dateCol),
                        uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        )
                    )
                }
            }
        } catch (e: Exception) {
            ScreenshotService.instance?.recordError("media query", e)
        }

        return items
    }
}
