# MaxKontroller

Use an Android phone as a **Bluetooth keyboard and mouse** for a PC, Mac, TV or tablet.

The phone registers itself as a Bluetooth HID *device*, so the host sees a plain Bluetooth
keyboard and mouse. Nothing has to be installed on the host, and because input arrives at
the kernel/evdev level it works regardless of whether the host runs X11, Wayland or no
desktop at all.

MaxKontroller is a modernised and extended fork of
[raghavk92/Kontroller](https://github.com/raghavk92/Kontroller) (Apache-2.0), whose last
code change was in November 2020 and which no longer builds or runs on current Android.
The original commit history is preserved in this repository.

## Requirements

- Android 9 (API 28) or newer, **and** a ROM that ships the Bluetooth HID Device profile.
  Check with:
  ```sh
  adb shell getprop bluetooth.profile.hid.device.enabled   # must print: true
  ```
  This is optional for OEMs; if it prints nothing or `false`, this app cannot work on that
  phone.
- JDK 17 and the Android SDK (platform 36, build-tools 36) to build.

## Building

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

There are no third-party dependencies. The release APK is ~330 KB.

To build a signed release, copy `keystore.properties.example` to `keystore.properties`
(gitignored) and fill it in. Without one the build still works and produces an unsigned APK.

## Using it

| Action | Gesture |
|---|---|
| Move pointer | one finger drag on the pad |
| Left click | single tap, or the **Left click** button |
| Right click | the **Right click** button (recommended), or a two-finger tap |
| Double click | double tap |
| Drag & drop / rubber-band select | hold a click button and drag on the pad — or double-tap and hold the second tap, then drag |
| Scroll | two-finger drag |
| Keyboard | the keyboard icon in the action bar |

The **click buttons** under the pad behave like the physical buttons on a laptop touchpad:
pressing holds the mouse button down and releasing lets it go, so you can hold one and drag
on the pad above.

The `(N)` / `(P)` action-bar item switches modifier handling between *released after each
keystroke* and *held*, so you can do Ctrl+click style combinations with the soft keyboard.

### Settings

- **Pointer speed** — 25–300 %, where 100 % is the raw one-to-one touch delta. Applies to
  both the trackpad and the gyro pointer.
- **Gyro pointer (air mouse)** — move the pointer by tilting the phone. Pad movement is
  disabled while it is on so the two do not fight; taps, scroll and the click buttons keep
  working. Off by default.
- **Click buttons** — show the left/right click buttons under the pad. The trackpad shrinks
  to make room. On by default.
- **Connect automatically** — let the phone open the HID connection to a known host as soon
  as the app starts. On by default, because a host-initiated connection tends to bring up
  audio profiles instead of HID.
- **Stay connected in background** — runs a foreground service that owns the HID
  registration, so the phone keeps working as a keyboard and mouse when you leave the app or
  the screen turns off. Shows an ongoing notification with a Stop action. On by default.
- **Theme** — Light, Black, or follow the phone's setting.
- **Orientation** — Portrait, Landscape or Auto-rotate. Auto-rotate is unavailable while the
  gyro pointer is on, since tilting the phone to aim would spin the screen; an
  already-selected Auto falls back to portrait.
- **Keep screen on** — stop the screen turning off while the app is open.

- **Media keys** — show a row of volume, playback and Home keys, turning the app into a
  TV-style remote. Off by default.
- **Send clipboard** — adds a menu item that types the phone's clipboard to the host.

A getting-started guide opens on first launch and is available afterwards from the overflow
menu or the bottom of Settings.

The overflow menu also has **Devices**, which lists the paired devices with their live HID
connection state; tap one to connect or disconnect it. (It lists everything the phone is
paired with, speakers included — only a host can actually accept a keyboard connection.)

Tap the **star** next to a device to make it the preferred host. This matters for more than
convenience: everything typed goes to whatever holds the HID link, the clipboard included,
and with no preferred host set *Connect automatically* will target whichever bonded device
the Bluetooth stack offers first. Pinning one is how you say which machine is allowed to be
on the other end. The action bar shows the host currently connected.

## Pairing with a Linux host (BlueZ)

Tested against Debian 13 with BlueZ 5.82.

1. Open the app and allow the discoverability prompt.
2. On the host, pair and trust the phone:
   ```sh
   bluetoothctl
   > agent on
   > default-agent
   > scan on          # wait for the phone to appear, then note its MAC
   > pair  <PHONE_MAC>
   > trust <PHONE_MAC>
   ```
   Confirm the passkey on both ends.
3. With **Connect automatically** enabled (the default), the phone opens the HID connection
   itself from then on — just launch the app.

Two `/dev/input` devices appear on the host once connected:

```
N: Name="<phone> Mouse"      H: Handlers=mouse0 event12
N: Name="<phone> Keyboard"   H: Handlers=sysrq kbd event13
```

### Troubleshooting

**The host sees an audio device but no keyboard or mouse.** A plain `connect` brings up
whichever profiles win the race, usually A2DP/AVRCP. Either enable *Connect automatically*
so the phone initiates, or connect the HID profile explicitly:

```sh
bluetoothctl connect <PHONE_MAC> 00001124-0000-1000-8000-00805f9b34fb
```

**The HID UUID (`0x1124`) never shows up in `bluetoothctl info`.** BlueZ caches each
device's SDP records. If the host ever resolved services while the app was not registered,
that HID-less list is cached and will not be re-queried. Drop the cache entry (the pairing
itself survives):

```sh
sudo systemctl stop bluetooth
sudo rm /var/lib/bluetooth/<ADAPTER_MAC>/cache/<PHONE_MAC>
sudo systemctl start bluetooth
```

**`Could not parse HID SDP record` after clearing the BlueZ cache.** Deleting the cache file
removes the stored service records, and BlueZ does not re-fetch them just because it
reconnects — so `hidp_add_connection` finds nothing to parse. A plain profile-less connect
forces a full SDP browse and repopulates them:

```sh
bluetoothctl connect <PHONE_MAC>      # repopulates the record cache
```

Then let the phone open the HID link itself (*Connect automatically*), because a host-side
connect afterwards fails with `br-connection-create-socket` — the plain connect already
holds a link.

**After changing the HID descriptor**, a host that cached the old SDP record will not see the
new reports (e.g. media keys do nothing). Clear the cache as above, then re-browse and let
the phone reconnect.

**`Connection reset by peer` when connecting the HID profile.** Android permits only one
registered HID app at a time. If the app's process was killed without unregistering (a
crash, or `adb shell am force-stop`), the stack can hold the dead registration and refuse
new connections. Toggle Bluetooth off and on on the phone:

```sh
adb shell svc bluetooth disable && sleep 5 && adb shell svc bluetooth enable
```

## What changed from upstream

Upstream did not build at all: `jcenter()` is shut down, and Gradle 5.1.1 / AGP 3.4.1
cannot run on JDK 17.

**Added in this fork**
- **Media / consumer keys.** Upstream's descriptor had no consumer collection at all, so
  volume and playback keys had no report to travel in. A Consumer Control collection
  (report ID 10, one bit per key) was added to `MOUSE_KEYBOARD_COMBO`.
- **A device picker** (`DevicesActivity`) showing paired devices, their live HID state, and
  connect/disconnect. `SelectDeviceActivity` never listed devices despite its name.
- **Send clipboard as keystrokes**, paced on the main looper so it cannot interleave with
  normal typing on the shared HID report.
- A **settings screen**, with settings moved out of the overflow menu into a shared
  `SharedPreferences` store (upstream used `Activity.getPreferences()`, which is scoped to a
  single activity's file and cannot be shared with a settings screen).
- **Laptop-style click buttons** below the trackpad, with press-and-hold semantics so
  drag-and-drop works. This also gives a dependable right click, which the two-finger tap
  gesture does not.
- **A foreground service** owning the HID registration, so the connection survives leaving
  the app or the screen turning off. This is what upstream's unused `FOREGROUND_SERVICE`
  permission was presumably intended for; it declared the permission but had no service.
- **Pointer speed** setting, and the byte packing for relative movement pulled out of
  `ViewListener` into `RelativeMouseSender.sendMove()` (it previously inlined the packing and
  hard-coded report ID 4).
- **A working gyro pointer.** Upstream's `SensorSender` sent an `AbsMouseReport` on report
  ID 2, but ID 2 only exists in the unused `MOUSE_ABSOLUTE` descriptor — the active combo
  descriptor has no absolute-pointer report, so it could never have worked. `GyroPointer`
  differentiates yaw/pitch from the rotation vector and sends relative deltas on report ID 4
  like the trackpad does.
- **Light / Black / follow-system theming**, applied via `setTheme` before `onCreate` (the
  app deliberately avoids AppCompat, so `AppCompatDelegate` night mode is not available).
- **Orientation lock** (portrait / landscape / auto), with auto suppressed while the gyro
  pointer is active.
- **A getting-started guide** shown on first launch, covering pairing, gestures and the
  BlueZ pitfalls below.
- Renamed to **MaxKontroller** (app label and the SDP name the host displays).

**Build and toolchain**
- Gradle 5.1.1 → 8.13, AGP 3.4.1 → 8.9.1, Kotlin 1.3.21 → 2.1.20
- `compileSdk`/`targetSdk` 28 → 36; `minSdk` stays 28
- `jcenter()` → `mavenCentral()`, with modern `pluginManagement` / `dependencyResolutionManagement`
- Manifest `package=` → Gradle `namespace`
- Dropped Anko (archived), `kotlin-android-extensions` (removed in Kotlin 1.8), `kotpref`
  (never referenced) and the support-library `LocalBroadcastManager` (only used in
  commented-out code). The Anko-DSL UI is now a real layout XML.
- `inline class` → `@JvmInline value class` → plain classes. The report types wrap a
  *mutable* `ByteArray`, so value-class semantics were wrong for them, and R8 also crashed
  on the generated bytecode (`Invalid stack map table … iload 5`).
- Fixed `GestureDetector` overrides whose `MotionEvent?` parameters stopped compiling once
  SDK 36 tightened the nullability annotations.

**Correctness / runtime fixes**
- **Removed hidden-API reflection.** `Unhide.kt` called `BluetoothAdapter.setScanMode(int, int)`
  by reflection. That method does not exist on Android 12+, so the lookup threw
  `NoSuchMethodException` from `onServiceConnected` and killed the connection path. The app
  now uses the public `ACTION_REQUEST_DISCOVERABLE` intent. (`removeBond` and
  `cancelBondProcess` were never called and were deleted.)
- **Android 12+ permissions.** Upstream requested only `ACCESS_COARSE_LOCATION`, which was
  the pre-Android-12 requirement for *scanning* — something this app never does. It now
  requests `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` and `BLUETOOTH_SCAN` at runtime, and
  the legacy permissions are capped with `maxSdkVersion="30"`.
- **Keyboard input actually works.** Input was read solely from `Activity.onKeyUp`, which
  only ever sees the few keys an IME dispatches as raw key events; ordinary characters
  arrive via `InputConnection.commitText` and were silently dropped. `HidInputConnection`
  now handles `commitText`, `sendKeyEvent`, `deleteSurroundingText` and
  `performEditorAction`, converting text back into key events with `KeyCharacterMap` so the
  existing keycode → HID-usage table still does the mapping.
- **Keyboard toggle works.** `toggleSoftInput(SHOW_FORCED, 0)` is deprecated and a no-op on
  recent Android, so the button did nothing.
- **Keys send on press.** `onKeyDown` had its send call commented out and only `onKeyUp`
  sent a report.
- **HID registration survives dialogs.** `unregisterApp()` was called from `onStop`, so the
  discoverability prompt and the pairing dialog each tore down the HID service at exactly
  the moment the host was resolving it — the host then saw only an audio device. Teardown
  moved to `onDestroy`.
- **SET_REPORT handshake.** `onSetReport` only logged, never replying, so the host logged
  `HIDP SET_REPORT request timed out` (typically when setting keyboard LEDs). It now replies
  with `ERROR_RSP_SUCCESS`.
- **Fixed a first-run crash.** `onAppStatusChanged` indexed `[0]` into the result of
  `getDevicesMatchingConnectionStates()`, which is empty when nothing has been paired yet →
  `IndexOutOfBoundsException`.
- **Fixed six modifier-bit getters** in `KeyboardReport` (`leftAlt`, `leftGui`,
  `rightControl`, `rightShift`, `rightAlt`, `rightGui`) that all read the shift bit. The
  setters were correct, so the bug was latent.
- `android:exported` added to the launcher activity (required from targetSdk 31, a hard
  build error).
- The gyroscope is no longer `required="true"` in the manifest; it needlessly blocked
  installation, and the sensor-based pointer path is not wired up.

## Verified

Phone: realme 8 Pro (RMX3085), Android 13 / API 33.
Host: Debian 13, kernel 6.12, BlueZ 5.82, Realtek RTL8852BU adapter.

Confirmed by reading raw `/dev/input/event*` on the host:

- Pointer movement — expected `REL_X`/`REL_Y` deltas
- Typing — `hello` produced `H↓H↑E↓E↑L↓L↑L↓L↑O↓O↑`; `wasd`+Tab+Space likewise
- Click buttons — tap gives `BTN_LEFT`/`BTN_RIGHT` down+up ~10 ms apart; a 1.5 s press
  holds the button down for 1.52 s, which is what makes press-and-drag work
- Phone-initiated connection creates both the Mouse and Keyboard input devices with no
  host-side `connect` at all
- No `SET_REPORT` timeouts in the host's `bluetoothd` log

Scroll, drag & drop and double click confirmed by hand.

## Known limitations

- **The gyro pointer's gain is a first guess.** `PIXELS_PER_RADIAN` (900) and the noise floor
  were picked analytically, not tuned against a real screen, so it may feel too fast or too
  twitchy and want adjusting.
- No middle click — the HID descriptor declares `USAGE_MAXIMUM (Button 2)`, so adding one
  means editing `DescriptorCollection`, not just the report class.
- `KeyboardReport` sends a single key at a time (`key1`); simultaneous key rollover is not
  implemented.
- `applicationId` is still `com.github.roarappstudio.btkontroller`. Changing it is a
  one-line edit, but it installs as a separate app rather than upgrading in place.
- **"Keep screen on" has no switch.** The preference is read and applied, and the strings
  exist and are translated, but `activity_settings.xml` never got the row — so the setting
  documented above is currently unreachable and always off.
- **No tests and no CI.** The report bit-packing, the `HostLayout` tables and `sendMove`'s
  clamping are pure functions and the obvious place to start.
- Strings added after the initial translation pass are English-only in the other 21 locales;
  `MissingTranslation` is reported as a warning rather than failing the build.

## Post-review changes

A review of the whole project produced the following. The findings each fix are described in
the code, next to the thing they fix.

**Package size.** `proguard-rules.pro` contained `-keep class **`, `-keepclassmembers class
*{*;}` and `-keepattributes *`, so R8 ran but kept everything, kotlin-stdlib included: 2.19 MB
of dex in a 2.32 MB APK. Those rules are gone and `shrinkResources` is on. **2377 KB → 330 KB.**

**Lifecycle.**
- `HidService.onDestroy` released the HID registration unconditionally, so turning "stay
  connected" off and returning to the trackpad unregistered the HID app while the activity
  was still using it, and the app silently stopped working until restarted.
  `BluetoothController` now reference-counts its owners and only tears down when the last
  one goes.
- The three callbacks on `BluetoothController` capture the activity and were only cleared on
  teardown — which was skipped in the default configuration — so every rotation and theme
  change leaked a whole view hierarchy. They are now always cleared in `onDestroy`, and a
  configuration change no longer drops the host's link.
- `SelectDeviceActivity` re-checks the Bluetooth permissions in `onStart`. It is reachable
  straight from the service notification, bypassing the gate in `SplashScreen`.

**Edge to edge.** At `targetSdk` 36 Android no longer insets the window, and nothing in the
app asked for insets, so the click buttons and media row drew behind the navigation bar.
`SystemBars` applies them. *(Written against the framework API; not yet verified on a device.)*

**Pointer.** Movement was sent straight from `ACTION_MOVE` — 120-240 Hz of touch samples into
a link that carries 50-100 reports a second — and rounded to whole pixels per event, so slow
movement at low sensitivity was discarded entirely. `PointerPump` coalesces onto the frame
clock and carries the sub-pixel remainder.

**Gestures.** `GestureDetectListener` was rewritten. The two-finger right click listed above
as unreliable now times from the second finger landing rather than the first, uses the
300 ms double-tap window rather than the 100 ms single-tap one, and cancels if either
pointer travels past the touch slop. The single tap that follows is identified by
`MotionEvent.downTime` instead of a sticky flag that could swallow a later click.

**Threads.** Clicks scheduled their release with `Timer().schedule`, spawning a non-daemon
thread per click (three per double click) that mutated the shared report off the main
thread. All timing is now on the main looper. Clipboard sending posts one self-reposting
runnable instead of up to 5000 uncancellable messages.

**Host safety.** A host can be pinned from **Devices**; auto-connect then targets only that
device instead of whichever bonded device appears first. The action bar names the connected
host, and **Send clipboard** confirms first, naming the target.

**Dead code.** Deleted `Sender`, `MouseReport`, `AbsMouseReport`, `TrackpadMouseReport`,
`TestTrackpadMouseReport`, the empty `Kontroller` application class, ten unused HID
descriptors (`DescriptorCollection` 769 → 164 lines), ~200 lines of commented-out code, the
per-event debug logging in the touch path, twelve PNGs shadowed by `drawable-anydpi`
vectors, and four unused launcher-icon resources.

**Lint.** `assembleRelease` could not previously complete: `lintVital` fails on
`MissingTranslation`, and `app_name` was untranslated in all 21 locales. Real errors
(`MissingPermission` on all four HID send paths) are fixed and lint now runs clean.
