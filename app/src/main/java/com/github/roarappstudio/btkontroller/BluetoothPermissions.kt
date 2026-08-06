package com.github.roarappstudio.btkontroller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The Bluetooth permissions this app actually needs, per API level.
 *
 * Upstream asked for ACCESS_COARSE_LOCATION, which was the pre-Android-12 requirement
 * for *scanning*. This app never scans -- the host PC initiates pairing -- so location
 * was never needed. On Android 12+ the relevant permissions are the new runtime
 * BLUETOOTH_* ones, which upstream never requested at all.
 */
object BluetoothPermissions {

    val required: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            // BLUETOOTH and BLUETOOTH_ADMIN are install-time permissions on API <= 30.
            emptyArray()
        }

    fun missing(ctx: Context): Array<String> =
        required.filter {
            ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    fun allGranted(ctx: Context): Boolean = missing(ctx).isEmpty()
}
