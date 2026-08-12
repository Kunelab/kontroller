package io.github.kunelab.kontroller

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import io.github.kunelab.kontroller.Prefs.autoPair
import io.github.kunelab.kontroller.Prefs.autoReconnect
import io.github.kunelab.kontroller.Prefs.clickBar
import io.github.kunelab.kontroller.Prefs.clipboardAction
import io.github.kunelab.kontroller.Prefs.effectiveOrientation
import io.github.kunelab.kontroller.Prefs.mediaKeys
import io.github.kunelab.kontroller.Prefs.gyroInvertX
import io.github.kunelab.kontroller.Prefs.gyroInvertY
import io.github.kunelab.kontroller.Prefs.gyroPointer
import io.github.kunelab.kontroller.Prefs.hostLayout
import io.github.kunelab.kontroller.Prefs.keepScreenOn
import io.github.kunelab.kontroller.Prefs.preferredHost
import io.github.kunelab.kontroller.Prefs.sensitivityFactor
import io.github.kunelab.kontroller.Prefs.stayConnected
import io.github.kunelab.kontroller.extraLibraries.CustomGestureDetector
import io.github.kunelab.kontroller.listeners.CompositeListener
import io.github.kunelab.kontroller.listeners.GestureDetectListener
import io.github.kunelab.kontroller.listeners.ViewListener
import io.github.kunelab.kontroller.reports.KeyboardReport
import io.github.kunelab.kontroller.senders.KeyboardSender
import io.github.kunelab.kontroller.senders.MediaSender
import io.github.kunelab.kontroller.senders.RelativeMouseSender

class SelectDeviceActivity : Activity() {

    private lateinit var trackpad: TrackpadView
    private lateinit var clickBarView: View
    private lateinit var leftClickButton: View
    private lateinit var rightClickButton: View
    private lateinit var mediaBarView: View

    private val charMap: KeyCharacterMap =
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

    private var bluetoothStatus: MenuItem? = null

    /** False releases each modifier after every keystroke; true holds them. */
    private var holdModifiers = false

    private var keyboardSender: KeyboardSender? = null
    private var mouseSender: RelativeMouseSender? = null
    private var mediaSender: MediaSender? = null
    private var viewListener: ViewListener? = null
    private var gestureListener: GestureDetectListener? = null
    private var pointer: PointerPump? = null

    /** Cancels an in-flight clipboard send if the screen goes away mid-way. */
    private var clipboardJob: Runnable? = null

    /**
     * One instance for the whole activity lifetime, with its sender swapped on (re)connect.
     * Creating a new one per connection left the previous instance registered with
     * SensorManager, so the pointer kept moving after the gyro setting was switched off.
     */
    private val gyro = GyroPointer()
    private var gyroRegistered = false

    private var appliedTheme = 0
    private var discoverableRequested = false
    private var keyboardShown = false

    /** The stuck-stack dialog is worth showing once, not on every retry. */
    private var stuckStackDialogShown = false

    /** Throttles the nudge toast. See [nudgeReconnect]. */
    private var lastNudgeAt = 0L

    /**
     * Latest link state, for the action bar.
     *
     * The status line has to distinguish "not connected" from "actively calling the host",
     * because with a sleeping PC the second state can last a minute and is the only sign that
     * anything is happening.
     */
    private var linkStatus = BluetoothController.currentStatus()

    /** Held so the same instance can be unsubscribed in [onStop]. */
    private val statusObserver: (BluetoothController.Status) -> Unit = { status ->
        runOnUiThread { onStatus(status) }
    }

    /**
     * The pad's live listener, or null while there is no host.
     *
     * Indirection rather than swapping the view's own listener: a null listener meant touching
     * the pad while disconnected did nothing whatsoever, so the app's main surface was silently
     * dead exactly when the user most needed a hint. See [nudgeReconnect].
     */
    private var padListener: View.OnTouchListener? = null

    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }

    /** Re-read in onStart so a change in Settings takes effect without a restart. */
    private var hostLayout: HostLayout = HostLayout.US

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = ThemeSupport.appStyle(this)
        setTheme(appliedTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_device)
        SystemBars.applyTo(this)

        trackpad = findViewById(R.id.mouseView)
        clickBarView = findViewById(R.id.clickBar)
        leftClickButton = findViewById(R.id.leftClickButton)
        rightClickButton = findViewById(R.id.rightClickButton)
        mediaBarView = findViewById(R.id.mediaBar)

        trackpad.onHidKey = ::forwardKey
        trackpad.onHidChar = ::forwardChar

        // Set once, for the life of the activity. The real listener is swapped in and out via
        // padListener as hosts come and go; with no host, a touch asks for one instead of
        // being swallowed.
        trackpad.setOnTouchListener { view, event ->
            padListener?.onTouch(view, event) ?: run {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) nudgeReconnect()
                true
            }
        }

        leftClickButton.setOnTouchListener { v, e -> onClickButtonTouch(v, e, left = true) }
        rightClickButton.setOnTouchListener { v, e -> onClickButtonTouch(v, e, left = false) }

        wireMediaKeys()
    }

    private fun wireMediaKeys() {
        val keys = listOf<Pair<Int, (MediaSender) -> Unit>>(
            R.id.mediaVolDown to { it.volumeDown() },
            R.id.mediaVolUp to { it.volumeUp() },
            R.id.mediaMute to { it.mute() },
            R.id.mediaPrev to { it.previous() },
            R.id.mediaPlay to { it.playPause() },
            R.id.mediaNext to { it.next() },
            R.id.mediaHome to { it.home() }
        )
        for ((id, action) in keys) {
            findViewById<View>(id).setOnClickListener {
                val sender = mediaSender
                if (sender == null) {
                    Toast.makeText(this, R.string.error_not_connected, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    action(sender)
                }
            }
        }
    }

    @SuppressLint("MissingPermission") // the permission gate below returns before any BT call
    override fun onStart() {
        super.onStart()

        // A theme only takes effect at creation time, so a change made in Settings needs
        // this activity rebuilt.
        if (ThemeSupport.appStyle(this) != appliedTheme) {
            recreate()
            return
        }

        // The permission gate lives in SplashScreen, but this activity is also reachable
        // straight from the foreground service's notification, and permissions can be
        // revoked while the app is running. Without this, every Bluetooth call below throws
        // SecurityException instead of showing a message.
        if (!AppPermissions.allEssentialGranted(this)) {
            Toast.makeText(this, R.string.error_bluetooth_permission, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setConnected(BluetoothController.hostDevice)

        if (!BluetoothController.acquire(this, BluetoothController.Owner.ACTIVITY)) {
            Toast.makeText(this, R.string.error_no_hid_profile, Toast.LENGTH_LONG).show()
            return
        }

        applyPreferences()

        promptToEnableBluetooth()

        // Registration survives onStop now, so on a restart the callback below may never
        // fire again -- ask directly when the HID app is already up.
        if (BluetoothController.btHid != null) ensureDiscoverable()
        BluetoothController.onRegistered { runOnUiThread { ensureDiscoverable() } }
        BluetoothController.onRegistrationFailed { runOnUiThread { showStuckStackDialog() } }

        BluetoothController.getSender { hidDevice, host ->
            runOnUiThread {
                keyboardSender = KeyboardSender(hidDevice, host)

                val sender = RelativeMouseSender(hidDevice, host)
                mouseSender = sender

                val pump = PointerPump(sender)
                pointer = pump

                val gestures = GestureDetectListener(this, sender)
                gestureListener = gestures
                val gestureDetector = CustomGestureDetector(this, gestures)

                val listener = ViewListener(pump)
                viewListener = listener

                padListener = CompositeListener(
                    { _, event -> gestureDetector.onTouchEvent(event) },
                    listener
                )

                gyro.pointer = pump
                mediaSender = MediaSender(hidDevice, host)

                applyPointerPreferences()
                setConnected(host)
                invalidateOptionsMenu()
            }
        }

        BluetoothController.getDisconnector {
            runOnUiThread { onHostDisconnected() }
        }

        // Subscribed here and dropped in onStop rather than through clearListeners(), because
        // these are a list the service also subscribes to -- see addStatusObserver. Adding it
        // paints the current state immediately.
        BluetoothController.addStatusObserver(statusObserver)

        BluetoothController.onPinnedHostUnpaired { device ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(R.string.device_unpaired_toast, device.name ?: device.address),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // The one hint the user gets that re-pairing is the fix -- Android shows both sides
        // as paired while every connection dies at encryption setup.
        BluetoothController.onStaleBondSuspected { device ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(R.string.stale_bond_toast, device.name ?: device.address),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Opening the app with the link already down used to do nothing at all. Auto-connect
        // runs on registration, and with "stay connected" on the registration is already up by
        // the time this activity starts, so no callback fires and the user is left looking at
        // "Not connected" with no way forward but the menu. This is also the path that makes
        // reopening the app wake a host that has gone to sleep since it was last used.
        if (BluetoothController.hostDevice == null &&
            !BluetoothController.reconnecting &&
            Prefs.of(this).autoReconnect
        ) {
            // Not user-initiated: this runs on every return to the activity, so it respects
            // the cooldown rather than restarting a full-length chase each time.
            BluetoothController.reconnect(userInitiated = false)
        }
    }

    /**
     * Drops everything bound to the link that just went away.
     *
     * The senders hold the `BluetoothHidDevice` and the host they were built for, so keeping
     * them after a disconnect leaves the app half-alive: reports go nowhere but nothing says
     * so. They are rebuilt from scratch by the [BluetoothController.getSender] callback when
     * a host connects again.
     */
    private fun onHostDisconnected() {
        cancelClipboard()
        gestureListener?.cancel()
        mouseSender?.cancelPending()
        pointer?.reset()

        keyboardSender = null
        mouseSender = null
        mediaSender = null
        gyro.pointer = null
        pointer = null
        // Hands the pad back to the nudge path, so a touch now asks for a host.
        padListener = null

        setConnected(null)
        invalidateOptionsMenu()
    }

    override fun onStop() {
        super.onStop()
        stopGyro()
        // Nothing to paint while stopped, and onStart re-subscribes with a fresh snapshot.
        BluetoothController.removeStatusObserver(statusObserver)
    }

    /**
     * Paints the link state and keeps the Disconnect item's three titles in step.
     */
    private fun onStatus(status: BluetoothController.Status) {
        val wasCalling = linkStatus.state == BluetoothController.LinkState.CALLING
        linkStatus = status
        setConnected(status.host.takeIf { status.state == BluetoothController.LinkState.CONNECTED })

        // Only on a start/stop edge: the menu item's title changes between Connect, Stop
        // calling and Disconnect, but rebuilding the menu on every attempt would close it
        // under the user while they were reading it.
        if (wasCalling != (status.state == BluetoothController.LinkState.CALLING)) {
            invalidateOptionsMenu()
        }
    }

    /**
     * Starts the wake loop because the user touched something while disconnected.
     *
     * This is the trackpad equivalent of wiggling a sleeping mouse, and it is the first thing
     * anyone tries. Guarded on [BluetoothController.reconnecting] so repeated touches during a
     * chase neither restart it nor stack up toasts.
     */
    private fun nudgeReconnect() {
        if (BluetoothController.hostDevice != null || BluetoothController.reconnecting) return

        // The `reconnecting` guard above only holds once a chase actually starts. When there
        // is nothing to chase -- no pinned host, retrying switched off -- it never engages,
        // and every tap on a dead pad queued another toast: five taps meant ten seconds of
        // them, one after another. Rate-limit the ones that cannot self-suppress.
        val now = SystemClock.uptimeMillis()
        if (now - lastNudgeAt < NUDGE_TOAST_INTERVAL_MS) return
        lastNudgeAt = now

        // With retrying switched off, say so rather than reaching for the host behind the
        // user's back -- but still say *something*, because a pad that swallows touches in
        // silence is the problem being fixed here.
        if (!Prefs.of(this).autoReconnect) {
            Toast.makeText(this, R.string.error_not_connected, Toast.LENGTH_SHORT).show()
            return
        }

        val message = if (BluetoothController.reconnect()) {
            R.string.reconnect_nudge
        } else {
            R.string.error_nothing_to_reconnect
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * The HID registration deliberately outlives [onStop].
     *
     * Upstream unregistered the HID app in onStop. Every transient system dialog -- the
     * discoverability prompt and the Bluetooth pairing dialog both -- stops this activity,
     * so the HID service was torn down at exactly the moment the host PC was resolving
     * it. The host then saw the phone as an audio/AVRCP device with no keyboard or mouse.
     *
     * When "stay connected" is on, [HidService] holds its own claim and the registration
     * survives this activity entirely; [BluetoothController.release] only tears it down once
     * nobody is left.
     *
     * The listeners are a separate matter and are always dropped. They capture this
     * activity, and [BluetoothController] is a singleton that outlives it, so leaving them
     * in place leaked the whole view hierarchy on every rotation and theme change -- which
     * is exactly what happened before, because teardown was skipped in the common case and
     * nothing else ever cleared them.
     */
    override fun onDestroy() {
        super.onDestroy()

        cancelClipboard()
        BluetoothController.clearListeners()
        gestureListener?.cancel()
        mouseSender?.cancelPending()
        pointer?.reset()

        // A rotation destroys and rebuilds this activity immediately. Unregistering the HID
        // app in between would make the host drop the link every time the phone turns.
        if (!isChangingConfigurations) {
            BluetoothController.release(BluetoothController.Owner.ACTIVITY)
        }

        stopGyro()
        gyro.pointer = null
        keyboardSender = null
        mouseSender = null
        mediaSender = null
        viewListener = null
        gestureListener = null
        pointer = null
        discoverableRequested = false
    }

    /** Re-read settings on every start so returning from [SettingsActivity] applies them. */
    private fun applyPreferences() {
        val prefs = Prefs.of(this)

        clickBarView.visibility = if (prefs.clickBar) View.VISIBLE else View.GONE
        mediaBarView.visibility = if (prefs.mediaKeys) View.VISIBLE else View.GONE
        BluetoothController.autoPairFlag = prefs.autoPair
        BluetoothController.autoReconnectFlag = prefs.autoReconnect
        BluetoothController.preferredHost = prefs.preferredHost
        hostLayout = prefs.hostLayout
        invalidateOptionsMenu()

        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        requestedOrientation = when (prefs.effectiveOrientation) {
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }

        if (prefs.stayConnected) HidService.start(this) else HidService.stop(this)

        applyPointerPreferences()
    }

    /** Sensitivity and gyro depend on the senders, which arrive asynchronously. */
    private fun applyPointerPreferences() {
        val prefs = Prefs.of(this)
        val factor = prefs.sensitivityFactor

        viewListener?.sensitivity = factor
        gyro.sensitivity = factor
        gyro.invertX = prefs.gyroInvertX
        gyro.invertY = prefs.gyroInvertY

        // While the gyro drives the pointer, dragging on the pad must not also move it.
        viewListener?.movementEnabled = !prefs.gyroPointer

        if (prefs.gyroPointer) startGyro() else stopGyro()
    }

    private fun startGyro() {
        if (gyroRegistered) return

        val manager = sensorManager ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (sensor == null) {
            Toast.makeText(this, R.string.error_no_gyro, Toast.LENGTH_LONG).show()
            return
        }
        gyro.reset()
        manager.registerListener(gyro, sensor, SensorManager.SENSOR_DELAY_GAME)
        gyroRegistered = true
    }

    private fun stopGyro() {
        if (!gyroRegistered) return
        sensorManager?.unregisterListener(gyro)
        gyroRegistered = false
    }

    /**
     * Physical-trackpad-style buttons: press holds the mouse button down and release lets
     * it go, rather than sending a click pulse. That makes press-and-drag work -- hold a
     * button here and move on the pad above to drag and drop or rubber-band select.
     */
    private fun onClickButtonTouch(view: View, event: MotionEvent, left: Boolean): Boolean {
        val sender = mouseSender ?: run {
            // Same reasoning as the pad: pressing a button with no host used to do nothing at
            // all rather than saying so or trying to fix it.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) nudgeReconnect()
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.isPressed = true
                if (left) sender.sendLeftClickOn() else sender.sendRightClickOn()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                if (left) sender.sendLeftClickOff() else sender.sendRightClickOff()
                // Press and release are driven from the raw touch stream so the button can
                // be held down for a drag, but the view still has to report the click for
                // TalkBack and for anything else driving it accessibly.
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            }
        }
        return true
    }

    /**
     * Forwards one key press to the host. Called both from the IME (via
     * [HidInputConnection]) and from hardware key events arriving at the activity.
     */
    private fun forwardKey(event: KeyEvent): Boolean =
        keyboardSender?.sendKeyboard(event, holdModifiers) ?: false

    /**
     * Sends a typed character.
     *
     * Prefers the host-layout table, which knows which key *position* produces this
     * character on the host. Falls back to translating the character into Android key
     * events, which assumes the host uses US QWERTY positions.
     *
     * Shared by the IME (through [HidInputConnection]) and by clipboard sending.
     */
    private fun forwardChar(ch: Char): Boolean {
        val sender = keyboardSender ?: return false

        val strokes = hostLayout.strokes?.get(ch)
        if (strokes != null) {
            strokes.forEach { sender.sendStroke(it.usage, it.shift, it.altGr) }
            return true
        }

        val events = charMap.getEvents(charArrayOf(ch)) ?: return false
        for (event in events) {
            if (event.action == KeyEvent.ACTION_DOWN) forwardKey(event)
        }
        return true
    }

    /**
     * Asks before typing the clipboard, naming the host it will go to.
     *
     * The clipboard is where passwords and tokens live, and the HID link delivers to
     * whatever is on the other end -- which is not necessarily the machine the user has in
     * mind. A one-tap menu item that silently types it out is too easy to hit by accident,
     * so the target device and the length are shown first.
     */
    @SuppressLint("MissingPermission") // onStart bails out if BLUETOOTH_CONNECT is missing
    private fun sendClipboard() {
        val host = BluetoothController.hostDevice
        if (keyboardSender == null || host == null) {
            Toast.makeText(this, R.string.error_not_connected, Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()

        if (text.isNullOrEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val toSend = text.take(CLIPBOARD_MAX_CHARS)
        val message = if (text.length > CLIPBOARD_MAX_CHARS) {
            getString(
                R.string.clipboard_confirm_truncated,
                host.name ?: host.address,
                text.length,
                CLIPBOARD_MAX_CHARS
            )
        } else {
            getString(R.string.clipboard_confirm, host.name ?: host.address, toSend.length)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.clipboard_confirm_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clipboard_confirm_send) { _, _ -> typeOut(toSend) }
            .show()
    }

    /**
     * Types [text] to the host, one character at a time.
     *
     * Paced on the main thread rather than blasted from a background thread: the HID report
     * object is shared with normal typing, so serialising through the looper avoids
     * interleaving two writers. A single self-reposting runnable does the pacing -- posting
     * one message per character queued up to five thousand of them at once, none of which
     * could be cancelled when the screen went away.
     */
    private fun typeOut(text: String) {
        val handler = window.decorView.handler ?: return
        cancelClipboard()

        Toast.makeText(
            this,
            getString(R.string.clipboard_sending, text.length),
            Toast.LENGTH_SHORT
        ).show()

        var index = 0
        val job = object : Runnable {
            override fun run() {
                if (clipboardJob !== this || keyboardSender == null) return
                when (val ch = text[index]) {
                    '\n', '\r' ->
                        forwardKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

                    '\t' -> forwardKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
                    else -> forwardChar(ch)
                }
                if (++index < text.length) {
                    handler.postDelayed(this, CLIPBOARD_DELAY_MS)
                } else {
                    clipboardJob = null
                }
            }
        }
        clipboardJob = job
        handler.post(job)
    }

    private fun cancelClipboard() {
        clipboardJob?.let { window.decorView.handler?.removeCallbacks(it) }
        clipboardJob = null
    }

    /**
     * Explains the one failure the app cannot recover from itself.
     *
     * When the Bluetooth stack holds a registration belonging to a process that died without
     * unregistering, `registerApp()` succeeds and then nothing happens -- no host can
     * connect and no error is reported anywhere. Only restarting Bluetooth clears it. This
     * used to present as the app simply not working, with no clue as to why, so it is worth
     * a dialog that names the remedy.
     */
    private fun showStuckStackDialog() {
        if (isFinishing || isDestroyed || stuckStackDialogShown) return
        stuckStackDialogShown = true

        AlertDialog.Builder(this)
            .setTitle(R.string.error_registration_title)
            .setMessage(R.string.error_registration_message)
            .setNegativeButton(android.R.string.ok, null)
            .setPositiveButton(R.string.error_registration_open_settings) { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                } catch (e: Exception) {
                    Log.e(TAG, "Could not open Bluetooth settings", e)
                }
            }
            .show()
    }

    @SuppressLint("MissingPermission") // onStart bails out if BLUETOOTH_CONNECT is missing
    private fun promptToEnableBluetooth() {
        val adapter = BluetoothController.btAdapter ?: return
        if (!adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    @SuppressLint("MissingPermission") // onStart bails out if BLUETOOTH_SCAN is missing
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

    /**
     * Reflects the link state, naming the host rather than just saying "connected".
     *
     * Which device is on the other end matters: everything typed here goes to whatever
     * holds the HID link, so the user has to be able to see that it is their PC and not
     * some other bonded device that got there first.
     */
    @SuppressLint("MissingPermission") // onStart bails out if BLUETOOTH_CONNECT is missing
    private fun setConnected(host: BluetoothDevice?) {
        val icon = if (host != null) {
            R.drawable.ic_action_app_connected
        } else {
            R.drawable.ic_action_app_not_connected
        }

        // "Not connected" while the app is busy calling a PC that is waking up is the exact
        // confusion this feature exists to remove, so the calling state names its target.
        val calling = linkStatus.host
            ?.takeIf { host == null && linkStatus.state == BluetoothController.LinkState.CALLING }

        val label = when {
            host != null -> getString(R.string.status_connected_to, host.name ?: host.address)
            calling != null -> getString(
                R.string.status_reconnecting,
                calling.name ?: calling.address,
                linkStatus.attempt
            )

            else -> getString(R.string.status_not_connected)
        }
        bluetoothStatus?.setIcon(icon)
        bluetoothStatus?.tooltipText = label
        actionBar?.subtitle = label
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
            KeyboardReport.usageFor(event.keyCode) != null
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.select_device_activity_menu, menu)
        bluetoothStatus = menu?.findItem(R.id.ble_app_connection_status)
        setConnected(BluetoothController.hostDevice)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.action_send_clipboard)?.isVisible =
            Prefs.of(this).clipboardAction

        menu?.findItem(R.id.action_disconnect)?.setTitle(
            when {
                BluetoothController.hostDevice != null -> R.string.action_disconnect
                BluetoothController.reconnecting -> R.string.action_stop_calling
                else -> R.string.action_connect
            }
        )
        return super.onPrepareOptionsMenu(menu)
    }

    @SuppressLint("MissingPermission") // onStart bails out if BLUETOOTH_CONNECT is missing
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_devices -> {
            startActivity(Intent(this, DevicesActivity::class.java))
            true
        }

        R.id.action_send_clipboard -> {
            sendClipboard()
            true
        }

        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        R.id.action_help -> {
            startActivity(HelpActivity.intent(this))
            true
        }

        R.id.action_keyboard -> {
            toggleKeyboard()
            true
        }

        R.id.check_modifier_state -> {
            holdModifiers = !holdModifiers
            if (holdModifiers) {
                item.title = getString(R.string.action_check_held)
            } else {
                item.title = getString(R.string.action_check)
                keyboardSender?.sendNullKeys()
            }
            true
        }

        // One item, three directions: with no way back from the trackpad screen, a single
        // stray tap on Disconnect meant restarting the app. While the retry loop is running
        // it also has to be possible to call it off, or the only way out of a 90-second chase
        // of a PC that is switched off is to force-stop the app.
        R.id.action_disconnect -> {
            when {
                BluetoothController.hostDevice != null -> {
                    BluetoothController.disconnectHost()
                    onHostDisconnected()
                }

                // userRequested, or the page still in flight fails a moment later and the
                // automatic chase reads that as a fresh drop and starts all over again.
                BluetoothController.reconnecting ->
                    BluetoothController.stopReconnecting(userRequested = true)

                !BluetoothController.reconnect() ->
                    Toast.makeText(this, R.string.error_nothing_to_reconnect, Toast.LENGTH_SHORT)
                        .show()
            }
            invalidateOptionsMenu()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private companion object {
        const val TAG = "SelectDeviceActivity"

        /** ACTION_REQUEST_DISCOVERABLE takes seconds and caps at 3600. */
        const val DISCOVERABLE_SECONDS = 300

        /** Pacing between clipboard keystrokes; fast enough to feel instant. */
        const val CLIPBOARD_DELAY_MS = 12L

        /** Guard against pasting something enormous one keystroke at a time. */
        const val CLIPBOARD_MAX_CHARS = 5000

        /** Minimum gap between nudge toasts, so tapping a dead pad cannot queue a stack. */
        const val NUDGE_TOAST_INTERVAL_MS = 3000L
    }
}
