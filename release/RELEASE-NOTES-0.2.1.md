# MaxKontroller 0.2.1

Upgrades over 0.2.0 in place. Requires Android 9+ and a ROM with the Bluetooth HID Device
profile (`adb shell getprop bluetooth.profile.hid.device.enabled` must print `true`).

## Added

- **Waking a sleeping host.** Opening the app or tapping the host under Devices now calls it
  repeatedly for up to a minute instead of trying once, the same way a Bluetooth mouse wakes a
  PC. A single attempt could never work: the host wakes, but the page times out while it is
  still resuming. *Whether the host wakes is up to the host: Windows and Mac usually can,
  on Linux it depends on the adapter and some cannot at all.*
- **Connection state is now visible** in the action bar and the service notification,
  "Calling <host>… (3)" rather than just "Not connected".
- **Touching the trackpad while disconnected** calls the host, instead of doing nothing.
- **Recovers on its own after Bluetooth is toggled off and on**, rather than needing a restart.
- **Keep screen on** now has a switch. The setting existed and was applied, but had no UI.
- Devices asks for confirmation before disconnecting a live host.
- A pinned host that gets unpaired is dropped automatically instead of leaving the app unable
  to connect to anything.

## Fixed

- "Stop calling the host" did not stop it: the in-flight attempt failed a moment later and
  restarted the whole thing.
- The "Calling…" state could fail to appear, leaving the app saying "Not connected" while it
  was actively calling.
- Repeatedly tapping a disconnected trackpad queued a stack of toasts.
- Reconnection now holds a wake lock while it runs, so it still works with the screen off.
- Opening the app no longer restarts a full-length reconnect attempt every time.

## Checksum

```
sha256  a10a0231ca18e1d8cb340c94d178ff1298391b7632772556c6881e9d1107f92a
```

Signed with the same key as 0.2.0 (`a7:b2:11:48:…:2d:76`).
