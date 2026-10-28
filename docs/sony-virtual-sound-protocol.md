# Sony V1 VPT and sound position

## Exact source contract

SonyPods3f701ff8 `SonyFunctionType.kt:315–316` gives V1 functions **0x41/0x42**
(decimal65/66), VPT and SOUND_POSITION. Gadgetbridge86703c8
`SonyProtocolImplV1.java:526–571,1248–1327`, `PayloadTypeV1.java:41–44`, and
`prefs/SurroundMode.java`, `prefs/SoundPosition.java` give executable query/write/report
handling and finite options. Only protocol facts are independently implemented; no
AGPL program code is copied.

SonyHeadphonesClient5620e8ed5deccb957338b54e371b215146080819
`Client/CommandSerializer.cpp:188–196` independently confirms the three-byte write.
`Client/Constants.h:92–128` confirms all option values; `Headphones.cpp:102–133`
treats VPT and position as interacting choices, so this implementation reads back both
advertised fields after either changes rather than inventing a partner value.

All commands below use SPP outer Table1 **0x0C**, only the confirmed V1 dialect:

- GET `46 type`; SET `48 type value`; RET/NOTIFY `47/49 type value`, exactly3bytes.
- Type01 VPT:00 off,01 outdoor stage,02 arena,03 concert hall,04 club.
- Type02 position:00 off,01 front-left,02 front-right,03 front,11 rear-left,12 rear-right.

Reserved values are not writable or accepted as known settings. These opcodes overlap
Table2 voice guidance but its outer0x0E and four-byte setting grammar remain separate.
V2 and model names do not acquire either feature. No independent executable availability
status grammar is present for this V1 family; no guessed42/43 boolean gate is added.

## Discovery, write and UI lifecycle

Only actual function discovery queues GET for the advertised type. Every advertised
member must have a valid current report before either selector is enabled. The typed
request pins the version, ordered discovered types, and full current pair. Valid
function removal or definition change clears all pair/pending evidence. Malformed
frames, unknown enums and echoed setters do not overwrite existing reports.

The existing ACK queue rejects controls while busy without retaining the rejected
command. Encoding has no side effects: successful transport write alone arms pending
readback and publishes that local pending flag. Both advertised types must subsequently
report before another write is allowed. No partner value or desired selection is
published optimistically. The pending UI exposes explicit Refresh, which resends reads
without declaring success or clearing the guard. Disconnect/reset clears the session.
There is no automatic retry loop or extra background timer.

Native Settings offers two finite lists; the existing Sony details offers the same
choices. Selection stays device-confirmed, and stale pair/definition/session/owner
callbacks are rejected. Tests use literal source payloads and a manually checksummed
SPP frame, actual adapter table isolation/queue/readback/reset, and native lifecycle
regressions. Build evidence remains separate from device validation.

## Separate optimizer workflow to complete later

Gadgetbridge V1 exposes function81 NC_OPTIMIZER, start/stop `84 01 00 01/00`,
status85 with exact4byte payload and progress values00 stopped,01 wearing-condition,
02 atmospheric-pressure,10 analyzing,11 finished. Parameter GET86 and RET87/NOTIFY89
supply a separate6byte pressure result. These are not VPT selections and do not prove
that starting optimization is harmless initialization. A later complete workflow needs
explicit start, current report qualification, owned bounded stop/release, result/lease
correlation, cancellation and native progress UI. This slice neither starts optimization
nor adds power-off commands; optimizer remains an identified source-backed next task.

### Optimizer follow-up contract (not implemented here)

Pinned Gadgetbridge V1 sources additionally expose function `81` (hex), Table1 only:
GET `86 01`, START `84 01 00 01`, STOP `84 01 00 00`. The four-byte `85` status ends
in `00` idle/stopped, `01` wearing condition, `02` atmospheric pressure, `10` analyzing,
or `11` finished. There is no proven failure code or running-state GET. Six-byte `87/89`
parameters end in pressure1..10 (0.1..1.0atm); the source example is `89 01 01 01 01 0A`.
Intermediate bytes are not independently understood. V2 getters/start are unimplemented upstream.
Source locations: `SonyProtocolImplV1.java:214–224,643–654,910–949` and
`SonyHeadphonesDeviceSettings.kt:84–160` at the already pinned Gadgetbridge revision.

The remaining engineering decision is ACK-aware cancellation: an ordinary write is rejected
while another command awaits ACK; closing cannot silently discard that transaction or reset
sequence numbers to claim a guaranteed stop. Any deadline would be host policy, not a proven
source completion duration. A future owned workflow must distinguish completion from local
cancel/timeout and document uncorrelated late status frames. The user's approved vendor-app
handoff for complex infrequent calibration remains an option using the existing channel yield
path. This document does not claim that detecting a function implements the optimizer workflow.
