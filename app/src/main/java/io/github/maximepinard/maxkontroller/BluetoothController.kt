package io.github.maximepinard.maxkontroller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import io.github.maximepinard.maxkontroller.Prefs.preferredHost
import io.github.maximepinard.maxkontroller.reports.FeatureReport
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("MemberVisibilityCanBePrivate")
@SuppressLint("MissingPermission") // callers gate on AppPermissions in SplashScreen
object BluetoothController : BluetoothHidDevice.Callback(), BluetoothProfile.ServiceListener {

    const val TAG = "BluetoothController"

    /** Who currently needs the HID registration alive. See [acquire] / [release]. */
    enum class Owner { ACTIVITY, SERVICE }

    /**
     * Coarse link state, for anything that only wants to *display* what is going on.
     *
     * Deliberately separate from the sender callbacks: those rebuild the objects that write HID
     * reports and there can only be one owner of them, whereas several things need to show the
     * state at once -- the action bar and the service notification both, and the notification
     * is the only surface at all when the app is closed.
     */
    enum class LinkState { IDLE, CALLING, CONNECTED }

    /** A snapshot of what to show. [attempt] is only meaningful for [LinkState.CALLING]. */
    data class Status(val state: LinkState, val host: BluetoothDevice?, val attempt: Int)

    val featureReport = FeatureReport()

    var btAdapter: BluetoothAdapter? = null
        private set

    /**
     * Written from a Bluetooth binder thread (see the executor passed to `registerApp`)
     * and read from the UI thread, so both of these have to be volatile.
     */
    @Volatile
    var btHid: BluetoothHidDevice? = null

    @Volatile
    var hostDevice: BluetoothDevice? = null

    /**
     * The most recent host, kept after it disconnects so [reconnect] has something to aim
     * at. [hostDevice] is cleared on disconnect because it means "connected right now".
     */
    @Volatile
    var lastHost: BluetoothDevice? = null
        private set

    var autoPairFlag = false

    /**
     * Whether a lost link should be chased with repeated connection attempts rather than one.
     *
     * A single attempt is what made the app unable to wake a sleeping host. See
     * [startReconnecting] for why persistence is the whole mechanism.
     */
    var autoReconnectFlag = true

    /**
     * MAC address of the host the user pinned in [DevicesActivity], or null for "whatever
     * turns up". When set, auto-connect will only ever target this device -- keystrokes
     * (and the clipboard) then cannot be delivered to some other bonded device that
     * happens to appear first.
     */
    var preferredHost: String? = null

    /**
     * True once the stack has confirmed the HID app is registered.
     *
     * Not the same as `btHid != null`, which only means the profile proxy arrived.
     * `registerApp()` can be accepted and then never complete -- see [registrationWatchdog]
     * -- and in that state the app looks ready while nothing works.
     */
    @Volatile
    var appRegistered = false
        private set

    private val owners = mutableSetOf<Owner>()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var deviceListener: ((BluetoothHidDevice, BluetoothDevice) -> Unit)? = null
    private var disconnectListener: (() -> Unit)? = null
    private var registeredListener: (() -> Unit)? = null
    private var registrationFailedListener: (() -> Unit)? = null

    /**
     * Unlike the single-slot listeners above, these are a list and are *not* touched by
     * [clearListeners]: subscribers add and remove their own, because the service's has to
     * survive the activity going away.
     */
    private val statusObservers = CopyOnWriteArrayList<(Status) -> Unit>()

    /**
     * Application context, kept for the broadcast receivers and for persisting a pin that had
     * to be dropped. Held by a process-lifetime singleton, so it must be the application and
     * never an Activity.
     */
    private var appContext: Context? = null

    /** The host the retry loop is currently aiming at, or null when it is idle. */
    @Volatile
    private var reconnectTarget: BluetoothDevice? = null

    /**
     * [SystemClock.uptimeMillis] after which the retry loop gives up, or 0 when idle.
     *
     * Paging a host that is not answering costs radio time on both ends, so the loop is
     * always bounded -- a phone left in a drawer must not spend the night calling a PC that
     * is switched off.
     */
    @Volatile
    private var reconnectDeadline = 0L

    /** How many attempts the current loop has made. Reported through [addStatusObserver]. */
    @Volatile
    private var reconnectAttempt = 0

    /**
     * [SystemClock.uptimeMillis] before which a lost link must not be chased again.
     *
     * Without this the loop never ends. Giving up leaves the last page still in flight, and
     * when it times out a few seconds later it reports `STATE_DISCONNECTED` like any other
     * failure -- which [chaseLostLink] would read as a fresh drop and answer with a fresh
     * 45-second window, forever. The cooldown is long enough to swallow the trailing
     * callbacks of a loop that has already been abandoned.
     */
    @Volatile
    private var chaseBlockedUntil = 0L

    val reconnecting: Boolean get() = reconnectDeadline != 0L

    /**
     * True while a disconnect the *user* asked for is in flight.
     *
     * Without this the retry loop would immediately undo every deliberate disconnect, since
     * it cannot otherwise tell "the host went away" from "the user tapped Disconnect".
     */
    @Volatile
    private var userDisconnected = false

    /**
     * Fires when [BluetoothHidDevice.registerApp] was accepted but the stack never called
     * back to say the app is registered.
     *
     * Android allows one registered HID app at a time, and if a process holding a
     * registration dies without unregistering -- a crash, a force-stop, an uninstall
     * mid-session -- the stack can keep the dead one. `registerApp()` then returns
     * successfully, `onAppStatusChanged` never arrives, and every `connect()` silently does
     * nothing. Nothing distinguishes it from a working app that simply has no host, which is
     * why it has to be detected explicitly rather than left to the user to guess.
     *
     * Only a Bluetooth restart clears it.
     */
    private val registrationWatchdog = Runnable {
        if (appRegistered) return@Runnable
        Log.e(TAG, "registerApp() was accepted but never completed -- stale registration?")
        registrationFailedListener?.invoke()
    }

    /**
     * Registers [owner] as needing the HID profile and acquires the proxy if it is not up
     * yet. Returns false when the device has no Bluetooth adapter or the proxy request was
     * rejected -- which is also what happens on ROMs that ship without the Bluetooth HID
     * Device profile.
     */
    fun acquire(ctx: Context, owner: Owner): Boolean {
        owners += owner
        return init(ctx)
    }

    /**
     * Drops [owner]'s claim, tearing the registration down only once nobody is left.
     *
     * The reference counting is the point. Teardown used to be unconditional, so stopping
     * the foreground service unregistered the HID app even when the activity was in the
     * foreground still using it: toggling "stay connected" off in Settings and returning to
     * the trackpad silently killed the link, because [SelectDeviceActivity.onStart] calls
     * `HidService.stop()` and the service's `onDestroy` then ran after the activity had
     * already re-registered its listeners.
     */
    fun release(owner: Owner) {
        owners -= owner
        if (owners.isEmpty()) teardown()
    }

    /**
     * Drops the callbacks without touching the registration.
     *
     * They capture the Activity, and this object outlives it, so anything that goes away
     * has to call this or it leaks its whole view hierarchy. That is exactly what happened
     * on every rotation and theme change while "stay connected" was on: teardown was
     * skipped, so nothing ever cleared them.
     */
    fun clearListeners() {
        deviceListener = null
        disconnectListener = null
        registeredListener = null
        registrationFailedListener = null
        unpairedHostListener = null
    }

    /**
     * Subscribes [observer] to link-state changes and immediately hands it the current state.
     *
     * Callers must pass the same instance to [removeStatusObserver] when they go away --
     * [clearListeners] deliberately does not clear these, because the service's subscription
     * has to outlive the activity's.
     *
     * Fired from whichever thread the change happened on, so UI callers have to hop.
     */
    fun addStatusObserver(observer: (Status) -> Unit) {
        statusObservers += observer
        observer(currentStatus())
    }

    fun removeStatusObserver(observer: (Status) -> Unit) {
        statusObservers -= observer
    }

    fun currentStatus(): Status {
        val host = hostDevice
        return when {
            host != null -> Status(LinkState.CONNECTED, host, 0)
            reconnecting -> Status(LinkState.CALLING, reconnectTarget, reconnectAttempt)
            else -> Status(LinkState.IDLE, null, 0)
        }
    }

    private fun notifyStatus() {
        val status = currentStatus()
        statusObservers.forEach { it(status) }
    }

    private fun init(ctx: Context): Boolean {
        // BluetoothAdapter.getDefaultAdapter() is deprecated as of API 31.
        val adapter = btAdapter
            ?: ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Log.e(TAG, "This device has no Bluetooth adapter")
            return false
        }
        btAdapter = adapter
        registerReceivers(ctx.applicationContext)

        if (btHid != null) return true

        val requested = adapter.getProfileProxy(ctx, this, BluetoothProfile.HID_DEVICE)
        if (!requested) {
            Log.e(TAG, "getProfileProxy(HID_DEVICE) refused -- profile unsupported?")
        }
        return requested
    }

    /*******************************/
    /** Adapter and bond watching **/
    /*******************************/

    /**
     * Re-registers the HID app when Bluetooth is switched back on, and drops a pinned host
     * that has been unpaired.
     *
     * The adapter half closes an embarrassing gap: the stuck-registration dialog tells the
     * user to turn Bluetooth off and on again, and the app then did not come back by itself,
     * because nothing was watching. Toggling Bluetooth invalidates the profile proxy, so the
     * app looked exactly as broken afterwards as before and had to be restarted.
     */
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> onAdapterState(
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                )

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBondState(intent)
            }
        }
    }

    private var receiversRegistered = false

    private fun registerReceivers(app: Context) {
        appContext = app
        if (receiversRegistered) return

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Both are protected system broadcasts, so RECEIVER_NOT_EXPORTED is correct and is
        // mandatory from API 34 for a context-registered receiver.
        app.registerReceiver(adapterReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        receiversRegistered = true
    }

    private fun onAdapterState(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                Log.i(TAG, "Adapter going down")
                stopReconnecting()
                // The proxy does not survive the adapter, and holding a stale one is what made
                // every later connect() a silent no-op.
                btHid = null
                hostDevice = null
                appRegistered = false
                notifyStatus()
                disconnectListener?.invoke()
            }

            BluetoothAdapter.STATE_ON -> {
                // Nobody wants the registration any more; do not resurrect it behind their back.
                if (owners.isEmpty()) return
                val app = appContext ?: return
                Log.i(TAG, "Adapter back on, re-registering")
                init(app)
            }
        }
    }

    /**
     * Drops a pinned host that has just been unpaired.
     *
     * A pin is a MAC address, and once the bond is gone nothing can connect to it -- but the
     * pin restricts auto-connect to that address and nothing else, so the app went quietly
     * dead: no host, no attempts, no explanation. [lastHost] has to go too, or it becomes the
     * fallback target and the loop chases a device it can no longer reach.
     */
    private fun onBondState(intent: Intent) {
        if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) != BluetoothDevice.BOND_NONE) {
            return
        }
        val device = deviceFrom(intent) ?: return

        if (lastHost?.address == device.address) lastHost = null
        if (preferredHost != device.address) return

        Log.i(TAG, "Pinned host ${device.address} was unpaired; dropping the pin")
        stopReconnecting()
        preferredHost = null
        // Also persisted, or the activity and the service both push the stale pin straight
        // back into this object the next time either of them starts.
        appContext?.let { Prefs.of(it).preferredHost = null }
        unpairedHostListener?.invoke(device)
    }

    /** The typed overload only exists from API 33; minSdk is 28. */
    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    /** Fired when the pinned host was unpaired and the pin had to be dropped. */
    private var unpairedHostListener: ((BluetoothDevice) -> Unit)? = null

    fun onPinnedHostUnpaired(callback: (BluetoothDevice) -> Unit) {
        unpairedHostListener = callback
    }

    private fun teardown() {
        mainHandler.removeCallbacks(registrationWatchdog)
        stopReconnecting()
        btHid?.let { hid ->
            hid.unregisterApp()
            btAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        }
        btHid = null
        hostDevice = null
        appRegistered = false
        clearListeners()

        // Nothing wants the registration, so nothing needs to know when Bluetooth comes back.
        // A later acquire() re-registers these through init().
        if (receiversRegistered) {
            appContext?.unregisterReceiver(adapterReceiver)
            receiversRegistered = false
        }
    }

    /**
     * Registers [callback] to run whenever a host connects, and runs it immediately if one
     * already is.
     *
     * The listener is kept in *both* cases. It used to return early after the immediate
     * call, so when the activity started with the link already up -- the normal case, since
     * "stay connected" is on by default -- the callback was fired once and thrown away.
     * `deviceListener` was then null for the rest of the process, and the next
     * reconnection rebuilt nothing: no senders, no status update. Disconnecting once left
     * the app permanently unable to come back without a restart.
     */
    fun getSender(callback: (BluetoothHidDevice, BluetoothDevice) -> Unit) {
        deviceListener = callback

        val hid = btHid ?: return
        val host = hostDevice ?: return
        callback(hid, host)
    }

    /**
     * Opens the HID link again after a disconnect, waking the host if it is asleep.
     *
     * Auto-connect only runs on registration, so once the user (or a misclick) has dropped
     * the link there is otherwise nothing that brings it back. Returns false when there is
     * no host to reconnect to.
     */
    /**
     * [userInitiated] separates "someone tapped Connect" from "the app happened to start".
     *
     * A tap is intent and overrides the cooldown; [SelectDeviceActivity.onStart] is not, and
     * it runs on every return to the activity. Left equal, an app sitting open with the PC
     * switched off would begin a fresh 90-second chase -- holding a wake lock throughout --
     * every time the activity came back, with the cooldown cleared each time so nothing ever
     * damped it down.
     */
    fun reconnect(userInitiated: Boolean = true): Boolean {
        val target = autoTarget() ?: return false
        if (userInitiated) return startReconnecting(target, WAKE_WINDOW_MS)

        if (SystemClock.uptimeMillis() < chaseBlockedUntil) return false
        return startReconnecting(target, AUTO_CONNECT_WINDOW_MS, userInitiated = false)
    }

    fun getDisconnector(callback: () -> Unit) {
        disconnectListener = callback
    }

    /** Fired once the HID app is registered, i.e. once it is worth becoming discoverable. */
    fun onRegistered(callback: () -> Unit) {
        registeredListener = callback
    }

    /** Fired when registration was accepted but never completed. See [registrationWatchdog]. */
    fun onRegistrationFailed(callback: () -> Unit) {
        registrationFailedListener = callback
        // A listener attached after the deadline has already passed still needs telling.
        if (btHid != null && !appRegistered) {
            mainHandler.removeCallbacks(registrationWatchdog)
            mainHandler.postDelayed(registrationWatchdog, REGISTRATION_TIMEOUT_MS)
        }
    }

    /**
     * Opens the HID link to [device], retrying for [WAKE_WINDOW_MS] so a sleeping host has
     * time to wake up and finish resuming.
     *
     * Returns false when there is no registered HID app to connect with, which callers must
     * surface: a silent no-op here is indistinguishable from a working app that is simply not
     * connected yet, and that is precisely the confusion a wedged registration causes. It
     * cannot report whether the *first* page was accepted, because attempts are now made
     * asynchronously by the loop -- but a page the stack refuses is retried rather than lost,
     * which is the more useful behaviour anyway.
     */
    fun connectTo(device: BluetoothDevice): Boolean {
        if (btHid == null) return false
        if (!appRegistered) {
            Log.e(TAG, "connect() requested before the HID app is registered")
            return false
        }
        return startReconnecting(device, WAKE_WINDOW_MS)
    }

    /**
     * Drops the link to [device] at the user's request.
     *
     * Goes through here rather than straight to [BluetoothHidDevice.disconnect] so the retry
     * loop knows this disconnect was deliberate and leaves it alone.
     */
    fun disconnectHost(device: BluetoothDevice? = hostDevice): Boolean {
        val hid = btHid ?: return false
        val target = device ?: return false
        userDisconnected = true
        stopReconnecting()
        return hid.disconnect(target)
    }

    /*************************************/
    /** Reconnect / wake-the-host loop  **/
    /*************************************/

    /**
     * Pages [target] every [RECONNECT_INTERVAL_MS] for up to [windowMs], stopping as soon as
     * the link comes up.
     *
     * The persistence is the entire point, and it is what a real Bluetooth mouse does. When a
     * host suspends, its controller stays powered and listening for pages from bonded
     * devices; the *device* is what initiates. A mouse wakes a PC by paging it repeatedly for
     * as long as you keep moving it -- the first page wakes the machine and a later one lands
     * on a stack that is up again.
     *
     * A single attempt cannot do that, and that is what this app used to make: the host woke
     * up, but Android's page timed out after a few seconds while the resume (firmware reload,
     * `bluetoothd` re-init) was still going, and nothing tried again. The PC came back and the
     * app still said "not connected", which reads exactly like the wake having failed.
     *
     * Returns false when there is nothing to aim at.
     */
    fun startReconnecting(
        target: BluetoothDevice,
        windowMs: Long,
        userInitiated: Boolean = true
    ): Boolean {
        if (btAdapter == null) return false

        // Whatever asked for this outranks a previous deliberate disconnect. Only a genuine
        // user action outranks the cooldown left by a loop that gave up -- see [reconnect].
        userDisconnected = false
        if (userInitiated) chaseBlockedUntil = 0L

        val extending = reconnecting && reconnectTarget?.address == target.address
        val proposed = SystemClock.uptimeMillis() + windowMs

        reconnectTarget = target
        if (!extending) reconnectAttempt = 0

        // Set before the post, not inside it, so [reconnecting] is true the moment this
        // returns. Callers gate on it -- and so does [chaseLostLink], which runs on a binder
        // thread and would otherwise start a second loop in the window before the post ran.
        //
        // Never shortens a chase already running against the same host: the 30-second
        // auto-connect on registration lands in the middle of a 90-second wake the user
        // asked for, and would otherwise cut it down to 30.
        reconnectDeadline = if (extending) maxOf(reconnectDeadline, proposed) else proposed

        acquireChaseWakeLock()

        mainHandler.post {
            mainHandler.removeCallbacks(reconnectTick)
            Log.i(TAG, "Chasing $target for ${windowMs}ms")
            reconnectTick.run()
        }
        // Publish the CALLING state now. attemptReconnect only notifies once it has actually
        // paged, and it legitimately skips the first few ticks while the profile proxy and
        // the registration are still arriving -- during which the UI would otherwise still be
        // saying "Not connected" about a chase that is already running.
        notifyStatus()
        return true
    }

    /**
     * Stops the retry loop. Safe to call when it is not running, and from any thread.
     *
     * [userRequested] additionally blocks the automatic chase for [CHASE_COOLDOWN_MS].
     * Without it, stopping was undone a few seconds later: the last page is still in flight
     * when the loop stops, and its failure arrives as `STATE_DISCONNECTED` like any other,
     * which [chaseLostLink] reads as a fresh drop and answers with a brand new window. The
     * one escape hatch from chasing a host that is switched off has to actually hold.
     */
    fun stopReconnecting(userRequested: Boolean = false) {
        mainHandler.removeCallbacks(reconnectTick)
        if (userRequested) chaseBlockedUntil = SystemClock.uptimeMillis() + CHASE_COOLDOWN_MS
        releaseChaseWakeLock()
        if (reconnectDeadline == 0L) return
        reconnectDeadline = 0L
        reconnectTarget = null
        reconnectAttempt = 0
        notifyStatus()
    }

    /**
     * Keeps the CPU up for the duration of a chase.
     *
     * The loop is a [Handler] on [SystemClock.uptimeMillis], and neither that clock nor a
     * posted callback advances while the device is in deep sleep. Without this the feature
     * fails in the case it exists for -- screen off, phone in a pocket, "stay connected" on --
     * because the ticks that are supposed to be paging the host are simply deferred. It also
     * makes the window mean what its documentation says: an unheld budget is not spent during
     * sleep, so a 45-second chase could otherwise resume hours later.
     *
     * Held only while a chase is running, and bounded a second time by an acquire timeout in
     * case a release is ever missed. WAKE_LOCK has been declared in the manifest since
     * upstream without anything using it.
     */
    private var chaseWakeLock: PowerManager.WakeLock? = null

    private fun acquireChaseWakeLock() {
        val lock = chaseWakeLock ?: run {
            val power = appContext?.getSystemService(PowerManager::class.java) ?: return
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                // Acquire/release are called from several paths and are not paired one to
                // one; an un-counted lock makes a redundant release harmless.
                setReferenceCounted(false)
                chaseWakeLock = this
            }
        }
        if (!lock.isHeld) lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseChaseWakeLock() {
        chaseWakeLock?.takeIf { it.isHeld }?.release()
    }

    private val reconnectTick = object : Runnable {
        override fun run() {
            if (attemptReconnect()) {
                mainHandler.postDelayed(this, RECONNECT_INTERVAL_MS)
            } else {
                stopReconnecting()
            }
        }
    }

    /** Makes one attempt if it is worth making. Returns whether the loop should keep going. */
    private fun attemptReconnect(): Boolean {
        // Stopped while this tick was already in flight -- removeCallbacks cannot recall a
        // Runnable that is mid-execution. Not an expiry: it must not log "giving up" and must
        // not lay down a cooldown that would suppress a legitimate chase for half a minute.
        if (reconnectDeadline == 0L) return false

        if (hostDevice != null) return false
        if (SystemClock.uptimeMillis() > reconnectDeadline) {
            Log.i(TAG, "Giving up after $reconnectAttempt attempts")
            chaseBlockedUntil = SystemClock.uptimeMillis() + CHASE_COOLDOWN_MS
            return false
        }

        val target = reconnectTarget ?: return false

        // Bluetooth turned off mid-loop: there is no radio to page with, and the adapter
        // coming back re-registers the app and starts a fresh loop anyway.
        val adapter = btAdapter ?: return false
        if (!adapter.isEnabled) return false

        // Not ready yet rather than not working: the profile proxy and the registration both
        // arrive asynchronously, and on a cold start the loop can easily begin first. Keep
        // waiting -- the window is the budget, not the attempt count.
        val hid = btHid ?: return true
        if (!appRegistered) return true

        // A page is already in flight. Stacking another on top achieves nothing and the
        // stack may reject it outright, so let this one time out first.
        if (hid.getConnectionState(target) == BluetoothProfile.STATE_CONNECTING) return true

        reconnectAttempt++
        Log.i(TAG, "Attempt $reconnectAttempt: paging $target")
        hid.connect(target)
        notifyStatus()
        return true
    }

    /**
     * Which host the loop should aim at when nobody named one.
     *
     * A pinned host means that host or nothing -- falling back to "any bonded device" here
     * would let the loop hand the keyboard to whatever answers first, which is the hazard
     * [preferredHost] exists to close. Without a pin, the last host we actually reached is
     * the only safe guess.
     */
    private fun autoTarget(): BluetoothDevice? {
        val pinned = preferredHost ?: return hostDevice ?: lastHost
        return btAdapter?.bondedDevices?.firstOrNull { it.address == pinned }
    }

    /**
     * Chases a link that dropped on its own -- the host suspended, went out of range, or
     * turned off.
     *
     * Deliberately does nothing when a loop is already running. Every failed page reports
     * `STATE_DISCONNECTED`, so this fires once per attempt; restarting would push the
     * deadline out each time and the loop would never end.
     */
    private fun chaseLostLink() {
        if (!autoReconnectFlag || reconnecting) return
        if (SystemClock.uptimeMillis() < chaseBlockedUntil) return
        val target = autoTarget() ?: return
        Log.i(TAG, "Link lost, chasing $target")
        startReconnecting(target, RECONNECT_WINDOW_MS)
    }

    /*****************************************************/
    /** BluetoothProfile.ServiceListener implementation **/
    /*****************************************************/

    override fun onServiceDisconnected(profile: Int) {
        Log.e(TAG, "Service disconnected!")
        if (profile == BluetoothProfile.HID_DEVICE) btHid = null
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        Log.i(TAG, "Connected to service")
        if (profile != BluetoothProfile.HID_DEVICE) {
            Log.w(TAG, "Unexpected profile $profile")
            return
        }

        val btHid = proxy as? BluetoothHidDevice
        if (btHid == null) {
            Log.e(TAG, "Proxy received but it is not a BluetoothHidDevice")
            return
        }
        this.btHid = btHid
        // registerApp() returning true only means the call was accepted. Give the stack a
        // few seconds to actually confirm it, then assume it is wedged.
        mainHandler.removeCallbacks(registrationWatchdog)
        mainHandler.postDelayed(registrationWatchdog, REGISTRATION_TIMEOUT_MS)

        // The executor runs inline, so every callback below arrives on a Bluetooth binder
        // thread. That is deliberate: onGetReport/onSetReport have to answer within the
        // HIDP handshake timeout and must not queue behind whatever the UI thread is doing.
        // The cost is that shared state is cross-thread, which is why btHid and hostDevice
        // are @Volatile and everything touching views hops via runOnUiThread.
        btHid.registerApp(sdpRecord, null, qosOut, { it.run() }, this)

        // Upstream called the hidden BluetoothAdapter.setScanMode(Int, Int) by
        // reflection here. That method is gone on Android 12+ and the reflective lookup
        // threw NoSuchMethodException, taking out the whole connection path. The
        // activity now asks for discoverability through ACTION_REQUEST_DISCOVERABLE.
    }

    /************************************************/
    /** BluetoothHidDevice.Callback implementation **/
    /************************************************/

    override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
        super.onSetReport(device, type, id, data)
        Log.d(TAG, "onSetReport device=$device type=$type id=$id")
        // HIDP requires a handshake for every SET_REPORT. Upstream only logged, so the
        // host waited for a reply that never came and bluetoothd reported
        // "HIDP SET_REPORT request timed out" (typically the host setting keyboard LEDs).
        btHid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
    }

    override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
        super.onGetReport(device, type, id, bufferSize)
        if (type == BluetoothHidDevice.REPORT_TYPE_FEATURE) {
            featureReport.wheelResolutionMultiplier = true
            featureReport.acPanResolutionMultiplier = true
            val replied = btHid?.replyReport(device, type, FeatureReport.ID, featureReport.bytes)
            Log.d(TAG, "Feature report replied=$replied")
        }
    }

    override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
        super.onConnectionStateChanged(device, state)
        Log.i(
            TAG, "Connection state ${
                when (state) {
                    BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                    BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                    BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                    BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                    else -> state.toString()
                }
            }"
        )
        if (state == BluetoothProfile.STATE_CONNECTED) {
            if (device != null) {
                hostDevice = device
                lastHost = device
                // A link that came up is proof the host is reachable, so the next drop
                // deserves a fresh chase regardless of what the previous loop concluded --
                // and regardless of a stale userDisconnected left by a disconnect that never
                // produced a state change.
                chaseBlockedUntil = 0L
                userDisconnected = false
                // stopReconnecting() notifies too, but only if a loop was running -- a
                // host-initiated connection arrives with no loop at all.
                stopReconnecting()
                notifyStatus()
                btHid?.let { deviceListener?.invoke(it, device) }
            } else {
                Log.e(TAG, "Connected state with no device")
            }
        } else {
            hostDevice = null
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                notifyStatus()
                disconnectListener?.invoke()

                // One-shot: a deliberate disconnect must not be chased, but the next
                // unexpected drop should be.
                val deliberate = userDisconnected
                userDisconnected = false
                if (!deliberate) chaseLostLink()
            }
        }
    }

    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
        super.onAppStatusChanged(pluggedDevice, registered)
        appRegistered = registered
        if (!registered) {
            Log.w(TAG, "HID app registration was lost")
            return
        }
        mainHandler.removeCallbacks(registrationWatchdog)

        registeredListener?.invoke()

        // getDevicesMatchingConnectionStates() returns an empty list when nothing has
        // ever been paired. Upstream indexed [0] unconditionally, so a first run with no
        // paired host crashed here with IndexOutOfBoundsException.
        val knownDevices = btHid?.getDevicesMatchingConnectionStates(
            intArrayOf(
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTING
            )
        ).orEmpty()
        Log.d(TAG, "Known HID hosts: $knownDevices")

        if (!autoPairFlag) return

        // With a host pinned, that host or nothing. Without one, fall back to whatever the
        // stack offers -- which is convenient but means anything the phone has ever been
        // bonded with can become the keystroke sink, so DevicesActivity nudges towards
        // pinning one.
        val pinned = preferredHost
        val target = if (pinned != null) {
            (knownDevices + listOfNotNull(pluggedDevice)).firstOrNull { it.address == pinned }
                ?: return
        } else {
            pluggedDevice ?: knownDevices.firstOrNull() ?: return
        }

        if (btHid?.getConnectionState(target) == BluetoothProfile.STATE_DISCONNECTED) {
            Log.i(TAG, "Auto-connecting to $target")
            // Remember it even if the loop is skipped, so a later reconnect has an aim.
            lastHost = target
            if (autoReconnectFlag) {
                startReconnecting(target, AUTO_CONNECT_WINDOW_MS)
            } else {
                btHid?.connect(target)
            }
        }
    }

    private val sdpRecord by lazy {
        BluetoothHidDeviceAppSdpSettings(
            "MaxKontroller",
            "Android Bluetooth keyboard and mouse",
            "MaxKontroller",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            DescriptorCollection.MOUSE_KEYBOARD_COMBO
        )
    }

    /**
     * How long the stack gets to confirm the registration. It normally answers in a few
     * milliseconds; a wedged one never answers at all.
     */
    private const val REGISTRATION_TIMEOUT_MS = 4000L

    /**
     * Gap between connection attempts.
     *
     * Not much shorter than this: a page that finds nothing takes several seconds to time out
     * on its own, and [attemptReconnect] skips a tick while one is still in flight, so a
     * tighter interval mostly just spins. Not much longer either -- the host's controller only
     * wakes the machine when it actually hears a page, so the gaps are dead time during which
     * a PC that just went to sleep is not being called.
     */
    private const val RECONNECT_INTERVAL_MS = 3_000L

    /**
     * Budget for chasing a link that dropped by itself.
     *
     * Long enough to cover a suspend/resume cycle plus BlueZ coming back, short enough that
     * walking out of range does not leave the phone paging for minutes.
     */
    private const val RECONNECT_WINDOW_MS = 45_000L

    /**
     * Budget when the user explicitly asked to connect.
     *
     * Longer, because a cold resume from S3 with a firmware reload can genuinely take half a
     * minute, and someone who just tapped Connect is waiting and watching rather than
     * wondering why their phone is warm.
     */
    private const val WAKE_WINDOW_MS = 90_000L

    /** Budget for the automatic attempt on registration, i.e. when the app is opened. */
    private const val AUTO_CONNECT_WINDOW_MS = 30_000L

    /** How long after giving up an unprompted drop is left alone. See [chaseBlockedUntil]. */
    private const val CHASE_COOLDOWN_MS = 30_000L

    private const val WAKE_LOCK_TAG = "MaxKontroller:chase"

    /**
     * Backstop on the chase wake lock. Comfortably longer than the longest window
     * ([WAKE_WINDOW_MS]) so it never cuts a legitimate chase short, but short enough that a
     * release lost to a crash cannot flatten the battery.
     */
    private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60_000L

    private val qosOut by lazy {
        BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )
    }
}
