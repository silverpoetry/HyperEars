# Native ownership replay after Bluetooth restart

## Scope

Preserve MiLink's authoritative, sticky SYSTEM ownership decision while delivering
that existing decision again when the Bluetooth process starts a new accepted
active session. No Apple activation, transport-policy change, new receiver API,
polling, or resource monitor is included.

## Cause and minimal change

`handleStateChanged` previously skipped native admission for SYSTEM-owned devices,
while the native admission hook published only on the first ownership transition.
MiLink surviving a Bluetooth restart therefore retained ownership but never told
the new Bluetooth session about it.

1. Record the last successfully delivered session token per normalized address in
   `MiLinkDeviceOwnershipRegistry`; clear it with the existing registry reset.
2. After accepting state through `ProcessStateStore`, replay a SYSTEM claim only
   for an active session with a nonblank current token. Older/retired state keeps
   using the existing state-store rejection rules.
3. Keep initial authoritative native claims available even before a module session
   exists. Their existing address-only broadcast still targets MiLink and Bluetooth.
4. Mark delivery only after both broadcasts return successfully. Failed delivery
   remains retryable on the next accepted update or existing pending flush. Pending
   entries distinguish initial claims from active-session-only replays, so flushing
   a disconnected replay never sends a claim. An initial pending claim keeps its
   pre-session delivery permission.

Delivery serialization uses the existing ownership registry lock. There is no new
thread or timer. Broadcast submission is not a receiver acknowledgement; this change
preserves that existing delivery contract rather than adding a cross-process protocol.

## Validation

- RED: four new Robolectric hook-level tests compiled and failed on missing replay
  assertions against the original production implementation.
- GREEN: the same four tests passed after the minimal registry/hook change, together
  with existing ownership-registry and device-state-registry tests.
- Additional regression checks cover pending initial delivery without context or
  session, replay retry after context recovery, and pending replay after disconnect.
- Focused command:

  ```powershell
  .\gradlew.bat --no-daemon :system-module:testDebugUnitTest `
    --tests dev.hyperears.hook.MiLinkSystemOwnershipReplayTest `
    --tests dev.hyperears.hook.MiLinkDeviceOwnershipRegistryTest `
    --tests dev.hyperears.bridge.DeviceStateRegistryTest
  ```

No device, Bluetooth process restart, remote mutation, or commit is performed by
this implementation task. Real Bluetooth restart acceptance remains separate.
