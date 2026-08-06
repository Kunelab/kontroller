package com.github.roarappstudio.btkontroller

import android.view.Choreographer
import com.github.roarappstudio.btkontroller.senders.RelativeMouseSender
import kotlin.math.roundToInt

/**
 * Rate-limits pointer movement to one HID report per displayed frame, and keeps the
 * sub-pixel remainder that rounding would otherwise throw away.
 *
 * Two problems, one fix.
 *
 * **Too many reports.** Movement used to be sent straight from `ACTION_MOVE`. Phones sample
 * touch at 120-240 Hz while a Bluetooth HID link realistically carries 50-100 reports a
 * second, so the trackpad was overrunning the link by two to four times. Every one of those
 * is a binder transaction into the Bluetooth stack plus an L2CAP packet, and the excess just
 * queues -- which is what made the pointer keep travelling after a fast flick had already
 * stopped. Deltas are now summed and flushed once per frame.
 *
 * **Lost sub-pixel movement.** The delta was rounded to an Int per touch event, so at 25%
 * sensitivity a 3 px finger movement became 1 px and a 1 px movement became nothing at all.
 * Slow precise pointing, the thing a trackpad is actually for, did not work. [pending] keeps
 * the fraction across frames, so ten 0.3 px events now produce 3 px of travel instead of 0.
 *
 * Both callers -- the trackpad ([listeners.ViewListener]) and the gyro pointer
 * ([GyroPointer]) -- deliver on the main thread, so no synchronisation is needed and
 * [Choreographer] is safe to use directly.
 */
class PointerPump(private val sender: RelativeMouseSender) : Choreographer.FrameCallback {

    private var pendingX = 0f
    private var pendingY = 0f
    private var scheduled = false

    /** Adds movement to be sent on the next frame. */
    fun move(dx: Float, dy: Float) {
        pendingX += dx
        pendingY += dy
        if (!scheduled) {
            scheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * Ends the gesture: emits whatever whole pixels are left and clears the remainder.
     *
     * A report is sent even when there is nothing left to move, which preserves the old
     * `stopMove()` contract -- the shared report object also carries button and scroll
     * state, and the end of a gesture is when a zeroed scroll needs to reach the host.
     */
    fun stop() {
        if (scheduled) {
            Choreographer.getInstance().removeFrameCallback(this)
            scheduled = false
        }
        val dx = pendingX.roundToInt()
        val dy = pendingY.roundToInt()
        pendingX = 0f
        pendingY = 0f
        sender.sendMove(dx, dy)
    }

    /** Throws away pending movement without sending anything. */
    fun reset() {
        if (scheduled) {
            Choreographer.getInstance().removeFrameCallback(this)
            scheduled = false
        }
        pendingX = 0f
        pendingY = 0f
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false

        // Truncate rather than round: the fraction stays in `pending` and is carried into
        // the next frame instead of being rounded away every time.
        val dx = pendingX.toInt()
        val dy = pendingY.toInt()
        if (dx == 0 && dy == 0) return

        pendingX -= dx
        pendingY -= dy
        sender.sendMove(dx, dy)
    }
}
