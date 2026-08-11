package io.github.kunelab.kontroller

import android.annotation.SuppressLint
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
import io.github.kunelab.kontroller.Prefs.autoPair
import io.github.kunelab.kontroller.Prefs.autoReconnect
import io.github.kunelab.kontroller.Prefs.preferredHost

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

    /**
     * Kept as a field so the same instance can be removed again -- and registered here rather
     * than through [BluetoothController.clearListeners], which the activity calls on its way
     * out and which would otherwise take the notification's subscription with it.
     */
    private val statusObserver: (BluetoothController.Status) -> Unit = { status ->
        // Arrives on a Bluetooth binder thread; NotificationManager is safe from any thread,
        // but getString/name lookups are cheap and thread-safe too, so no hop is needed.
        updateNotification(status)
    }

    /**
     * Whether [startForeground] has run.
     *
     * addStatusObserver hands over the current state the moment it is called, which is before
     * onStartCommand -- and posting this id with notify() first would put up a plain dismissable
     * notification that startForeground then has to adopt.
     */
    @Volatile
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        BluetoothController.addStatusObserver(statusObserver)
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
        BluetoothController.autoReconnectFlag = prefs.autoReconnect
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
        BluetoothController.removeStatusObserver(statusObserver)
        BluetoothController.release(BluetoothController.Owner.SERVICE)
        super.onDestroy()
    }

    /** Not a bound service. */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Repaints the notification to say what the link is actually doing.
     *
     * It used to be a fixed string, which made it the least informative surface in the app
     * while being the *only* surface once the app is closed: a phone that had silently lost the
     * host looked identical to one that was working.
     */
    @SuppressLint("MissingPermission") // device names need BLUETOOTH_CONNECT, gated in SplashScreen
    private fun updateNotification(status: BluetoothController.Status) {
        if (!foregroundStarted) return

        val name = status.host?.let { it.name ?: it.address }
        val text = when {
            status.state == BluetoothController.LinkState.CONNECTED && name != null ->
                getString(R.string.status_connected_to, name)

            status.state == BluetoothController.LinkState.CALLING && name != null ->
                getString(R.string.status_reconnecting, name, status.attempt)

            // The same wording as the action bar, and already translated. service_text
            // ("acting as a keyboard and mouse") is only true while a host is attached.
            else -> getString(R.string.status_not_connected)
        }

        // notify() with the same id updates the existing foreground notification. Calling
        // startForeground() again would work too but re-asserts the service type on every
        // attempt, which is noise for something that changes this often.
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(getString(R.string.service_text))
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

        // Only now is notify() on this id an update rather than a fresh notification.
        foregroundStarted = true
        updateNotification(BluetoothController.currentStatus())
    }

    private fun buildNotification(text: String): Notification {
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
            .setContentText(text)
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
        private const val ACTION_STOP = "io.github.kunelab.kontroller.STOP"

        fun start(ctx: Context) {
            val intent = Intent(ctx, HidService::class.java)
            ctx.startForegroundService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HidService::class.java))
        }
    }
}
