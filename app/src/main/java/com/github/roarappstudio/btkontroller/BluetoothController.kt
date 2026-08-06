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

    /** Who currently needs the HID registration alive. See [acquire] / [release]. */
    enum class Owner { ACTIVITY, SERVICE }

    val featureReport = FeatureReport()

    var btAdapter: BluetoothAdapter? = null
        private set

    /**
     * Written from a Bluetooth binder thread (see the executor passed to `registerApp`)
     * and read from the UI thread, so both of these have to be volatile.
     */
    @Volatile
    var btHid: BluetoothHidDevice? = null

    @Volatile
    var hostDevice: BluetoothDevice? = null

    /**
     * The most recent host, kept after it disconnects so [reconnect] has something to aim
     * at. [hostDevice] is cleared on disconnect because it means "connected right now".
     */
    @Volatile
    var lastHost: BluetoothDevice? = null
        private set

    var autoPairFlag = false

    /**
     * MAC address of the host the user pinned in [DevicesActivity], or null for "whatever
     * turns up". When set, auto-connect will only ever target this device -- keystrokes
     * (and the clipboard) then cannot be delivered to some other bonded device that
     * happens to appear first.
     */
    var preferredHost: String? = null

    private val owners = mutableSetOf<Owner>()

    private var deviceListener: ((BluetoothHidDevice, BluetoothDevice) -> Unit)? = null
    private var disconnectListener: (() -> Unit)? = null
    private var registeredListener: (() -> Unit)? = null

    /**
     * Registers [owner] as needing the HID profile and acquires the proxy if it is not up
     * yet. Returns false when the device has no Bluetooth adapter or the proxy request was
     * rejected -- which is also what happens on ROMs that ship without the Bluetooth HID
     * Device profile.
     */
    fun acquire(ctx: Context, owner: Owner): Boolean {
        owners += owner
        return init(ctx)
    }

    /**
     * Drops [owner]'s claim, tearing the registration down only once nobody is left.
     *
     * The reference counting is the point. Teardown used to be unconditional, so stopping
     * the foreground service unregistered the HID app even when the activity was in the
     * foreground still using it: toggling "stay connected" off in Settings and returning to
     * the trackpad silently killed the link, because [SelectDeviceActivity.onStart] calls
     * `HidService.stop()` and the service's `onDestroy` then ran after the activity had
     * already re-registered its listeners.
     */
    fun release(owner: Owner) {
        owners -= owner
        if (owners.isEmpty()) teardown()
    }

    /**
     * Drops the callbacks without touching the registration.
     *
     * They capture the Activity, and this object outlives it, so anything that goes away
     * has to call this or it leaks its whole view hierarchy. That is exactly what happened
     * on every rotation and theme change while "stay connected" was on: teardown was
     * skipped, so nothing ever cleared them.
     */
    fun clearListeners() {
        deviceListener = null
        disconnectListener = null
        registeredListener = null
    }

    private fun init(ctx: Context): Boolean {
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

    private fun teardown() {
        btHid?.let { hid ->
            hid.unregisterApp()
            btAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        }
        btHid = null
        hostDevice = null
        clearListeners()
    }

    /**
     * Registers [callback] to run whenever a host connects, and runs it immediately if one
     * already is.
     *
     * The listener is kept in *both* cases. It used to return early after the immediate
     * call, so when the activity started with the link already up -- the normal case, since
     * "stay connected" is on by default -- the callback was fired once and thrown away.
     * `deviceListener` was then null for the rest of the process, and the next
     * reconnection rebuilt nothing: no senders, no status update. Disconnecting once left
     * the app permanently unable to come back without a restart.
     */
    fun getSender(callback: (BluetoothHidDevice, BluetoothDevice) -> Unit) {
        deviceListener = callback

        val hid = btHid ?: return
        val host = hostDevice ?: return
        callback(hid, host)
    }

    /**
     * Opens the HID link again after a manual disconnect.
     *
     * Auto-connect only runs on registration, so once the user (or a misclick) has dropped
     * the link there is otherwise nothing that brings it back. Returns false when there is
     * no host to reconnect to.
     */
    fun reconnect(): Boolean {
        val hid = btHid ?: return false
        val target = hostDevice ?: lastHost ?: return false
        Log.i(TAG, "Reconnecting to $target")
        return hid.connect(target)
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
        // The executor runs inline, so every callback below arrives on a Bluetooth binder
        // thread. That is deliberate: onGetReport/onSetReport have to answer within the
        // HIDP handshake timeout and must not queue behind whatever the UI thread is doing.
        // The cost is that shared state is cross-thread, which is why btHid and hostDevice
        // are @Volatile and everything touching views hops via runOnUiThread.
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
                lastHost = device
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

        if (!autoPairFlag) return

        // With a host pinned, that host or nothing. Without one, fall back to whatever the
        // stack offers -- which is convenient but means anything the phone has ever been
        // bonded with can become the keystroke sink, so DevicesActivity nudges towards
        // pinning one.
        val pinned = preferredHost
        val target = if (pinned != null) {
            (knownDevices + listOfNotNull(pluggedDevice)).firstOrNull { it.address == pinned }
                ?: return
        } else {
            pluggedDevice ?: knownDevices.firstOrNull() ?: return
        }

        if (btHid?.getConnectionState(target) == BluetoothProfile.STATE_DISCONNECTED) {
            Log.i(TAG, "Auto-connecting to $target")
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
