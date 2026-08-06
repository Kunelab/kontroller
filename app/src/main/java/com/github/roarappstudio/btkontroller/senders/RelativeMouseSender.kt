package com.github.roarappstudio.btkontroller.senders

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.github.roarappstudio.btkontroller.reports.ScrollableTrackpadMouseReport

/**
 * Mouse half of the HID link: movement, buttons and scroll, all on report ID 4.
 *
 * Every method here must be called on the main thread. The report object is a single shared
 * mutable [ByteArray], so two writers would interleave button bits into each other's
 * packets. Timed sequences (the release half of a click) are therefore posted to the main
 * looper rather than run on a background timer.
 *
 * Movement does not go through here directly -- it is coalesced by
 * [com.github.roarappstudio.btkontroller.PointerPump], which calls [sendMove] once a frame.
 */
class RelativeMouseSender(
    private val hidDevice: BluetoothHidDevice,
    private val host: BluetoothDevice
) {
    val mouseReport = ScrollableTrackpadMouseReport()

    /**
     * Clicks are a press followed by a release some milliseconds later. This used to be
     * `Timer().schedule { }`, which spun up a fresh non-daemon thread per click -- three of
     * them for a double click -- and mutated the shared report off the main thread.
     */
    private val handler = Handler(Looper.getMainLooper())

    // A sender only exists once a host has connected, which cannot happen without
    // BLUETOOTH_CONNECT; SelectDeviceActivity.onStart re-checks before getting this far.
    @SuppressLint("MissingPermission")
    private fun sendMouse() {
        if (!hidDevice.sendReport(host, ScrollableTrackpadMouseReport.ID, mouseReport.bytes)) {
            Log.e(TAG, "Report wasn't sent")
        }
    }

    /**
     * Sends a relative pointer movement. The report's X/Y fields are 12-bit signed values
     * split across two bytes each, so deltas are clamped to +/-2047.
     */
    fun sendMove(dx: Int, dy: Int) {
        val cx = dx.coerceIn(-MAX_DELTA, MAX_DELTA)
        val cy = dy.coerceIn(-MAX_DELTA, MAX_DELTA)

        mouseReport.dxMsb = (cx shr 8).toByte()
        mouseReport.dxLsb = (cx and 0xFF).toByte()
        mouseReport.dyMsb = (cy shr 8).toByte()
        mouseReport.dyLsb = (cy and 0xFF).toByte()

        sendMouse()
    }

    /**
     * Press/release variants, used both by the on-screen click buttons and by the gesture
     * detector. Holding the button down rather than sending a pulse is what makes
     * drag-and-drop work: the button bit stays set in the shared report while the trackpad
     * sends movement.
     */
    fun sendLeftClickOn() {
        mouseReport.leftButton = true
        sendMouse()
    }

    fun sendLeftClickOff() {
        mouseReport.leftButton = false
        sendMouse()
    }

    fun sendRightClickOn() {
        mouseReport.rightButton = true
        sendMouse()
    }

    fun sendRightClickOff() {
        mouseReport.rightButton = false
        sendMouse()
    }

    /** A complete left click: press, then release on the next looper pass. */
    fun sendLeftClick() {
        sendLeftClickOn()
        handler.postDelayed(::sendLeftClickOff, CLICK_HOLD_MS)
    }

    /** A complete right click. */
    fun sendRightClick() {
        sendRightClickOn()
        handler.postDelayed(::sendRightClickOff, CLICK_HOLD_MS)
    }

    /**
     * Two full clicks close enough together that the host reads them as a double click.
     * The whole sequence takes 150 ms, comfortably inside the 400-500 ms a host typically
     * allows.
     */
    fun sendDoubleClick() {
        sendLeftClickOn()
        handler.postDelayed(::sendLeftClickOff, CLICK_HOLD_MS)
        handler.postDelayed(::sendLeftClickOn, CLICK_HOLD_MS * 2)
        handler.postDelayed(::sendLeftClickOff, CLICK_HOLD_MS * 3)
    }

    /**
     * Sends one scroll step. Values are wheel *detents*, not pixels, so +/-1 per report is
     * the normal magnitude.
     */
    fun sendScroll(vScroll: Int, hScroll: Int) {
        mouseReport.vScroll = vScroll.coerceIn(-MAX_SCROLL, MAX_SCROLL).toByte()
        mouseReport.hScroll = hScroll.coerceIn(-MAX_SCROLL, MAX_SCROLL).toByte()
        sendMouse()
    }

    /** Clears the scroll fields in the shared report without sending anything itself. */
    fun clearScroll() {
        mouseReport.vScroll = 0
        mouseReport.hScroll = 0
    }

    /** Drops any pending click releases. Call when the link goes away. */
    fun cancelPending() {
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val TAG = "RelativeMouseSender"

        /** Largest delta the 12-bit signed X/Y fields can carry. */
        const val MAX_DELTA = 2047

        /** The scroll fields are single signed bytes. */
        private const val MAX_SCROLL = 127

        /**
         * How long a button stays down for a synthetic click. Real hardware measures around
         * 10 ms; 50 ms is unmistakable to the host and still fast enough to feel instant.
         */
        private const val CLICK_HOLD_MS = 50L
    }
}
