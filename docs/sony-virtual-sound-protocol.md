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

## Optimizer: implemented through acknowledged vendor-app handoff

The source-backed rare calibration workflow is delegated to Sony Sound Connect as
approved by the maintainer. SonyPods3f701ff8 `SonyFunctionType.kt:324` identifies
the V1 function; Gadgetbridge `SonyHeadphonesDeviceSettings.kt:84–160` supplies the
explicit calibration workflow used to establish the feature semantics. V1 Table1 support function **0x81 (129 decimal)** annotates
the existing native controller row as “降噪优化 · 打开 Sony App 并让出控制”. No duplicate
app launcher is added. A valid function removal or reset revokes the annotation;
V2 and Table2 function-looking bytes never grant it. This discovery-only typed feature
does not enable private ANC, an extra card, an optimizer progress state or wire actions.

The existing VendorHandoffLauncher/Coordinator checks the installed Sony app, requests
BeginVendorHandoff and waits for a newer same-address/same-token state acknowledging
Sony external ownership before launching. Failure/timeout cleans up the handoff lease.
The existing explicit return action resumes normal module ownership policy. No scope,
automatic-yield preference or app configuration is changed. An old annotated callback
rechecks the current optimizer feature; the ordinary generic app row remains available
when the annotation is absent. The user completes calibration inside Sony's app; this
entry does not claim a deep link directly to its calibration screen or report success.

Reference facts retained for audit: Gadgetbridge V1 exposes start/stop84 01 00 01/00,
status85 and parameter86/87/89. V2 implementations are absent; the source has no
proven device-running query, explicit failure enum or completion-time guarantee.
Accordingly HyperEars sends none of these optimizer commands. The native entry plus
acknowledged handoff implements access to the capability, not native wire calibration.
