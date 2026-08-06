package com.github.roarappstudio.btkontroller

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.roarappstudio.btkontroller.Prefs.preferredHost

/**
 * Lists the paired hosts and lets you connect or disconnect a chosen one, or pin one as the
 * device to always connect to.
 *
 * `SelectDeviceActivity` has always been misnamed -- it never listed devices. This is the
 * screen that name implies: bonded devices, their live HID connection state, and a tap to
 * connect or drop the link.
 *
 * The star matters for more than convenience. Auto-connect otherwise targets whichever
 * bonded device the stack offers first, and whatever ends up holding the link receives
 * every keystroke -- so pinning is how the user says which machine is allowed to be the
 * other end.
 */
@SuppressLint("MissingPermission") // gated by AppPermissions in SplashScreen
class DevicesActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeSupport.appStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devices)
        SystemBars.applyTo(this)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        list = findViewById(R.id.deviceList)
        hint = findViewById(R.id.devicesHint)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()

        val adapter = BluetoothController.btAdapter
        if (adapter == null) {
            hint.setText(R.string.devices_no_bluetooth)
            return
        }

        val bonded = adapter.bondedDevices.orEmpty()
        if (bonded.isEmpty()) {
            hint.setText(R.string.devices_none)
            return
        }

        hint.setText(
            if (BluetoothController.btHid == null) R.string.devices_not_registered
            else R.string.devices_hint
        )

        bonded.forEach { list.addView(rowFor(it)) }
    }

    private fun rowFor(device: BluetoothDevice): View {
        val state = BluetoothController.btHid?.getConnectionState(device)
        val connected = state == BluetoothProfile.STATE_CONNECTED
        val connecting = state == BluetoothProfile.STATE_CONNECTING
        val pinned = Prefs.of(this).preferredHost == device.address

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            setBackgroundResource(R.drawable.click_button)
        }

        labels.addView(TextView(this).apply {
            text = device.name ?: device.address
            textSize = 16f
            setTextColor(themeColor(android.R.attr.textColorPrimary))
        })

        labels.addView(TextView(this).apply {
            text = getString(
                when {
                    connected -> R.string.device_state_connected
                    connecting -> R.string.device_state_connecting
                    else -> R.string.device_state_disconnected
                },
                device.address
            )
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
        })

        if (pinned) {
            labels.addView(TextView(this).apply {
                setText(R.string.device_preferred)
                textSize = 13f
                setTextColor(themeColor(android.R.attr.colorAccent))
            })
        }

        labels.setOnClickListener {
            val hid = BluetoothController.btHid
            if (hid == null) {
                Toast.makeText(this, R.string.devices_not_registered, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (connected) hid.disconnect(device) else hid.connect(device)

            // The state change arrives asynchronously; show it once it has settled.
            labels.postDelayed({ refresh() }, REFRESH_DELAY_MS)
        }

        val star = TextView(this).apply {
            text = if (pinned) STAR_FILLED else STAR_OUTLINE
            textSize = 22f
            gravity = Gravity.CENTER
            minWidth = dp(56)
            setPadding(dp(8), dp(14), dp(8), dp(14))
            contentDescription =
                getString(if (pinned) R.string.device_unpin else R.string.device_pin)
            setTextColor(
                themeColor(
                    if (pinned) android.R.attr.colorAccent
                    else android.R.attr.textColorSecondary
                )
            )
            isClickable = true
            setBackgroundResource(R.drawable.click_button)
            setOnClickListener { togglePinned(device, pinned) }
        }

        val separator = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundResource(R.color.separator)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(labels)
                addView(star)
            })
            addView(separator)
        }
    }

    /**
     * Pins or unpins the device auto-connect is allowed to target. Unpinning goes back to
     * "any bonded device", which is convenient but means anything the phone has ever paired
     * with can pick up the keyboard.
     */
    private fun togglePinned(device: BluetoothDevice, wasPinned: Boolean) {
        val address = if (wasPinned) null else device.address
        Prefs.of(this).preferredHost = address
        BluetoothController.preferredHost = address

        val message = if (wasPinned) {
            getString(R.string.device_unpinned_toast)
        } else {
            getString(R.string.device_pinned_toast, device.name ?: device.address)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun themeColor(attr: Int): Int {
        val typed = theme.obtainStyledAttributes(intArrayOf(attr))
        try {
            return typed.getColor(0, 0)
        } finally {
            typed.recycle()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private companion object {
        const val REFRESH_DELAY_MS = 1500L

        /** Plain glyphs rather than drawables -- no asset, and they theme with the text. */
        const val STAR_FILLED = "★"
        const val STAR_OUTLINE = "☆"
    }
}
