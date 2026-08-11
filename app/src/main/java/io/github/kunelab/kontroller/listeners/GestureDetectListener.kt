package io.github.kunelab.kontroller.listeners

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.kunelab.kontroller.senders.RelativeMouseSender
import kotlin.math.abs

/**
 * Taps, double taps, two-finger taps and two-finger scroll on the trackpad.
 *
 * [GestureDetector] handles the single-pointer gestures; the multi-pointer ones need the raw
 * event stream, which it does not expose, so [onTouchEvent] sees every event first (see
 * [io.github.kunelab.kontroller.extraLibraries.CustomGestureDetector]).
 *
 * ### The two-finger tap
 *
 * This was the app's most-reported broken gesture, and it was broken three ways. It timed
 * the window from the *first* finger touching down, so the clock was already running before
 * the second finger arrived; the window was [ViewConfiguration.getTapTimeout] (100 ms),
 * which is a threshold for a single finger and far too tight for landing and lifting two;
 * and there was no movement guard, so the start of a two-finger scroll could be read as a
 * tap. All three are fixed here: the window starts when the *second* finger lands, it is
 * [ViewConfiguration.getDoubleTapTimeout] (300 ms), and any pointer travelling further than
 * the touch slop cancels the candidate.
 *
 * ### Suppressing the single tap that follows
 *
 * A two-finger tap ends with a normal one-finger up, so [GestureDetector] reports a single
 * tap for it too. That tap is identified by [MotionEvent.getDownTime], which is the same for
 * every event in one gesture, so the right click records the gesture it fired for and
 * [onSingleTapConfirmed] drops exactly that one. The previous sticky boolean could not
 * expire: if the gesture ended as a drag instead of a tap, nothing consumed the flag and it
 * swallowed the *next* genuine click.
 */
class GestureDetectListener(
    context: Context,
    private val mouse: RelativeMouseSender
) : GestureDetector.SimpleOnGestureListener() {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())

    private var pointerCount = 0

    // --- two-finger tap ---------------------------------------------------------------
    private var twoFingerCandidate = false
    private var secondFingerDownAt = 0L
    private var anchorX = FloatArray(2)
    private var anchorY = FloatArray(2)

    /** downTime of the gesture that already produced a right click, or 0. */
    private var rightClickedGesture = 0L

    // --- double-tap-and-hold (drag) ---------------------------------------------------
    private var holdingLeftButton = false
    private var holdArmed = false
    private val startHold = Runnable {
        if (holdArmed && !holdingLeftButton) {
            holdingLeftButton = true
            mouse.sendLeftClickOn()
        }
    }

    // --- scroll -----------------------------------------------------------------------
    private var scrolled = false

    /**
     * Raw event stream, seen before [GestureDetector] gets it. Returns true only to claim an
     * event the detector must not also interpret.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerCount = 1
                twoFingerCandidate = false
                // The scroll fields stay set in the shared report after a two-finger drag.
                // Zero them as the next gesture starts so it does not carry stale detents.
                if (scrolled) {
                    mouse.clearScroll()
                    scrolled = false
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = event.pointerCount
                if (event.pointerCount == 2) {
                    twoFingerCandidate = true
                    secondFingerDownAt = event.eventTime
                    for (i in 0..1) {
                        anchorX[i] = event.getX(i)
                        anchorY[i] = event.getY(i)
                    }
                } else {
                    // Three or more fingers is not a tap we recognise.
                    twoFingerCandidate = false
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (twoFingerCandidate && movedBeyondSlop(event)) twoFingerCandidate = false
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val lifted = event.actionMasked == MotionEvent.ACTION_UP
                if (twoFingerCandidate && isWithinTapWindow(event)) {
                    twoFingerCandidate = false
                    rightClickedGesture = event.downTime
                    mouse.sendRightClick()
                }
                pointerCount = if (lifted) 0 else event.pointerCount - 1
                if (lifted) releaseHold()
            }

            MotionEvent.ACTION_CANCEL -> {
                pointerCount = 0
                twoFingerCandidate = false
                releaseHold()
            }
        }
        return false
    }

    private fun isWithinTapWindow(event: MotionEvent): Boolean =
        event.eventTime - secondFingerDownAt <= TWO_FINGER_TAP_TIMEOUT

    private fun movedBeyondSlop(event: MotionEvent): Boolean {
        for (i in 0 until minOf(event.pointerCount, 2)) {
            if (abs(event.getX(i) - anchorX[i]) > touchSlop) return true
            if (abs(event.getY(i) - anchorY[i]) > touchSlop) return true
        }
        return false
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        // Exactly the tap the two-finger gesture already answered with a right click.
        if (e.downTime == rightClickedGesture) {
            rightClickedGesture = 0L
            return false
        }
        mouse.sendLeftClick()
        return false
    }

    /**
     * The second tap of a double tap. Held down it starts a drag; released quickly it is an
     * ordinary double click.
     */
    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holdArmed = true
                handler.postDelayed(startHold, HOLD_DELAY_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                // Dragging before the hold timer fires: start the drag now rather than
                // losing the first part of the movement.
                if (holdArmed && !holdingLeftButton) {
                    handler.removeCallbacks(startHold)
                    startHold.run()
                }
            }

            MotionEvent.ACTION_UP -> {
                if (holdingLeftButton) releaseHold() else {
                    cancelHold()
                    mouse.sendDoubleClick()
                }
            }

            MotionEvent.ACTION_CANCEL -> releaseHold()
        }
        return false
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (pointerCount != 2) return false

        // One wheel detent per callback in whichever direction the fingers moved. The
        // horizontal axis needs a small deadband because a vertical two-finger drag always
        // carries a little sideways drift. Thresholds are unchanged from the version
        // verified against a real host -- only the shape of the code is different.
        val vertical = when {
            distanceY > 0f -> -1
            distanceY < 0f -> 1
            else -> 0
        }
        val horizontal = when {
            distanceX > HORIZONTAL_DEADBAND -> 1
            distanceX < -HORIZONTAL_DEADBAND -> -1
            else -> 0
        }

        mouse.sendScroll(vertical, horizontal)
        scrolled = true
        return false
    }

    /** Lets go of a drag, if one is in progress, and disarms a pending one. */
    private fun releaseHold() {
        cancelHold()
        if (holdingLeftButton) {
            holdingLeftButton = false
            mouse.sendLeftClickOff()
        }
    }

    private fun cancelHold() {
        holdArmed = false
        handler.removeCallbacks(startHold)
    }

    /** Drops anything pending. Call when the trackpad goes away. */
    fun cancel() {
        releaseHold()
        twoFingerCandidate = false
        pointerCount = 0
    }

    private companion object {
        /**
         * Measured from the second finger landing. The single-finger tap timeout (100 ms)
         * was what made this gesture near-impossible to perform.
         */
        val TWO_FINGER_TAP_TIMEOUT = ViewConfiguration.getDoubleTapTimeout().toLong()

        /** How long the second tap must stay down before it becomes a drag. */
        const val HOLD_DELAY_MS = 150L

        const val HORIZONTAL_DEADBAND = 2f
    }
}
