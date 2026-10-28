# Apple proximity ownership and disconnected case presentation

Implementation date: 2026-10-28. This records code and JVM verification, not device acceptance.

## Private bootstrap and identity

The admitted AACP session requests type 1 IRK and type 4 ENC. Opcode `0x31` is
consumed as one framed private record by `AppleAapWireCodec.Decoder`; keys never
become public feature state. The decoder bounds count to eight and record length
to 256, validates LE16 fields and exact 16-byte known keys, and rejects duplicate
records. Entire Apple receive chunks are redacted, including headerless fragments.

`AppleProximityOwner` associates an advertisement using the paired address or
Bluetooth `ah` with that identity's IRK before decoding battery/lid fields. AES-ECB
battery decryption itself does not provide a message authentication tag. Model IDs
from advertisements do not select or replace the admitted adapter. Source schema:
HyperPods-X `core/protocol/.../Proximity.kt`, as recorded in section 5 of
[the remaining-features audit](airpods5-remaining-features.md).

Keys remain in the Bluetooth process. The connected transport has a revocable
registration; a separate bounded retention owner receives a defensive key copy
only from that admitted transport. At most eight previously admitted paired
identities are retained. Unpairing, module pause, native ownership, disabled
adapter, configured vendor ownership and Bluetooth teardown revoke eligibility.
There is no disk persistence or key-bearing broadcast. A Bluetooth process restart
requires another admitted AACP bootstrap.

## Scanning and case events

Connected registration, disconnection and user-present events can request a
30-second low-power scan window. An active window is not renewed by repeated
requests or advertisements, and there is no periodic retry.

For openings long after the disconnect window, `AppleNativeScanObservationHook`
observes the existing Xiaomi process's framework scan-delivery callbacks. It
checks exact signatures of `BluetoothLeScanner.BleScanCallbackWrapper` before
installing transactional hooks. The inspected native source includes ordinary
`ScanCallback` consumers in `PeripheralConnectionManager` and
`MiuiThirdPartAppScanManager`. The observer starts no scan and changes no native
filter. Package-targeted, sender-authenticated broadcasts carry bounded public
Apple scan observations only while the Bluetooth retention owner subscribes.
Keys never cross this boundary. Complete batches larger than 32 are dropped;
stale, malformed and unowned observations do not publish case state.

This supplies an event-driven path beyond the initial scan window when the native
scanner delivers those advertisements. Actual ROM callback availability and
hardware filter coverage still require device verification; no universal passive
scan coverage has been demonstrated.

Capture timestamps, 900 ms settling and three-second debounce precede an open
edge. Equal-time contradictory lid samples cancel confirmation. Silence never
means closed. Precision BLE battery fills unavailable connected AACP components;
available AACP components keep precedence. Approximate nibbles are not presented
as precision readings.

Disconnected presentation uses a dedicated authenticated event, the same live
producer, prior admitted session/physical connection, and an unchanged inactive
`EarbudState` lifecycle. `fromPairedDiscovery` and the explicit native-window
request flag project that state without inventing a connection. Producer changes,
new sessions, native ownership, reconnects and unpairing invalidate old tickets.
Current pairing, unlocked interactive foreground user, native FastConnect setting,
scene policy and native display/circulation ownership still gate presentation.
Disconnected windows do not send refresh/control writes or launch connected-only
settings flows.

## Verification and acceptance boundary

JVM tests cover the published Bluetooth `ah` vector, wrong identities/keys,
truncation/count/length/duplicate record rejection, embedded headers inside key
records, generation revocation, precision field domains, lid ordering and
contradictory timestamps. Presentation tests cover inactive projection and ticket
rejection after native ownership, producer/session/connection replacement or
reconnection. Focused protocol, integration and module tests passed; no live
scanning, phone installation or actual physical case acceptance was performed.
