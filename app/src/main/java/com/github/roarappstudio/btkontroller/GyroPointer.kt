package com.github.roarappstudio.btkontroller

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.github.roarappstudio.btkontroller.senders.RelativeMouseSender
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "Air mouse": moves the pointer by tilting the phone.
 *
 * This replaces upstream's `SensorSender`, which could never have worked -- it sent an
 * [com.github.roarappstudio.btkontroller.reports.AbsMouseReport] on report ID 2, and ID 2
 * only exists in the unused `MOUSE_ABSOLUTE` descriptor. The active
 * `MOUSE_KEYBOARD_COMBO` descriptor has no absolute-pointer report at all, so movement has
 * to be sent as relative deltas on report ID 4 like the trackpad does.
 *
 * Rotation is read as yaw/pitch from the rotation vector and differentiated between
 * samples, so what reaches the host is the *change* in aim rather than an absolute angle.
 *
 * Exactly one instance is kept for the lifetime of the activity and [sender] is swapped when
 * the HID link changes. Creating a fresh instance per connection used to leave the previous
 * one registered with SensorManager, so the pointer kept moving after the setting was
 * switched off.
 */
class GyroPointer : SensorEventListener {

    /** Null while there is no HID connection to send to. */
    var sender: RelativeMouseSender? = null

    /** Pointer speed multiplier, shared with the trackpad setting. */
    var sensitivity: Float = 1f

    var invertX: Boolean = false
    var invertY: Boolean = false

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var havePrevious = false
    private var previousYaw = 0f
    private var previousPitch = 0f

    fun reset() {
        havePrevious = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val target = sender ?: return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        val yaw = orientation[0]    // azimuth, turning the phone left/right
        val pitch = orientation[1]  // tilting the phone up/down

        if (!havePrevious) {
            previousYaw = yaw
            previousPitch = pitch
            havePrevious = true
            return
        }

        val dYaw = wrap(yaw - previousYaw)
        val dPitch = wrap(pitch - previousPitch)
        previousYaw = yaw
        previousPitch = pitch

        // Ignore sensor noise so the pointer sits still when the phone is held steady.
        if (abs(dYaw) < NOISE_FLOOR && abs(dPitch) < NOISE_FLOOR) return

        // Azimuth follows a compass heading, so turning the phone clockwise (to the right)
        // increases it -- the delta is used as-is. Both axes can be flipped from Settings
        // because which way feels "natural" depends on how you hold the phone.
        var dx = dYaw * PIXELS_PER_RADIAN * sensitivity
        var dy = dPitch * PIXELS_PER_RADIAN * sensitivity

        if (invertX) dx = -dx
        if (invertY) dy = -dy

        val moveX = dx.roundToInt()
        val moveY = dy.roundToInt()
        if (moveX != 0 || moveY != 0) target.sendMove(moveX, moveY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Keeps a difference of two angles in -pi..pi so crossing the wrap point is smooth. */
    private fun wrap(delta: Float): Float = when {
        delta > Math.PI -> delta - TWO_PI
        delta < -Math.PI -> delta + TWO_PI
        else -> delta
    }

    private companion object {
        const val TWO_PI = (2 * Math.PI).toFloat()

        /** Radians-to-pixels gain, chosen so a small wrist turn crosses a 1080p screen. */
        const val PIXELS_PER_RADIAN = 900f

        const val NOISE_FLOOR = 0.0015f
    }
}
