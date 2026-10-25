package com.doubletap.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tap, drag and hold all begin with a finger on the same button, so the rules
 * that tell them apart decide whether the bubble is usable or infuriating.
 * Getting this wrong means recordings start while you reposition it, or taps
 * are swallowed at the end of a drag.
 */
class BubbleGestureTest {

    private val slop = 30f
    private val longPress = 400L

    private fun gesture() = BubbleGesture(slop, longPress)

    @Test
    fun `a quick press and release is a tap`() {
        val g = gesture()
        g.down(100f, 100f, 0L)
        assertEquals(BubbleGesture.Phase.PRESSED, g.tick(100L))
        assertEquals(BubbleGesture.Release.TAP, g.up())
    }

    @Test
    fun `a still finger becomes a hold at the threshold`() {
        val g = gesture()
        g.down(100f, 100f, 0L)
        assertEquals("399ms is not yet a hold", BubbleGesture.Phase.PRESSED, g.tick(399L))
        assertEquals(BubbleGesture.Phase.HELD, g.tick(400L))
        assertEquals(BubbleGesture.Release.HOLD_END, g.up())
    }

    @Test
    fun `small wobble does not break a hold`() {
        val g = gesture()
        g.down(100f, 100f, 0L)
        // A finger resting on glass never stays perfectly still.
        assertEquals(BubbleGesture.Phase.PRESSED, g.move(108f, 104f, 200L))
        assertEquals(BubbleGesture.Phase.HELD, g.move(112f, 96f, 450L))
    }

    @Test
    fun `moving past the slop is a drag not a hold`() {
        val g = gesture()
        g.down(100f, 100f, 0L)
        assertEquals(BubbleGesture.Phase.DRAGGING, g.move(200f, 100f, 100L))

        // Still a drag well past the long-press threshold: repositioning the
        // bubble slowly must never start a recording.
        assertEquals(BubbleGesture.Phase.DRAGGING, g.move(240f, 130f, 900L))
        assertEquals(BubbleGesture.Release.DRAG_END, g.up())
    }

    @Test
    fun `a drag that returns home is still a drag`() {
        val g = gesture()
        g.down(100f, 100f, 0L)
        g.move(300f, 100f, 80L)
        // Back where it started — but the user was dragging, not tapping.
        assertEquals(BubbleGesture.Phase.DRAGGING, g.move(100f, 100f, 160L))
        assertEquals(BubbleGesture.Release.DRAG_END, g.up())
    }

    @Test
    fun `a hold that then moves stays a hold`() {
        val g = gesture()
        g.down(100f, 100f, 0L)
        assertEquals(BubbleGesture.Phase.HELD, g.tick(400L))
        // Recording already started; sliding a finger must not turn it into a drag.
        assertEquals(BubbleGesture.Phase.HELD, g.move(400f, 400f, 500L))
        assertEquals(BubbleGesture.Release.HOLD_END, g.up())
    }

    @Test
    fun `exactly at the slop edge is not yet a drag`() {
        val g = gesture()
        g.down(100f, 100f, 0L)
        assertEquals(BubbleGesture.Phase.PRESSED, g.move(100f + slop, 100f, 10L))
        assertEquals(BubbleGesture.Phase.DRAGGING, g.move(100f + slop + 1f, 100f, 20L))
    }

    @Test
    fun `releasing without a press does nothing`() {
        val g = gesture()
        assertEquals(BubbleGesture.Release.NONE, g.up())
    }

    @Test
    fun `cancel drops the gesture`() {
        val g = gesture()
        g.down(100f, 100f, 0L)
        g.cancel()
        assertEquals(BubbleGesture.Phase.IDLE, g.phase)
        assertEquals("a cancelled press must not fire a tap", BubbleGesture.Release.NONE, g.up())
    }

    @Test
    fun `movement after release is ignored`() {
        val g = gesture()
        g.down(100f, 100f, 0L)
        g.up()
        assertEquals(BubbleGesture.Phase.IDLE, g.move(500f, 500f, 50L))
    }

    @Test
    fun `each press starts clean`() {
        val g = gesture()
        g.down(100f, 100f, 0L)
        g.move(400f, 100f, 50L)
        g.up()

        // A drag must not leave the next tap looking like a drag.
        g.down(100f, 100f, 1000L)
        assertEquals(BubbleGesture.Release.TAP, g.up())
    }
}
