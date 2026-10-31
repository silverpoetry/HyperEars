# TOZO NC20 Pro protocol research

Status: the user verified connection presentation and the native detail entry on the pre-split local build. This independently trimmed branch still requires device revalidation; advanced controls have code/frame tests, not blanket hardware acceptance.
Reference: installed TOZO App 5.4.3 (2026102101), package `com.woertec.tozo`.
Only independently implemented codecs and address-free protocol fixtures are included.

## Identity

The manufacturer's advertisement includes six Classic Bluetooth address bytes, a product
byte, `WR` and an optional batch byte. Legacy `WOER` advertisements have no batch.
The SDK retains the advertising BLE address separately; address association must come
from this report, never from a name or an assumed MAC offset.

Confirmed App identifiers:

- NC20 Pro earbuds: `D50D`, `140E`, `210E`.
- NC20 Pro charging case: `D509`, `1409`, `2109`.
- Standard NC20 uses other identifiers and must not match the Pro adapter.

`TozoIdentityCodec` validates AD boundaries and conflicts and reconstructs Android's
manufacturer entry prefix. Identifying a model does not grant its private capabilities.

## Command framing

The SDK's protocol-0 branch uses two command bytes in big-endian order, an unsigned
one-byte payload length, payload bytes and their sum modulo 256. `TozoWireCodec`
implements this format independently with exact length and checksum checks.

The App selects write/notification characteristics containing `b611`/`b612` and uses
writes without response. The actual D50D log confirms service `00004d45-0000-1000-8000-00805f9b34fb`,
TX `0000b611-0000-1000-8000-00805f9b34fb` and notify
`0000b612-0000-1000-8000-00805f9b34fb`. The D509 box uses a different service:
`000001ff-3c17-d293-8e48-14fe2e4da212`. The candidate uses the paired device address, as observed on the user's D50D unit.
Companion-address association is not enabled; other batches still need endpoint validation.
The exact service excludes the case, and valid individual reports gate each capability. Audio/AI/OTA packet formats are outside this command codec.

Tests cover identity, framing, state parsing and address-free official-App frames.
`TozoHeadsetCodec` separates reported noise state from command acknowledgement; status zero
on a write reply does not prove that the requested mode is now active.
Native detail integration must follow the shared Xiaomi-style page requirement; complex
OTA may hand off to the vendor App under the existing control-ownership mechanism.

## Local adapter boundary

`TozoNc20ProAdapter` requests firmware, battery and current noise state. NC20 and case
names are excluded; a system-native device is always left to Xiaomi. The official
ANC V2 model exposes off, ANC, transparency, wind, leisure and adaptive modes. Custom
ANC is not offered. Leisure/adaptive retain their typed state rather than appearing as off.
Write acknowledgements do not change the displayed state; each write requests authoritative
noise readback. Reset clears the model-owned feature state and its control permissions.

The TOZO controller package participates in the existing ownership policy, with matching
package visibility and both scope declarations. No new polling, scan, connection timer or
background job is introduced. This candidate uses the existing GATT session lifecycle.

The shared Xiaomi native detail entry now includes NC20 Pro, paired-ear battery, a generated
model illustration, all six noise choices and firmware version as the last row. A single native
choice preserves leisure/adaptive state without misusing the stock three-mode ANC buttons.
Rows are keyed and reused across reports. Firmware version is not an update-check result.
Final split-branch hardware verification remains outstanding; local UI tests do not prove
the real ROM route, appearance, or device writes.

## Advanced EQ transport and framing

The captured advanced-EQ read is `0018` (40-byte body), notification `201f`, and
write `1018` (41-byte body). Each of ten records contains little-endian frequency16,
signed gain8 in tenths of a dB, and a separate unsigned Q byte. Writes prefix an
explicit persistence flag. Both HomeViewModel and current FunctionViewModel encoders
agree; the codec reproduces the captured write byte for byte, preserving fractional
and negative gains. It is connected to the session and the native gain editor described below.

The 45-byte framed write needs ATT MTU >=48. NC20 Pro now requests 220 before service
discovery and rejects an insufficient result rather than issuing an oversized atomic
write. This opt-in setup leaves ordinary adapters unchanged. Existing connection
cancellation/deadline handling owns a missing callback; no new polling timer is added.

## Native EQ editing

The session requests advanced EQ after its basic state reads and accepts `0018`/`201f`
reports as the authoritative vector. `SetEqualizer` carries the entire expected baseline;
a changed frequency, Q or gain rejects a stale submission. Writes preserve reported
frequency/Q values, persist one ten-gain vector, then request readback. ACK alone never
publishes requested settings; reset clears EQ state and permission to write.

The native-themed editor follows the reference SeniorEQView gain bounds of -5 to +5 dB
in 0.1 dB increments. Dragging updates a local draft with value captions and scale labels;
Apply sends once, Cancel sends nothing. Session/ownership changes close the editor, and
Apply rechecks the baseline. Other report updates do not replace the draft. Frequency/Q
editing and preset selection are not implemented by this gain-only editor.

Validation includes actual Android dialog Apply/Cancel and draft behavior, host-row
registration, request/state serialization, stale/range/reset rejection, and captured
write/readback frames. Advanced EQ real-device acceptance remains open.

## V7 touch settings

TouchConvertAdapter assigns all three NC20 Pro identifiers to V7. TouchControlSettingActivity
reads `0003` and writes `1003`: ten bytes, four actions plus a reserved byte per ear.
Single/double/triple taps offer none, play/pause, next, previous, assistant, volume up/down,
noise cycling and ANC/off cycling. Hold omits play/pause/next/previous. Game and AI actions
from other profiles are not offered. These mappings are source-derived, not captured-device
validation; the existing log did not include a touch-settings query.

The adapter requires a full valid report before publishing eight native choice rows.
A pending full-vector write disables all eight touch choices until a complete report arrives.
A single delayed read retries after two seconds; if the device remains silent, controls stay
disabled until refresh/readback or reconnection rather than reusing an unsafe baseline.
Write failure closes the channel and the existing session reset clears the reservation.

An individual change preserves the other nine bytes, including both reserved positions,
and carries the expected whole configuration. Stale requests are rejected. ACK does not
change displayed assignments; readback does. Reset removes the feature and its permissions.
The SDK omits the last reserved right-ear byte when exposing its UI data; our independent
codec retains all ten bytes so edits do not overwrite unknown/reserved state.

## Android write completion

The local Android 35 BluetoothGatt source confirms a completion callback also applies to
WRITE_TYPE_NO_RESPONSE. The channel now awaits that callback before the next write, using
its existing deadline, and rejects callbacks from a different characteristic. This is an
Android queue completion, not proof the firmware applied a setting; protocol readbacks
remain necessary. A delayed-callback test reproduces the previous early return and verifies
serialization. The regression test withholds the Android callback and rejects an unrelated characteristic before allowing the queued write to complete.

## Daily settings and live battery

Captured reads confirm game `0006=0`, wear detection `0016=0`, multipoint `0025=1`,
and LDAC `0029=0`. Write commands add `1000`; notification IDs are respectively
`200f`, `201e`, `201c`, `2020`. Read/notification payloads must be exactly one byte,
0 or 1. Write replies do not publish settings. Battery notification `200e` now uses
the existing battery decoder, avoiding an initial-value-only display.

Each native switch appears only after its own report. LDAC/multipoint changes require
both states and revalidate the peer state at submission. Their readback queries both
settings; the module never invents the peer's resulting value or sends an additional
unrequested peer write. Official SettingsViewModel and App strings explicitly confirm
that LDAC changes restart the earbuds and enabling either conflicting mode disables
the other. Native confirmation describes that behavior and checks fresh state again
after confirmation. Game and wear detection use direct value-checked switches.

Daily-control tests cover codec, request validation and state readback. These are code and captured-frame checks, not physical acceptance.
