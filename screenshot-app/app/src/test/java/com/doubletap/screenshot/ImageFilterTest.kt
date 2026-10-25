package com.doubletap.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter maths, which is why ImageFilter returns a FloatArray instead of
 * an android.graphics.ColorMatrix: a wrong matrix silently recolours every
 * saved image, and a stub class in unit tests would prove nothing.
 */
class ImageFilterTest {

    private val black = Triple(0f, 0f, 0f)
    private val white = Triple(255f, 255f, 255f)
    private val mid = Triple(127.5f, 127.5f, 127.5f)

    private fun run(filter: ImageFilter, colour: Triple<Float, Float, Float>) =
        ImageFilter.apply(filter.matrix()!!, colour.first, colour.second, colour.third)

    @Test
    fun `original does no work`() {
        assertNull("Original must skip the filter entirely", ImageFilter.NONE.matrix())
    }

    @Test
    fun `every filter is a well formed colour matrix`() {
        for (filter in ImageFilter.values()) {
            val m = filter.matrix() ?: continue
            assertEquals("${filter.name} must have 20 entries", 20, m.size)

            // The alpha row must be untouched, or transparent pixels get mangled.
            assertEquals("${filter.name} alpha row", 0f, m[15], 0f)
            assertEquals("${filter.name} alpha row", 0f, m[16], 0f)
            assertEquals("${filter.name} alpha row", 0f, m[17], 0f)
            assertEquals("${filter.name} alpha row", 1f, m[18], 0f)
            assertEquals("${filter.name} alpha row", 0f, m[19], 0f)
        }
    }

    @Test
    fun `black and white leaves no colour behind`() {
        for (colour in listOf(
            Triple(255f, 0f, 0f), Triple(0f, 255f, 0f),
            Triple(0f, 0f, 255f), Triple(12f, 200f, 90f)
        )) {
            val (r, g, b) = run(ImageFilter.MONO, colour)
            assertEquals("red and green must match for $colour", r, g, 0.01f)
            assertEquals("green and blue must match for $colour", g, b, 0.01f)
        }
    }

    @Test
    fun `black and white keeps the ends of the range`() {
        val (r, _, _) = run(ImageFilter.MONO, white)
        assertEquals(255f, r, 0.5f)
        assertEquals(0f, run(ImageFilter.MONO, black).first, 0.5f)
    }

    @Test
    fun `contrast pivots on mid grey`() {
        val (r, g, b) = run(ImageFilter.CONTRAST, mid)
        assertEquals("mid grey must not move", 127.5f, r, 0.01f)
        assertEquals(127.5f, g, 0.01f)
        assertEquals(127.5f, b, 0.01f)

        val dark = run(ImageFilter.CONTRAST, Triple(80f, 80f, 80f)).first
        val light = run(ImageFilter.CONTRAST, Triple(180f, 180f, 180f)).first
        assertTrue("darks must get darker, got $dark", dark < 80f)
        assertTrue("lights must get lighter, got $light", light > 180f)
    }

    @Test
    fun `brighter lifts every channel equally and clamps`() {
        val (r, g, b) = run(ImageFilter.BRIGHT, Triple(100f, 100f, 100f))
        assertEquals(132f, r, 0.01f)
        assertEquals(132f, g, 0.01f)
        assertEquals(132f, b, 0.01f)

        assertEquals("must not overflow past white", 255f, run(ImageFilter.BRIGHT, white).first, 0.01f)
    }

    @Test
    fun `warm and cool push opposite ends`() {
        val warm = run(ImageFilter.WARM, Triple(120f, 120f, 120f))
        assertTrue("warm must add red", warm.first > 120f)
        assertTrue("warm must cut blue", warm.third < 120f)

        val cool = run(ImageFilter.COOL, Triple(120f, 120f, 120f))
        assertTrue("cool must cut red", cool.first < 120f)
        assertTrue("cool must add blue", cool.third > 120f)

        assertEquals("neither may shift green", 120f, warm.second, 0.01f)
        assertEquals("neither may shift green", 120f, cool.second, 0.01f)
    }

    @Test
    fun `vivid pushes colour away from grey without moving grey`() {
        val grey = run(ImageFilter.VIVID, Triple(100f, 100f, 100f))
        assertEquals("grey has no colour to boost", 100f, grey.first, 0.5f)
        assertEquals(100f, grey.second, 0.5f)
        assertEquals(100f, grey.third, 0.5f)

        val before = Triple(150f, 100f, 100f)
        val after = run(ImageFilter.VIVID, before)
        assertTrue("a red tint must get redder", after.first - after.second > 50f)
    }

    @Test
    fun `every filter has a label for the picker`() {
        for (filter in ImageFilter.values()) {
            assertTrue("${filter.name} needs a label", filter.label.isNotBlank())
        }
        assertNotNull(ImageFilter.NONE.label)
    }
}
