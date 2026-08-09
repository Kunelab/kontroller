package io.github.maximepinard.maxkontroller

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.github.maximepinard.maxkontroller.Prefs.helpShown

class SplashScreen : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeSupport.splashStyle(this))
        super.onCreate(savedInstanceState)

        val missing = AppPermissions.missing(this)
        if (missing.isEmpty()) {
            proceed()
        } else {
            requestPermissions(missing, REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return

        // Only the Bluetooth permissions are load-bearing; a declined notification
        // permission just hides the service notification.
        if (AppPermissions.allEssentialGranted(this)) {
            proceed()
        } else {
            // Upstream finished silently here, which looked like the app simply failing
            // to launch.
            Toast.makeText(this, R.string.error_bluetooth_permission, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** First launch goes through the guide; afterwards straight to the trackpad. */
    private fun proceed() {
        val next = if (Prefs.of(this).helpShown) {
            Intent(this, SelectDeviceActivity::class.java)
        } else {
            HelpActivity.intent(this, firstRun = true)
        }
        startActivity(next)
        finish()
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
