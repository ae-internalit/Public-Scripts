package com.doubletap.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * Joins successive screenshots of a scrolling view into one tall image.
 *
 * Consecutive captures overlap by however much the view did not scroll, and
 * that amount is not known in advance: a list may scroll a full screen, or
 * stop short at the end of its content. The overlap is therefore measured
 * from the pixels.
 *
 * The matching works on per-row hashes rather than Bitmaps so it can be
 * tested on the JVM without a device.
 */
object ImageStitcher {

    /** Rows must agree over at least this many lines to count as an overlap. */
    const val MIN_OVERLAP_ROWS = 24

    /**
     * One hash per row of a row-major pixel array.
     *
     * Rows are hashed rather than compared directly so the search below stays
     * linear in the number of rows instead of rows x width.
     */
    fun rowHashes(pixels: IntArray, width: Int, height: Int): IntArray {
        require(width > 0) { "width must be positive" }
        require(pixels.size >= width * height) { "pixel array smaller than width*height" }

        val hashes = IntArray(height)
        for (y in 0 until height) {
            var h = 17
            val base = y * width
            for (x in 0 until width) {
                h = h * 31 + pixels[base + x]
            }
            hashes[y] = h
        }
        return hashes
    }

    /**
     * How many rows at the top of [next] repeat the bottom of [prev].
     *
     * Returns 0 when the images do not overlap, which happens when the view
     * scrolled by more than a full screen. Returns [next].size when nothing
     * moved at all, the signal that the end of the content has been reached.
     */
    fun findOverlap(
        prev: IntArray,
        next: IntArray,
        minOverlap: Int = MIN_OVERLAP_ROWS
    ): Int {
        if (prev.isEmpty() || next.isEmpty()) return 0

        // Prefer the largest overlap: a short run of identical rows (a blank
        // margin, say) can match in many places, and the longest match is the
        // one that actually aligns the content.
        var candidate = minOf(prev.size, next.size)
        while (candidate >= minOverlap) {
            var matches = true
            val prevStart = prev.size - candidate
            for (i in 0 until candidate) {
                if (prev[prevStart + i] != next[i]) {
                    matches = false
                    break
                }
            }
            if (matches) return candidate
            candidate--
        }
        return 0
    }

    /**
     * Total height of the stitched image given each capture's height and the
     * overlap measured against the capture before it.
     */
    fun stitchedHeight(heights: List<Int>, overlaps: List<Int>): Int {
        require(overlaps.size == heights.size) { "one overlap per capture" }
        if (heights.isEmpty()) return 0

        var total = heights[0]
        for (i in 1 until heights.size) {
            val contributed = heights[i] - overlaps[i]
            if (contributed > 0) total += contributed
        }
        return total
    }

    /**
     * Stitches [shots] top to bottom, dropping the duplicated region between
     * each pair. Returns null when there is nothing to join.
     */
    fun stitch(shots: List<Bitmap>): Bitmap? {
        if (shots.isEmpty()) return null
        if (shots.size == 1) return shots[0]

        // A rotation mid-capture would change the width, and slicing a bitmap
        // to a width it does not have throws.
        val width = shots[0].width
        val usable = shots.filter { it.width == width }
        if (usable.size < 2) return usable.firstOrNull()

        val overlaps = ArrayList<Int>(usable.size)
        overlaps.add(0)

        var prevHashes = hashesOf(usable[0], width)
        for (i in 1 until usable.size) {
            val nextHashes = hashesOf(usable[i], width)
            overlaps.add(findOverlap(prevHashes, nextHashes))
            prevHashes = nextHashes
        }

        val total = stitchedHeight(usable.map { it.height }, overlaps)
        if (total <= 0) return null

        val result = Bitmap.createBitmap(width, total, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        canvas.drawBitmap(usable[0], 0f, 0f, null)
        var y = usable[0].height

        for (i in 1 until usable.size) {
            val shot = usable[i]
            val skip = overlaps[i]
            if (skip >= shot.height) continue
            val slice = Bitmap.createBitmap(shot, 0, skip, width, shot.height - skip)
            canvas.drawBitmap(slice, 0f, y.toFloat(), null)
            y += slice.height
            if (slice != shot) slice.recycle()
        }

        return result
    }

    private fun hashesOf(bitmap: Bitmap, width: Int): IntArray {
        val pixels = IntArray(width * bitmap.height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, bitmap.height)
        return rowHashes(pixels, width, bitmap.height)
    }
}
