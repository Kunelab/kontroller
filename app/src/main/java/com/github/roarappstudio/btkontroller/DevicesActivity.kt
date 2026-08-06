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

/**
 * Lists the paired hosts and lets you connect or disconnect a chosen one.
 *
 * `SelectDeviceActivity` has always been misnamed -- it never listed devices. This is the
 * screen that name implies: bonded devices, their live HID connection state, and a tap to
 * connect or drop the link.
 */
@SuppressLint("MissingPermission") // gated by AppPermissions in SplashScreen
class DevicesActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeSupport.appStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devices)
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

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            setBackgroundResource(R.drawable.click_button)
        }

        row.addView(TextView(this).apply {
            text = device.name ?: device.address
            textSize = 16f
            setTextColor(themeColor(android.R.attr.textColorPrimary))
        })

        row.addView(TextView(this).apply {
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

        row.setOnClickListener {
            val hid = BluetoothController.btHid
            if (hid == null) {
                Toast.makeText(this, R.string.devices_not_registered, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (connected) hid.disconnect(device) else hid.connect(device)

            // The state change arrives asynchronously; show it once it has settled.
            row.postDelayed({ refresh() }, REFRESH_DELAY_MS)
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
            addView(row)
            addView(separator)
        }
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
    }
}
