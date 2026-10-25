package com.doubletap.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutTest {

    private val pageW = PdfLayout.PAGE_WIDTH
    private val pageH = PdfLayout.PAGE_HEIGHT
    private val margin = PdfLayout.MARGIN

    @Test
    fun `a tall screenshot is limited by page height`() {
        val fit = PdfLayout.fit(imageWidth = 1080, imageHeight = 2400)

        assertEquals(pageH - margin * 2, fit.height)
        assertTrue("must not exceed the printable width", fit.width <= pageW - margin * 2)
    }

    @Test
    fun `a wide image is limited by page width`() {
        val fit = PdfLayout.fit(imageWidth = 4000, imageHeight = 1000)

        assertEquals(pageW - margin * 2, fit.width)
        assertTrue("must not exceed the printable height", fit.height <= pageH - margin * 2)
    }

    @Test
    fun `aspect ratio is preserved`() {
        val fit = PdfLayout.fit(imageWidth = 1000, imageHeight = 2000)

        val original = 1000.0 / 2000.0
        val scaled = fit.width.toDouble() / fit.height
        assertEquals(original, scaled, 0.01)
    }

    @Test
    fun `small images are not upscaled`() {
        val fit = PdfLayout.fit(imageWidth = 100, imageHeight = 100)

        assertEquals(100, fit.width)
        assertEquals(100, fit.height)
    }

    @Test
    fun `the image is centred inside the margins`() {
        val fit = PdfLayout.fit(imageWidth = 100, imageHeight = 100)

        // The leftover space is often odd, and page coordinates are integers,
        // so the two gaps can differ by one. Anything larger is off-centre.
        val leftGap = fit.left - margin
        val rightGap = (pageW - margin) - fit.right
        assertTrue(
            "off-centre horizontally: $leftGap vs $rightGap",
            kotlin.math.abs(leftGap - rightGap) <= 1
        )

        val topGap = fit.top - margin
        val bottomGap = (pageH - margin) - fit.bottom
        assertTrue(
            "off-centre vertically: $topGap vs $bottomGap",
            kotlin.math.abs(topGap - bottomGap) <= 1
        )
    }

    @Test
    fun `the image always stays inside the page`() {
        val cases = listOf(
            1080 to 2400, 2400 to 1080, 50 to 50, 1 to 9999, 9999 to 1
        )
        for ((w, h) in cases) {
            val fit = PdfLayout.fit(w, h)
            assertTrue("$w x $h overflows left", fit.left >= margin)
            assertTrue("$w x $h overflows top", fit.top >= margin)
            assertTrue("$w x $h overflows right", fit.right <= pageW - margin)
            assertTrue("$w x $h overflows bottom", fit.bottom <= pageH - margin)
        }
    }

    @Test
    fun `an extreme aspect ratio still produces a visible size`() {
        val fit = PdfLayout.fit(imageWidth = 1, imageHeight = 20000)

        assertTrue("width collapsed to nothing", fit.width >= 1)
        assertTrue("height collapsed to nothing", fit.height >= 1)
    }
}
