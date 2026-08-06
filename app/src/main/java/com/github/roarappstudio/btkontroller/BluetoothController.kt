package com.github.roarappstudio.btkontroller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.github.roarappstudio.btkontroller.reports.FeatureReport

@Suppress("MemberVisibilityCanBePrivate")
@SuppressLint("MissingPermission") // callers gate on AppPermissions in SplashScreen
object BluetoothController : BluetoothHidDevice.Callback(), BluetoothProfile.ServiceListener {

    const val TAG = "BluetoothController"

    val featureReport = FeatureReport()

    var btAdapter: BluetoothAdapter? = null
        private set
    var btHid: BluetoothHidDevice? = null
    var hostDevice: BluetoothDevice? = null
    var autoPairFlag = false
    var mpluggedDevice: BluetoothDevice? = null

    private var deviceListener: ((BluetoothHidDevice, BluetoothDevice) -> Unit)? = null
    private var disconnectListener: (() -> Unit)? = null
    private var registeredListener: (() -> Unit)? = null

    /**
     * Acquires the HID_DEVICE profile proxy. Returns false when the device has no
     * Bluetooth adapter or the proxy request was rejected -- which is also what happens
     * on ROMs that ship without the Bluetooth HID Device profile.
     */
    fun init(ctx: Context): Boolean {
        // BluetoothAdapter.getDefaultAdapter() is deprecated as of API 31.
        val adapter = btAdapter
            ?: ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Log.e(TAG, "This device has no Bluetooth adapter")
            return false
        }
        btAdapter = adapter

        if (btHid != null) return true

        val requested = adapter.getProfileProxy(ctx, this, BluetoothProfile.HID_DEVICE)
        if (!requested) {
            Log.e(TAG, "getProfileProxy(HID_DEVICE) refused -- profile unsupported?")
        }
        return requested
    }

    /** Tears down the HID registration. Call only when the app is really going away. */
    fun release() {
        btHid?.let { hid ->
            hid.unregisterApp()
            btAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        }
        btHid = null
        hostDevice = null
        deviceListener = null
        disconnectListener = null
        registeredListener = null
    }

    fun getSender(callback: (BluetoothHidDevice, BluetoothDevice) -> Unit) {
        btHid?.let { hidd ->
            hostDevice?.let { host ->
                callback(hidd, host)
                return
            }
        }
        deviceListener = callback
    }

    fun getDisconnector(callback: () -> Unit) {
        disconnectListener = callback
    }

    /** Fired once the HID app is registered, i.e. once it is worth becoming discoverable. */
    fun onRegistered(callback: () -> Unit) {
        registeredListener = callback
    }

    /*****************************************************/
    /** BluetoothProfile.ServiceListener implementation **/
    /*****************************************************/

    override fun onServiceDisconnected(profile: Int) {
        Log.e(TAG, "Service disconnected!")
        if (profile == BluetoothProfile.HID_DEVICE) btHid = null
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        Log.i(TAG, "Connected to service")
        if (profile != BluetoothProfile.HID_DEVICE) {
            Log.w(TAG, "Unexpected profile $profile")
            return
        }

        val btHid = proxy as? BluetoothHidDevice
        if (btHid == null) {
            Log.e(TAG, "Proxy received but it is not a BluetoothHidDevice")
            return
        }
        this.btHid = btHid
        btHid.registerApp(sdpRecord, null, qosOut, { it.run() }, this)

        // Upstream called the hidden BluetoothAdapter.setScanMode(Int, Int) by
        // reflection here. That method is gone on Android 12+ and the reflective lookup
        // threw NoSuchMethodException, taking out the whole connection path. The
        // activity now asks for discoverability through ACTION_REQUEST_DISCOVERABLE.
    }

    /************************************************/
    /** BluetoothHidDevice.Callback implementation **/
    /************************************************/

    override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
        super.onSetReport(device, type, id, data)
        Log.d(TAG, "onSetReport device=$device type=$type id=$id")
        // HIDP requires a handshake for every SET_REPORT. Upstream only logged, so the
        // host waited for a reply that never came and bluetoothd reported
        // "HIDP SET_REPORT request timed out" (typically the host setting keyboard LEDs).
        btHid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
    }

    override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
        super.onGetReport(device, type, id, bufferSize)
        if (type == BluetoothHidDevice.REPORT_TYPE_FEATURE) {
            featureReport.wheelResolutionMultiplier = true
            featureReport.acPanResolutionMultiplier = true
            val replied = btHid?.replyReport(device, type, FeatureReport.ID, featureReport.bytes)
            Log.d(TAG, "Feature report replied=$replied")
        }
    }

    override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
        super.onConnectionStateChanged(device, state)
        Log.i(
            TAG, "Connection state ${
                when (state) {
                    BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                    BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                    BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                    BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                    else -> state.toString()
                }
            }"
        )
        if (state == BluetoothProfile.STATE_CONNECTED) {
            if (device != null) {
                hostDevice = device
                btHid?.let { deviceListener?.invoke(it, device) }
            } else {
                Log.e(TAG, "Connected state with no device")
            }
        } else {
            hostDevice = null
            if (state == BluetoothProfile.STATE_DISCONNECTED) disconnectListener?.invoke()
        }
    }

    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
        super.onAppStatusChanged(pluggedDevice, registered)
        if (!registered) {
            Log.w(TAG, "HID app registration was lost")
            return
        }

        registeredListener?.invoke()

        // getDevicesMatchingConnectionStates() returns an empty list when nothing has
        // ever been paired. Upstream indexed [0] unconditionally, so a first run with no
        // paired host crashed here with IndexOutOfBoundsException.
        val knownDevices = btHid?.getDevicesMatchingConnectionStates(
            intArrayOf(
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTING
            )
        ).orEmpty()
        Log.d(TAG, "Known HID hosts: $knownDevices")

        mpluggedDevice = pluggedDevice
        if (!autoPairFlag) return

        val target = pluggedDevice ?: knownDevices.firstOrNull() ?: return
        if (btHid?.getConnectionState(target) == BluetoothProfile.STATE_DISCONNECTED) {
            Log.i(TAG, "Auto-pairing to $target")
            btHid?.connect(target)
        }
    }

    private val sdpRecord by lazy {
        BluetoothHidDeviceAppSdpSettings(
            "MaxKontroller",
            "Android Bluetooth keyboard and mouse",
            "MaxKontroller",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            DescriptorCollection.MOUSE_KEYBOARD_COMBO
        )
    }

    private val qosOut by lazy {
        BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )
    }
}
