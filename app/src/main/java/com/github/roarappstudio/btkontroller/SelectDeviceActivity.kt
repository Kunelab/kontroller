package com.github.roarappstudio.btkontroller

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.github.roarappstudio.btkontroller.extraLibraries.CustomGestureDetector
import com.github.roarappstudio.btkontroller.listeners.CompositeListener
import com.github.roarappstudio.btkontroller.listeners.GestureDetectListener
import com.github.roarappstudio.btkontroller.listeners.ViewListener
import com.github.roarappstudio.btkontroller.senders.KeyboardSender
import com.github.roarappstudio.btkontroller.senders.RelativeMouseSender

@SuppressLint("MissingPermission") // gated by BluetoothPermissions in SplashScreen
class SelectDeviceActivity : Activity(), KeyEvent.Callback {

    private lateinit var trackpad: TrackpadView

    private var autoPairMenuItem: MenuItem? = null
    private var screenOnMenuItem: MenuItem? = null
    private var bluetoothStatus: MenuItem? = null

    /** 0 = each modifier is released after every keystroke, 1 = modifiers are held. */
    private var modifierHoldState: Int = 0

    private var keyboardSender: KeyboardSender? = null
    private var discoverableRequested = false
    private var keyboardShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_device)

        trackpad = findViewById(R.id.mouseView)
        trackpad.onHidKey = ::forwardKey
    }

    override fun onStart() {
        super.onStart()

        setConnected(false)

        val prefs = getPreferences(Context.MODE_PRIVATE)
        BluetoothController.autoPairFlag = prefs.getBoolean(getString(R.string.auto_pair_flag), false)
        autoPairMenuItem?.isChecked = BluetoothController.autoPairFlag

        val keepScreenOn = prefs.getBoolean(getString(R.string.screen_on_flag), false)
        screenOnMenuItem?.isChecked = keepScreenOn
        setKeepScreenOn(keepScreenOn)

        if (!BluetoothController.init(this)) {
            Toast.makeText(this, R.string.error_no_hid_profile, Toast.LENGTH_LONG).show()
            return
        }

        val adapter = BluetoothController.btAdapter
        if (adapter != null && !adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }

        // Registration survives onStop now, so on a restart the callback below may never
        // fire again -- ask directly when the HID app is already up.
        if (BluetoothController.btHid != null) ensureDiscoverable()
        BluetoothController.onRegistered { runOnUiThread { ensureDiscoverable() } }

        BluetoothController.getSender { hidDevice, host ->
            runOnUiThread {
                keyboardSender = KeyboardSender(hidDevice, host)

                val mouseSender = RelativeMouseSender(hidDevice, host)
                val gestureDetector =
                    CustomGestureDetector(this, GestureDetectListener(mouseSender))

                val composite = CompositeListener()
                composite.registerListener(
                    View.OnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
                )
                composite.registerListener(ViewListener(hidDevice, host, mouseSender))
                trackpad.setOnTouchListener(composite)

                setConnected(true)
            }
        }

        BluetoothController.getDisconnector {
            runOnUiThread { setConnected(false) }
        }
    }

    /**
     * The HID registration deliberately outlives [onStop].
     *
     * Upstream unregistered the HID app in onStop. Every transient system dialog -- the
     * discoverability prompt and the Bluetooth pairing dialog both -- stops this activity,
     * so the HID service was torn down at exactly the moment the host PC was resolving
     * it. The host then saw the phone as an audio/AVRCP device with no keyboard or mouse.
     */
    override fun onDestroy() {
        super.onDestroy()
        BluetoothController.release()
        keyboardSender = null
        discoverableRequested = false
    }

    /**
     * Forwards one key press to the host. Called both from the IME (via
     * [HidInputConnection]) and from hardware key events arriving at the activity.
     */
    private fun forwardKey(event: KeyEvent): Boolean =
        keyboardSender?.sendKeyboard(event.keyCode, event, modifierHoldState) ?: false

    /**
     * The host PC has to be able to find the phone in order to start pairing. Upstream
     * did this with a reflective call to the hidden setScanMode(), which no longer exists
     * on Android 12+; ACTION_REQUEST_DISCOVERABLE is the supported equivalent.
     */
    private fun ensureDiscoverable() {
        val adapter = BluetoothController.btAdapter ?: return
        if (discoverableRequested) return
        if (adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) return

        discoverableRequested = true
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not request discoverability", e)
        }
    }

    private fun setConnected(connected: Boolean) {
        val icon = if (connected) {
            R.drawable.ic_action_app_connected
        } else {
            R.drawable.ic_action_app_not_connected
        }
        val tooltip = getString(
            if (connected) R.string.status_connected else R.string.status_not_connected
        )
        bluetoothStatus?.setIcon(icon)
        bluetoothStatus?.tooltipText = tooltip
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Shows or hides the soft keyboard against the trackpad view.
     *
     * Upstream used `toggleSoftInput(SHOW_FORCED, 0)`; both the method and the flag are
     * deprecated and are a no-op on recent Android versions, so the keyboard button did
     * nothing. [keyboardShown] mirrors our own requests rather than the true IME state,
     * which is good enough for a toggle button.
     */
    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        if (keyboardShown) {
            imm.hideSoftInputFromWindow(trackpad.windowToken, 0)
            keyboardShown = false
        } else {
            trackpad.requestFocus()
            imm.showSoftInput(trackpad, 0)
            keyboardShown = true
        }
    }

    /*
     * Upstream sent the HID report from onKeyUp and had the onKeyDown call commented out.
     * Sending on key-down matches how a real keyboard behaves and removes the lag; the
     * sender emits a press and a release per call, so key-up only needs to be swallowed.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && forwardKey(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyboardSender != null &&
            event != null &&
            KeyboardReportKeys.isMapped(event.keyCode)
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.select_device_activity_menu, menu)

        bluetoothStatus = menu?.findItem(R.id.ble_app_connection_status)
        autoPairMenuItem = menu?.findItem(R.id.action_autopair)
        screenOnMenuItem = menu?.findItem(R.id.action_screen_on)

        val prefs = getPreferences(Context.MODE_PRIVATE)
        screenOnMenuItem?.isChecked = prefs.getBoolean(getString(R.string.screen_on_flag), false)
        autoPairMenuItem?.isChecked = prefs.getBoolean(getString(R.string.auto_pair_flag), false)
        setConnected(BluetoothController.hostDevice != null)

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> true

        R.id.action_keyboard -> {
            toggleKeyboard()
            true
        }

        R.id.check_modifier_state -> {
            modifierHoldState = if (modifierHoldState == 1) 0 else 1
            if (modifierHoldState == 0) {
                item.title = getString(R.string.action_check)
                keyboardSender?.sendNullKeys()
            } else {
                item.title = getString(R.string.action_check_held)
            }
            true
        }

        R.id.action_disconnect -> {
            BluetoothController.btHid?.disconnect(BluetoothController.hostDevice)
            setConnected(false)
            true
        }

        R.id.action_screen_on -> {
            val enabled = !item.isChecked
            item.isChecked = enabled
            setKeepScreenOn(enabled)
            getPreferences(Context.MODE_PRIVATE).edit()
                .putBoolean(getString(R.string.screen_on_flag), enabled)
                .apply()
            true
        }

        R.id.action_autopair -> {
            val enabled = !item.isChecked
            item.isChecked = enabled
            BluetoothController.autoPairFlag = enabled

            if (enabled) {
                val plugged = BluetoothController.mpluggedDevice
                if (plugged != null &&
                    BluetoothController.btHid?.getConnectionState(plugged) ==
                    android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
                ) {
                    BluetoothController.btHid?.connect(plugged)
                }
            }
            getPreferences(Context.MODE_PRIVATE).edit()
                .putBoolean(getString(R.string.auto_pair_flag), enabled)
                .apply()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private companion object {
        const val TAG = "SelectDeviceActivity"

        /** ACTION_REQUEST_DISCOVERABLE takes seconds and caps at 3600. */
        const val DISCOVERABLE_SECONDS = 300
    }
}

/** Small helper so the activity can ask whether a key has a HID mapping. */
private object KeyboardReportKeys {
    fun isMapped(keyCode: Int): Boolean =
        com.github.roarappstudio.btkontroller.reports.KeyboardReport.KeyEventMap[keyCode] != null
}
