package io.github.kunelab.kontroller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The runtime permissions this app needs, per API level.
 *
 * Upstream asked for ACCESS_COARSE_LOCATION, which was the pre-Android-12 requirement for
 * *scanning*. This app never scans -- the host PC initiates pairing -- so location was never
 * needed. On Android 12+ the relevant permissions are the new runtime BLUETOOTH_* ones,
 * which upstream never requested at all.
 */
object AppPermissions {

    val required: Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        // The foreground service runs either way, but without this its notification is
        // hidden, which makes an always-on connection invisible to the user.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    /** Permissions that must be granted for the app to work at all. */
    private val essential: Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            emptySet()
        }

    fun missing(ctx: Context): Array<String> =
        required.filter { granted(ctx, it) != true }.toTypedArray()

    fun allEssentialGranted(ctx: Context): Boolean =
        essential.all { granted(ctx, it) }

    private fun granted(ctx: Context, permission: String): Boolean =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
