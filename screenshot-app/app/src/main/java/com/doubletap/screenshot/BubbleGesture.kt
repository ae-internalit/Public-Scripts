package com.doubletap.screenshot

import kotlin.math.hypot

/**
 * Decides whether a finger on the floating bubble is a tap, a drag or a hold.
 *
 * The three overlap badly in practice: moving the bubble out of the way starts
 * exactly like holding it to record, and a hold starts exactly like a tap. Time
 * is passed in rather than read, so the rules can be driven from a test instead
 * of being discovered on a phone.
 */
class BubbleGesture(
    private val touchSlopPx: Float,
    private val longPressMs: Long
) {

    enum class Phase {
        /** Nothing is touching the bubble. */
        IDLE,

        /** Down, but not yet far enough to be a drag or long enough to be a hold. */
        PRESSED,

        /** Moved past the slop: the user is repositioning the bubble. */
        DRAGGING,

        /** Held still past the threshold. */
        HELD
    }

    enum class Release {
        /** Released before it became anything else. */
        TAP,

        /** Finished repositioning. */
        DRAG_END,

        /** Let go of a hold. */
        HOLD_END,

        /** Released without a matching press. */
        NONE
    }

    var phase = Phase.IDLE
        private set

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    fun down(x: Float, y: Float, timeMs: Long) {
        phase = Phase.PRESSED
        downX = x
        downY = y
        downAt = timeMs
    }

    /**
     * Once a finger has travelled far enough it is a drag for good, even if it
     * comes back: otherwise lifting at the end of a sweep would fire a tap.
     */
    fun move(x: Float, y: Float, timeMs: Long): Phase {
        if (phase == Phase.IDLE || phase == Phase.DRAGGING) return phase

        if (hypot(x - downX, y - downY) > touchSlopPx) {
            phase = if (phase == Phase.HELD) Phase.HELD else Phase.DRAGGING
            return phase
        }
        return tick(timeMs)
    }

    /** Promotes a still finger to a hold once it has been down long enough. */
    fun tick(timeMs: Long): Phase {
        if (phase == Phase.PRESSED && timeMs - downAt >= longPressMs) {
            phase = Phase.HELD
        }
        return phase
    }

    fun up(): Release {
        val result = when (phase) {
            Phase.PRESSED -> Release.TAP
            Phase.DRAGGING -> Release.DRAG_END
            Phase.HELD -> Release.HOLD_END
            Phase.IDLE -> Release.NONE
        }
        phase = Phase.IDLE
        return result
    }

    fun cancel() {
        phase = Phase.IDLE
    }
}
