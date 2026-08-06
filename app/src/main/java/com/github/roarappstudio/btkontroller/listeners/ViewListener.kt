package com.github.roarappstudio.btkontroller.listeners

import android.view.MotionEvent
import android.view.View
import com.github.roarappstudio.btkontroller.senders.RelativeMouseSender
import kotlin.math.roundToInt

/**
 * Turns one-finger drags on the trackpad into relative pointer movement.
 *
 * Multi-finger sequences are ignored here: two-finger gestures are scroll and are handled
 * by [GestureDetectListener], and mixing the two would fight over the pointer.
 */
class ViewListener(
    private val rMouseSender: RelativeMouseSender
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
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && movementEnabled) {
                    movementStopped = false

                    val dx = ((x - previousX) * sensitivity).roundToInt()
                    val dy = ((y - previousY) * sensitivity).roundToInt()
                    rMouseSender.sendMove(dx, dy)
                } else if (!movementStopped) {
                    // A second finger arrived (scroll): stop feeding movement deltas, but
                    // only send the zeroing report once.
                    rMouseSender.stopMove()
                    movementStopped = true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> rMouseSender.stopMove()
        }

        previousX = x
        previousY = y
        return true
    }
}
