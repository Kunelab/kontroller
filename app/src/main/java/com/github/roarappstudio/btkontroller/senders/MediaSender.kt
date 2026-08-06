package com.github.roarappstudio.btkontroller.senders

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.util.Log

/**
 * Media / consumer-control keys: volume, playback transport and Home.
 *
 * These travel in their own one-byte report (ID 10) declared by the Consumer Control
 * collection added to `DescriptorCollection.MOUSE_KEYBOARD_COMBO`. Each key is a single bit,
 * so a press is "set the bit, send, clear the bit, send".
 *
 * Because the report lives in the HID descriptor, a host that cached this device's SDP
 * record before the collection existed will not know about it. Such a host needs its cache
 * cleared before the media keys do anything -- on BlueZ that is the file under
 * /var/lib/bluetooth/<adapter>/cache/<device>.
 */
class MediaSender(
    private val hidDevice: BluetoothHidDevice,
    private val host: BluetoothDevice
) {

    private val report = ByteArray(1)

    fun next() = tap(BIT_NEXT)
    fun previous() = tap(BIT_PREVIOUS)
    fun stop() = tap(BIT_STOP)
    fun playPause() = tap(BIT_PLAY_PAUSE)
    fun mute() = tap(BIT_MUTE)
    fun volumeUp() = tap(BIT_VOLUME_UP)
    fun volumeDown() = tap(BIT_VOLUME_DOWN)
    fun home() = tap(BIT_HOME)

    private fun tap(bit: Int) {
        report[0] = (1 shl bit).toByte()
        send()
        report[0] = 0
        send()
    }

    // A sender only exists once a host has connected, which cannot happen without
    // BLUETOOTH_CONNECT; SelectDeviceActivity.onStart re-checks before getting this far.
    @SuppressLint("MissingPermission")
    private fun send() {
        if (!hidDevice.sendReport(host, ID, report)) {
            Log.e(TAG, "Media report wasn't sent")
        }
    }

    companion object {
        private const val TAG = "MediaSender"

        /** Must match the REPORT_ID in the consumer collection of the HID descriptor. */
        const val ID = 10

        private const val BIT_NEXT = 0
        private const val BIT_PREVIOUS = 1
        private const val BIT_STOP = 2
        private const val BIT_PLAY_PAUSE = 3
        private const val BIT_MUTE = 4
        private const val BIT_VOLUME_UP = 5
        private const val BIT_VOLUME_DOWN = 6
        private const val BIT_HOME = 7
    }
}
