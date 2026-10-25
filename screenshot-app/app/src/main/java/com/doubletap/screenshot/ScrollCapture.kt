package com.doubletap.screenshot

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures a view that is taller than the screen by scrolling it a screenful
 * at a time and joining the results.
 *
 * Each pass takes a screenshot, scrolls the first scrollable node forward, and
 * waits for the scroll to settle before the next. It stops when the content
 * stops moving, which is how the end of a list announces itself.
 */
class ScrollCapture(private val service: AccessibilityService) {

    companion object {
        private const val MAX_CAPTURES = 12

        /** The system throttles takeScreenshot, and scrolls need time to settle. */
        private const val SETTLE_MS = 700L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val shots = mutableListOf<Bitmap>()
    private var running = false

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun start(onFinished: (String) -> Unit) {
        if (running) return
        if (!isSupported()) {
            onFinished(service.getString(R.string.scroll_unsupported))
            return
        }
        running = true
        shots.clear()
        captureStep(onFinished)
    }

    private fun captureStep(onFinished: (String) -> Unit) {
        capture { bitmap ->
            if (bitmap == null) {
                finish(onFinished)
                return@capture
            }
            shots.add(bitmap)

            if (shots.size >= MAX_CAPTURES) {
                finish(onFinished)
                return@capture
            }

            val scrollable = findScrollable(service.rootInActiveWindow)
            if (scrollable == null) {
                finish(onFinished)
                return@capture
            }

            val scrolled = try {
                scrollable.performAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                )
            } catch (_: Exception) {
                false
            } finally {
                try {
                    @Suppress("DEPRECATION")
                    scrollable.recycle()
                } catch (_: Exception) {
                }
            }

            if (!scrolled) {
                finish(onFinished)
            } else {
                handler.postDelayed({ captureStep(onFinished) }, SETTLE_MS)
            }
        }
    }

    private fun capture(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }
        try {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                { it.run() },
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val bitmap = try {
                            val hardware = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )
                            // The hardware bitmap cannot be read back or drawn
                            // into another bitmap, and stitching needs both.
                            hardware?.copy(Bitmap.Config.ARGB_8888, false).also {
                                hardware?.recycle()
                            }
                        } catch (_: Exception) {
                            null
                        } finally {
                            try {
                                result.hardwareBuffer.close()
                            } catch (_: Exception) {
                            }
                        }
                        handler.post { onResult(bitmap) }
                    }

                    override fun onFailure(errorCode: Int) {
                        handler.post { onResult(null) }
                    }
                }
            )
        } catch (_: Exception) {
            onResult(null)
        }
    }

    private fun findScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        // Breadth first: the outermost scrollable container is the one the user
        // thinks of as "the page", and an inner one would scroll the wrong thing.
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun finish(onFinished: (String) -> Unit) {
        running = false

        if (shots.size < 2) {
            recycleAll()
            onFinished(service.getString(R.string.scroll_nothing_to_join))
            return
        }

        val stitched = try {
            ImageStitcher.stitch(shots)
        } catch (e: Exception) {
            ScreenshotService.instance?.recordError("scroll capture stitch", e)
            null
        }

        if (stitched == null) {
            recycleAll()
            onFinished(service.getString(R.string.scroll_failed))
            return
        }

        val saved = save(stitched)
        val count = shots.size
        recycleAll()
        if (stitched != null && !stitched.isRecycled) stitched.recycle()

        onFinished(
            if (saved) service.getString(R.string.scroll_saved, count)
            else service.getString(R.string.scroll_failed)
        )
    }

    private fun recycleAll() {
        shots.forEach { if (!it.isRecycled) it.recycle() }
        shots.clear()
    }

    private fun save(bitmap: Bitmap): Boolean {
        return try {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_${stamp}_scrolling.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Screenshots"
                )
            }
            val uri = service.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return false

            service.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return false
            true
        } catch (e: Exception) {
            ScreenshotService.instance?.recordError("scroll capture save", e)
            false
        }
    }
}
