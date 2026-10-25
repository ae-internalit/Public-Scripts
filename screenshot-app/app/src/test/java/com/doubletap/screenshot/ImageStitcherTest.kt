package com.doubletap.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImageStitcherTest {

    /** Pixels for `height` rows where every pixel in a row equals its row label. */
    private fun rows(width: Int, labels: List<Int>): IntArray {
        val out = IntArray(width * labels.size)
        labels.forEachIndexed { y, label ->
            for (x in 0 until width) out[y * width + x] = label
        }
        return out
    }

    @Test
    fun `row hashes distinguish different rows`() {
        val pixels = rows(4, listOf(1, 2, 3))
        val hashes = ImageStitcher.rowHashes(pixels, width = 4, height = 3)

        assertEquals(3, hashes.size)
        assertNotEquals(hashes[0], hashes[1])
        assertNotEquals(hashes[1], hashes[2])
    }

    @Test
    fun `identical rows hash identically`() {
        val a = ImageStitcher.rowHashes(rows(4, listOf(7)), 4, 1)
        val b = ImageStitcher.rowHashes(rows(4, listOf(7)), 4, 1)
        assertEquals(a[0], b[0])
    }

    @Test
    fun `finds the overlapping tail`() {
        // prev ends with 8,9,10 and next starts with the same three rows.
        val prev = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val next = intArrayOf(8, 9, 10, 11, 12, 13)

        assertEquals(3, ImageStitcher.findOverlap(prev, next, minOverlap = 2))
    }

    @Test
    fun `reports no overlap when the view scrolled a whole screen`() {
        val prev = intArrayOf(1, 2, 3, 4)
        val next = intArrayOf(5, 6, 7, 8)

        assertEquals(0, ImageStitcher.findOverlap(prev, next, minOverlap = 2))
    }

    @Test
    fun `a full match means the view did not move`() {
        val same = intArrayOf(1, 2, 3, 4)

        assertEquals(4, ImageStitcher.findOverlap(same, same.copyOf(), minOverlap = 2))
    }

    @Test
    fun `short accidental matches are ignored below the minimum`() {
        // One shared row only; too little to trust as alignment.
        val prev = intArrayOf(1, 2, 3, 99)
        val next = intArrayOf(99, 5, 6, 7)

        assertEquals(0, ImageStitcher.findOverlap(prev, next, minOverlap = 2))
    }

    @Test
    fun `prefers the longest alignment when a filler row repeats`() {
        // Row 0 repeats throughout, as a blank margin would. The correct
        // alignment is the three-row tail, not the single repeated row.
        val prev = intArrayOf(0, 0, 5, 6, 7)
        val next = intArrayOf(5, 6, 7, 0, 0)

        assertEquals(3, ImageStitcher.findOverlap(prev, next, minOverlap = 2))
    }

    @Test
    fun `empty input has no overlap`() {
        assertEquals(0, ImageStitcher.findOverlap(intArrayOf(), intArrayOf(1, 2)))
        assertEquals(0, ImageStitcher.findOverlap(intArrayOf(1, 2), intArrayOf()))
    }

    @Test
    fun `stitched height drops the duplicated regions`() {
        // Three 100-row captures, each repeating 30 rows of the one before.
        val height = ImageStitcher.stitchedHeight(
            heights = listOf(100, 100, 100),
            overlaps = listOf(0, 30, 30)
        )
        assertEquals(100 + 70 + 70, height)
    }

    @Test
    fun `a capture that added nothing does not grow the result`() {
        val height = ImageStitcher.stitchedHeight(
            heights = listOf(100, 100),
            overlaps = listOf(0, 100)
        )
        assertEquals(100, height)
    }

    @Test
    fun `single capture keeps its height`() {
        assertEquals(100, ImageStitcher.stitchedHeight(listOf(100), listOf(0)))
    }

    @Test
    fun `no captures means no height`() {
        assertEquals(0, ImageStitcher.stitchedHeight(emptyList(), emptyList()))
    }
}
