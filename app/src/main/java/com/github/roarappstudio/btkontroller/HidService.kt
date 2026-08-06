package com.github.roarappstudio.btkontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.github.roarappstudio.btkontroller.Prefs.autoPair
import com.github.roarappstudio.btkontroller.Prefs.preferredHost

/**
 * Owns the Bluetooth HID registration for as long as the user wants to stay connected.
 *
 * Upstream kept the registration in the activity, so it died whenever the activity did.
 * Holding it in a foreground service is what lets the phone keep acting as a keyboard and
 * mouse while the app is in the background or the screen is off -- and it is why upstream's
 * manifest declared FOREGROUND_SERVICE without ever having a service.
 *
 * The activity still talks to [BluetoothController] directly; this service only owns the
 * registration's lifetime, so there is no binder plumbing to get wrong.
 */
class HidService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        // START_STICKY can bring this back with no activity ever having run, so the
        // connection preferences have to be loaded here too. Without the pinned host the
        // service would fall back to connecting to any bonded device.
        val prefs = Prefs.of(this)
        BluetoothController.autoPairFlag = prefs.autoPair
        BluetoothController.preferredHost = prefs.preferredHost

        if (!BluetoothController.acquire(this, BluetoothController.Owner.SERVICE)) {
            Log.e(TAG, "No HID Device profile available; stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * Drops this service's claim on the registration but does not necessarily end it: if
     * the activity is still in the foreground it holds its own claim and the link stays up.
     * Releasing unconditionally is what used to kill the HID link when the user turned
     * "stay connected" off while looking at the trackpad.
     */
    override fun onDestroy() {
        BluetoothController.release(BluetoothController.Owner.SERVICE)
        super.onDestroy()
    }

    /** Not a bound service. */
    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ wants the service type declared at start time; from Android 14 it
            // is mandatory and must match the manifest.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SelectDeviceActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, HidService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setSmallIcon(R.drawable.ic_action_app_connected)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.service_stop),
                    stop
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "HidService"
        private const val CHANNEL_ID = "hid_connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.github.roarappstudio.btkontroller.STOP"

        fun start(ctx: Context) {
            val intent = Intent(ctx, HidService::class.java)
            ctx.startForegroundService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HidService::class.java))
        }
    }
}
