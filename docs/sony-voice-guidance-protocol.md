# Sony V1 voice guidance on/off

This adds an earbud/headphone voice-guidance toggle, not notification volume,
language installation or Android notification announcements. No name-based capability
is granted. Only the existing confirmed SPP V1 dialect with an actual Table1 support
function **0x39 (57 decimal)** and an exact Table2 parameter report gets this control.
V2 voice-guidance families remain excluded.

## Source contract

SonyPods3f701ff8 `SonyFunctionType.kt:314` identifies V1 VOICE_GUIDANCE0x39;
`SonyTandemV1Table2Protocol.kt:35–44,130–138` identifies the Table2 voice family and
inquiry01. Its generic GET only includes the inquiry; it does not give a concrete
setting-value parser or setter. Gadgetbridge86703c8006fc701a81505a28c48e399d1428e0c1
`SonyProtocolImplV1.java:596–618,1404–1427` provides executable concrete operations:

- GET `46 01 01` (inquiry01, setting01).
- SET `48 01 01 value`, value00 disabled or01 enabled.
- RET/NOTIFY `47/49 01 01 value`, exactly four bytes.
- `PayloadTypeV1.java:96–99` assigns these to COMMAND_2, SPP outer0x0E.

SonyPods uses internal Tandem Table2 identifiers; those are not blindly used as SPP
outer bytes. The implementation independently organizes these protocol facts; it does
not copy Gadgetbridge AGPL program code or vendor assets. SonyHeadphonesClient has
ambient focus-on-voice handling but no additional authoritative voice-guidance setting.

There is no executable availability-status grammar in these V1 sources. No invented
43/45 boolean polarity is used. A function alone permits the harmless exact GET, not
a writable feature. Function removal clears the value, and rediscovery needs a fresh
parameter report. Malformed counts/shapes, echoed SET/GET, unknown settings and V2
reports do not grant or overwrite a valid feature. Language and volume are not inferred.

## Session and native behavior

The typed definition includes version/table/inquiry/setting, plus each request pins the
previous confirmed boolean. The adapter and protocol session both validate it. Writes
use the existing ACK queue and immediately enqueue the exact GET readback. A busy queue
rejects the user command without retaining it for later execution. No optimistic value
is published. Reset removes the feature; outer Table1 reports cannot populate Table2
state, and Table2 function-looking bytes cannot discover Table1 capabilities.

The native settings switch and existing Sony details use the same request policy and
current owner/address/channel/session gates. Settings-only presentation works without
invented ANC state. Closing or replacing the UI session disables stale callbacks.

Tests include exact source-shaped payloads, a manually formed SPP checksum fixture,
actual adapter discovery/readback/reset, Table1/Table2 isolation, busy queue rejection,
function removal and stale definitions/current values, plus native report-only switches.
These are source-backed unit fixtures, not newly captured hardware responses.
