package com.github.roarappstudio.btkontroller

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

class SplashScreen : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val missing = BluetoothPermissions.missing(this)
        if (missing.isEmpty()) {
            openController()
        } else {
            requestPermissions(missing, REQUEST_BLUETOOTH)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return

        val granted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            openController()
        } else {
            // Upstream finished silently here, which looked like the app simply failing
            // to launch.
            Toast.makeText(this, R.string.error_bluetooth_permission, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun openController() {
        startActivity(Intent(this, SelectDeviceActivity::class.java))
        finish()
    }

    private companion object {
        const val REQUEST_BLUETOOTH = 1
    }
}
