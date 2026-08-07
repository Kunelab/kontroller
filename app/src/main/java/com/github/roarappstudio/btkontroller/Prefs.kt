package com.github.roarappstudio.btkontroller

import android.content.Context
import android.content.SharedPreferences

/** How the app picks its colour scheme. */
enum class ThemeMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), BLACK("black");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Screen orientation lock. */
enum class OrientationMode(val key: String) {
    PORTRAIT("portrait"), LANDSCAPE("landscape"), AUTO("auto");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: PORTRAIT
    }
}

/**
 * App settings.
 *
 * Upstream stored its two toggles with `Activity.getPreferences()`, which is scoped to a
 * single activity's own file. A settings screen has to share them, so everything now lives
 * in one named SharedPreferences file.
 */
object Prefs {

    private const val FILE = "maxkontroller"

    private const val KEY_CLICK_BAR = "click_bar"
    private const val KEY_MEDIA_KEYS = "media_keys"
    private const val KEY_CLIPBOARD = "clipboard_action"
    private const val KEY_AUTO_PAIR = "auto_pair"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_SENSITIVITY = "pointer_sensitivity"
    private const val KEY_BACKGROUND = "stay_connected"
    private const val KEY_THEME = "theme"
    private const val KEY_GYRO = "gyro_pointer"
    private const val KEY_GYRO_INVERT_X = "gyro_invert_x"
    private const val KEY_GYRO_INVERT_Y = "gyro_invert_y"
    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_HELP_SHOWN = "help_shown"
    private const val KEY_HOST_LAYOUT = "host_layout"
    private const val KEY_PREFERRED_HOST = "preferred_host"

    /** Sensitivity is stored as a percentage so it survives as a plain Int. */
    const val SENSITIVITY_MIN = 25
    const val SENSITIVITY_MAX = 300
    const val SENSITIVITY_DEFAULT = 100

    fun of(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Show the physical-style left/right click buttons under the trackpad. */
    var SharedPreferences.clickBar: Boolean
        get() = getBoolean(KEY_CLICK_BAR, true)
        set(value) = edit().putBoolean(KEY_CLICK_BAR, value).apply()

    /** Show the media / remote key row. Off by default -- opt in when you want a remote. */
    var SharedPreferences.mediaKeys: Boolean
        get() = getBoolean(KEY_MEDIA_KEYS, false)
        set(value) = edit().putBoolean(KEY_MEDIA_KEYS, value).apply()

    /** Offer "Send clipboard" in the menu. */
    var SharedPreferences.clipboardAction: Boolean
        get() = getBoolean(KEY_CLIPBOARD, true)
        set(value) = edit().putBoolean(KEY_CLIPBOARD, value).apply()

    /**
     * Let the phone initiate the HID connection as soon as it registers. Defaults on: a
     * host-initiated `connect` tends to bring up audio profiles instead of HID, so the
     * phone opening the connection is the reliable direction.
     */
    var SharedPreferences.autoPair: Boolean
        get() = getBoolean(KEY_AUTO_PAIR, true)
        set(value) = edit().putBoolean(KEY_AUTO_PAIR, value).apply()

    /**
     * Keep paging the host after the link drops, instead of giving up after one attempt.
     *
     * Defaults on. This is what lets the phone wake a sleeping host the way a Bluetooth mouse
     * does -- see `BluetoothController.startReconnecting` -- and it also means a link lost to
     * a brief walk out of range comes back on its own. Bounded either way, so it cannot page
     * indefinitely.
     */
    var SharedPreferences.autoReconnect: Boolean
        get() = getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    var SharedPreferences.keepScreenOn: Boolean
        get() = getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    /** Pointer speed as a percentage; 100 means raw 1:1 touch deltas. */
    var SharedPreferences.sensitivity: Int
        get() = getInt(KEY_SENSITIVITY, SENSITIVITY_DEFAULT)
            .coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
        set(value) = edit()
            .putInt(KEY_SENSITIVITY, value.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX))
            .apply()

    /** Convenience: [sensitivity] as a multiplier. */
    val SharedPreferences.sensitivityFactor: Float
        get() = sensitivity / 100f

    /** Run the foreground service so the HID link survives leaving the app. */
    var SharedPreferences.stayConnected: Boolean
        get() = getBoolean(KEY_BACKGROUND, true)
        set(value) = edit().putBoolean(KEY_BACKGROUND, value).apply()

    var SharedPreferences.theme: ThemeMode
        get() = ThemeMode.from(getString(KEY_THEME, null))
        set(value) = edit().putString(KEY_THEME, value.key).apply()

    /** Move the pointer by tilting the phone instead of dragging on the pad. */
    var SharedPreferences.gyroPointer: Boolean
        get() = getBoolean(KEY_GYRO, false)
        set(value) = edit().putBoolean(KEY_GYRO, value).apply()

    /** Which way feels natural depends on how the phone is held, so both axes can flip. */
    var SharedPreferences.gyroInvertX: Boolean
        get() = getBoolean(KEY_GYRO_INVERT_X, false)
        set(value) = edit().putBoolean(KEY_GYRO_INVERT_X, value).apply()

    var SharedPreferences.gyroInvertY: Boolean
        get() = getBoolean(KEY_GYRO_INVERT_Y, false)
        set(value) = edit().putBoolean(KEY_GYRO_INVERT_Y, value).apply()

    var SharedPreferences.orientation: OrientationMode
        get() = OrientationMode.from(getString(KEY_ORIENTATION, null))
        set(value) = edit().putString(KEY_ORIENTATION, value.key).apply()

    /**
     * Auto-rotate cannot be combined with the gyro pointer: tilting the phone to move the
     * cursor would spin the screen. Auto silently degrades to portrait while gyro is on.
     */
    val SharedPreferences.effectiveOrientation: OrientationMode
        get() = orientation.let {
            if (it == OrientationMode.AUTO && gyroPointer) OrientationMode.PORTRAIT else it
        }

    /**
     * The keyboard layout configured on the *host*. HID sends key positions, so this is what
     * decides which position we send for a given character.
     */
    var SharedPreferences.hostLayout: HostLayout
        get() = HostLayout.from(getString(KEY_HOST_LAYOUT, null))
        set(value) = edit().putString(KEY_HOST_LAYOUT, value.key).apply()

    var SharedPreferences.helpShown: Boolean
        get() = getBoolean(KEY_HELP_SHOWN, false)
        set(value) = edit().putBoolean(KEY_HELP_SHOWN, value).apply()

    /**
     * MAC address of the host to auto-connect to, or null for "whichever one turns up".
     *
     * Everything typed goes to whatever holds the HID link, the clipboard included, so
     * leaving this unset means any device the phone has ever been bonded with can end up
     * receiving keystrokes. Pinning one in [DevicesActivity] restricts auto-connect to it.
     */
    var SharedPreferences.preferredHost: String?
        get() = getString(KEY_PREFERRED_HOST, null)
        set(value) = edit().apply {
            if (value == null) remove(KEY_PREFERRED_HOST) else putString(KEY_PREFERRED_HOST, value)
        }.apply()
}
