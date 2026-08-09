package io.github.maximepinard.maxkontroller.listeners

import android.view.MotionEvent
import android.view.View
import io.github.maximepinard.maxkontroller.PointerPump

/**
 * Turns one-finger drags on the trackpad into relative pointer movement.
 *
 * Multi-finger sequences are ignored here: two-finger gestures are scroll and are handled
 * by [GestureDetectListener], and mixing the two would fight over the pointer.
 *
 * Deltas are handed to [PointerPump] as floats rather than sent immediately. The pump caps
 * the report rate to the frame clock and carries the sub-pixel remainder, which is what
 * makes slow movement at low sensitivity work at all.
 */
class ViewListener(
    private val pointer: PointerPump
) : View.OnTouchListener {

    private var previousX: Float = 0f
    private var previousY: Float = 0f
    private var movementStopped = false

    /** Pointer speed multiplier; 1.0 is the raw touch delta. */
    var sensitivity: Float = 1f

    /**
     * Set false while the gyro pointer drives the cursor, so dragging on the pad does not
     * fight the tilt input. Taps, scroll and the click buttons keep working.
     */
    var movementEnabled: Boolean = true

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Seed the reference point, otherwise the first ACTION_MOVE of a gesture is
                // measured against wherever the previous one ended and jumps the pointer.
                pointer.reset()
                movementStopped = false
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // event.x follows pointer index 0, and lifting a finger renumbers the
                // indices. Treat it as a fresh reference point instead of reading the jump
                // as movement.
                previousX = x
                previousY = y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && movementEnabled) {
                    movementStopped = false
                    pointer.move((x - previousX) * sensitivity, (y - previousY) * sensitivity)
                } else if (!movementStopped) {
                    // A second finger arrived (scroll): stop feeding movement deltas, but
                    // only send the zeroing report once.
                    pointer.stop()
                    movementStopped = true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointer.stop()
        }

        previousX = x
        previousY = y
        return true
    }
}
