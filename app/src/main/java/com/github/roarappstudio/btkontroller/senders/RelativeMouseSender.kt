package com.github.roarappstudio.btkontroller.senders

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.util.Log
import com.github.roarappstudio.btkontroller.reports.ScrollableTrackpadMouseReport
import java.util.*
import kotlin.concurrent.schedule

@Suppress("MemberVisibilityCanBePrivate")
open class RelativeMouseSender(
    val hidDevice: BluetoothHidDevice,
    val host: BluetoothDevice

) {
    val mouseReport = ScrollableTrackpadMouseReport()
    var previousvscroll :Int=0
    var previoushscroll :Int =0


    protected open fun sendMouse() {
        if (!hidDevice.sendReport(host, ScrollableTrackpadMouseReport.ID, mouseReport.bytes)) {
            Log.e(TAG, "Report wasn't sent")
        }
    }

    fun sendTestMouseMove() {
        mouseReport.dxLsb = 20
        mouseReport.dyLsb = 20
        mouseReport.dxMsb = 20
        mouseReport.dyMsb = 20
        sendMouse()
    }

    /**
     * Sends a relative pointer movement. The report's X/Y fields are 12-bit signed values
     * split across two bytes each, so deltas are clamped to +/-2047.
     *
     * Both the trackpad and the gyro pointer go through here; the byte packing and the
     * report ID used to be duplicated inline in ViewListener.
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
     * Clears the movement fields so the host does not keep applying the last delta.
     * Button and scroll state in the shared report are deliberately left untouched, which
     * is what lets a held button survive a finger lift.
     */
    fun stopMove() = sendMove(0, 0)

    fun sendTestClick() {
        mouseReport.leftButton = true
        sendMouse()
        mouseReport.leftButton = false
        sendMouse()
//        Timer().schedule(20L) {
//
//        }
    }
    fun sendDoubleTapClick() {
        mouseReport.leftButton = true
        sendMouse()
        Timer().schedule(100L) {
            mouseReport.leftButton = false
            sendMouse()
            Timer().schedule(100L) {
                mouseReport.leftButton = true
                sendMouse()
                Timer().schedule(100L) {
                    mouseReport.leftButton = false
                    sendMouse()
                }




            }
        }
    }



    fun sendLeftClickOn() {
        mouseReport.leftButton = true
        sendMouse()


    }
    fun sendLeftClickOff() {
        mouseReport.leftButton = false
        sendMouse()

    }
    fun sendRightClick() {
        mouseReport.rightButton = true
        sendMouse()
        Timer().schedule(50L) {
            mouseReport.rightButton= false
            sendMouse()
        }
    }

    /**
     * Press/release variants for the on-screen click buttons. Holding the button down
     * rather than sending a pulse is what makes drag-and-drop work: the button bit stays
     * set in the shared report while the trackpad sends movement.
     */
    fun sendRightClickOn() {
        mouseReport.rightButton = true
        sendMouse()
    }

    fun sendRightClickOff() {
        mouseReport.rightButton = false
        sendMouse()
    }

    fun sendScroll(vscroll:Int,hscroll:Int){

        var hscrollmutable=0
        var vscrollmutable =0

        hscrollmutable=hscroll
        vscrollmutable= vscroll

//        var dhscroll= hscrollmutable-previoushscroll
//        var dvscroll= vscrollmutable-previousvscroll
//
//        dhscroll = Math.abs(dhscroll)
//        dvscroll = Math.abs(dvscroll)
//        if(dvscroll>=dhscroll)
//        {
//            hscrollmutable=0
//
//        }
//        else
//        {
//            vscrollmutable=0
//        }
        var vs:Int =(vscrollmutable)
        var hs:Int =(hscrollmutable)
        Log.i("vscroll ",vscroll.toString())
        Log.i("vs ",vs.toString())
        Log.i("hscroll ",hscroll.toString())
        Log.i("hs ",hs.toString())


        mouseReport.vScroll=vs.toByte()
        mouseReport.hScroll= hs.toByte()

        sendMouse()

//        previousvscroll=-1*vscroll
//        previoushscroll=hscroll


    }




    companion object {
        const val TAG = "TrackPadSender"

        /** Largest delta the 12-bit signed X/Y fields can carry. */
        const val MAX_DELTA = 2047
    }

}