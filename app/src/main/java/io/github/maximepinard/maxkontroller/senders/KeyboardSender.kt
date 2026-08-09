package io.github.maximepinard.maxkontroller.senders

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.util.Log
import android.view.KeyEvent
import io.github.maximepinard.maxkontroller.reports.KeyboardReport

/**
 * Keyboard half of the HID link, on report ID 8.
 *
 * Call on the main thread only: the report is one shared mutable [ByteArray], so a second
 * writer would interleave modifier bits into someone else's packet.
 */
class KeyboardSender(
    private val hidDevice: BluetoothHidDevice,
    private val host: BluetoothDevice
) {
    private val keyboardReport = KeyboardReport()

    // A sender only exists once a host has connected, which cannot happen without
    // BLUETOOTH_CONNECT; SelectDeviceActivity.onStart re-checks before getting this far.
    @SuppressLint("MissingPermission")
    private fun sendKeys() {
        if (!hidDevice.sendReport(host, KeyboardReport.ID, keyboardReport.bytes)) {
            Log.e(TAG, "Report wasn't sent")
        }
    }

    /**
     * Sends one key press and its release.
     *
     * With [holdModifiers] set the modifier bits stay set after the release, which is what
     * the (P) toggle in the action bar is for -- it lets a soft keyboard build Ctrl+click
     * style combinations one tap at a time.
     */
    fun sendKeyboard(event: KeyEvent, holdModifiers: Boolean): Boolean {
        val usage = KeyboardReport.usageFor(event.keyCode) ?: return false

        applyModifiers(event)
        keyboardReport.key1 = usage.toByte()
        sendKeys()

        if (holdModifiers) {
            keyboardReport.key1 = 0
            sendKeys()
        } else {
            sendNullKeys()
        }
        return true
    }

    /**
     * Sends one explicit key position with the modifiers a layout needs for it.
     *
     * Used by the host-layout tables, where a character maps to a position rather than to an
     * Android keycode. Modifiers the user is holding via the (P) toggle are snapshotted and
     * restored, so Shift/AltGr added for a single character cannot get stuck on.
     */
    fun sendStroke(usage: Int, shift: Boolean, altGr: Boolean): Boolean {
        val heldModifiers = keyboardReport.bytes[0]

        if (shift) keyboardReport.leftShift = true
        if (altGr) keyboardReport.rightAlt = true
        keyboardReport.key1 = usage.toByte()
        sendKeys()

        keyboardReport.bytes[0] = heldModifiers
        keyboardReport.key1 = 0
        sendKeys()
        return true
    }

    /** Releases everything, modifiers included. */
    fun sendNullKeys() {
        keyboardReport.reset()
        sendKeys()
    }

    private fun applyModifiers(event: KeyEvent) {
        if (event.isShiftPressed) keyboardReport.leftShift = true
        if (event.isAltPressed) keyboardReport.leftAlt = true
        if (event.isCtrlPressed) keyboardReport.leftControl = true
        if (event.isMetaPressed) keyboardReport.leftGui = true

        // These three arrive as their own Android keycodes but are shifted positions on a
        // US keyboard, so the shift has to be added by hand.
        when (event.keyCode) {
            KeyEvent.KEYCODE_AT, KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_STAR ->
                keyboardReport.leftShift = true
        }
    }

    private companion object {
        const val TAG = "KeyboardSender"
    }
}
