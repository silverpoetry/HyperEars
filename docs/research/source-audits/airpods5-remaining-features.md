# AirPods 5 wireless remaining features and implementation contracts

## Implementation decision after the audit

The existing reported wearing-detection and conversation-awareness switches also enable their
corresponding HyperEars host behavior. This follows the requested integrated feature experience;
there is no second host-only toggle. The details panel explains pause/owned resume and conversation
attenuation beside those controls. Missing or false configuration, lost module ownership, native
system admission, private-channel loss and module pause disable the behavior. Enabling a switch
still waits for the device's report; it does not assert that media manipulation succeeded.

Wear and conversation host paths are now implemented and undergoing combined verification.
Long-press, listening-cycle and device EQ controls are also wired through existing MiLink details.
BLE key/bootstrap and case events, host gesture interception and spatial audio remain outstanding.
The original source audit below describes research findings, not the current implementation status.

Audit date: 2026-10-28. Source inspection and specification only; no device,
build, adapter or host changes in this audit. This is the remaining-work ledger
after device information, four listening modes, fourteen settings and native
card/details integration. Those settings do not implement host media automation.

## Evidence roots and current boundary

- `A`: original checkout `.analysis/andpods-4.3.8/decompiled/sources/`
  `cn/xiaolongonly/andpodsop/`; primary application protocol source.
- `X`: original checkout `.analysis/upstreams/HyperPods-X/`, pinned
  `018765be94f24a49bf28be26fbb5924a3652018f`; supplementary implementation and
  upstream A3441 reports, not HyperEars device validation.
- `XC`: `X/core/protocol/src/main/kotlin/com/hyper/podsrx/protocol/`.
- `XA`: `X/app/src/main/java/com/hyper/podsrx/`.
- `XH`: `X/hook/src/main/java/com/hyper/podsrx/hook/`.
- LibrePods and original HyperPods corroboration is recorded in the linked
  [settings audit](apple-settings-followup.md) and
  [HyperPods-X audit](hyperpods-x-protocol.md). Original HyperPods does not
  establish A3441 support or a working conversation host by itself.

AirPods 5 wireless candidate identifiers are A3439/A3440/A3441. X's concrete
documented target is A3441. A model identifies a candidate, not every feature:
each capability below requires current-session evidence and the existing
Xiaomi-first native admission. No firmware minimum was established. Pro hearing
protection, hearing assistance, heart-rate and PPE features are not inferred for
this target. No proprietary source, keys or product assets are reproduced here.

Common frame prefix `P` below means `04 00 04 00`; all multibyte lengths/opcodes
are little-endian. Strict full-opcode validation must precede dispatch. Received
state, requested state and successful host operation are separate evidence.

## Remaining feature matrix

Each numbered entry specifies protocol, called source, eligibility, confirmation,
host responsibility and cleanup. Current status for all entries is **remaining**;
the existing configuration switches mentioned below are already implemented.

### 1. Actual wear state and automatic media pause/resume

- Report: `P 06 00 primary secondary`, exactly eight bytes. Supplementary domain
  is 0=in-ear, 1=out-of-ear, 2=in-case, 3=disconnected. Preserve primary/secondary
  identity; these are not fixed left/right channels. Reject unknown values rather
  than treating them as removal. Initial valid telemetry enables telemetry only.
- Primary called path: `A/util/liberated/AacpProtocol.java:203-207,377-378`
  decodes offsets 6/7 with zero=in-ear; `AirPodsDemoController.java:1078-1096`
  handles `EarChanged`; `AacpSessionManager.java:268` dispatches ear callbacks.
  The richer four-value domain and strict shape are from `XC/Aacp.kt:70-75` and
  `XC/EarPlaybackPolicy.kt`, not invented left/right interpretation.
- Existing setting `0x09/0x0A` is device-side detection configuration; it does
  not establish current presence or host playback ownership. Separate typed
  `apple.wear` evidence and host automation preference are needed.
- Host reference: `XA/device/EarDetectionController.kt:37-51,78-148` actually
  registers playback/device callbacks, checks selected media route and call mode,
  consumes reports and dispatches media actions. A connected output alone does
  not prove the active route. Do not transplant its recurring short poll.
- Implement a bounded host policy: initial report never auto-plays; a decrease
  from two to one or one to zero pauses only currently playing eligible media.
  Resume requires an issued and subsequently observed pause owned by this
  session, reinsertion, the same eligible player/route and no call. Reference
  bounds are 1.5 seconds for pause acknowledgement and five minutes for resume.
  Bind ownership to the actual player token as well as the device session, so a
  different app is never resumed by an old pause. Clear on user/player restart,
  timeout, route/call/ownership loss, disconnect, host teardown and preference off.
- Completion evidence: valid state transitions plus actual pause/owned resume;
  initial wear, duplicate reports, single-bud use and user-paused playback must
  produce no unsolicited play. Implement callback-driven checks and one-shot
  deadlines outside WireCodec/ProtocolSession.

### 2. Conversation activity and actual audio attenuation/restoration

- Known report: `P 4B 00 02 00 subtype status`, exactly ten bytes. Length at
  offsets 6/7 frames the whole payload (`8 + length`); retain subtype and status.
  X bounds unknown payloads to 1..64 bytes. Unknown variants are opaque and do
  not run host actions; a short frame does not confirm conversation state.
- Primary: `A/util/liberated/AacpProtocol.java:43-60,397-403` validates the known
  frame; `AirPodsDemoController.java:1098-1103` invokes the activity listener;
  `AacpSessionManager.java:272-279` dispatches it. Primary
  `ConversationAwarenessEventType.java:7-12` maps start 1/2 and stop 6/8/9.
  X adds start 3 and stop 7 based on its LibrePods reference; preserve this
  provenance rather than attributing those two values to AndPods.
- `XC/Aacp.kt:101-130`, `XC/ConversationPolicy.kt` and
  `XA/device/ConversationController.kt:147-166` preserve/validate subtype; only
  subtype 1 drives the known policy. Start 1/2/3; release 6/7/8/9; unknown 4
  is ignored. Duplicate active reports never renew the absolute 60-second lease.
- Existing `0x09/0x28` enables device conversation detection. Actual host ducking
  additionally requires activity evidence, active matching route, normal audio
  mode, host capability and opted-in automation. Do not label the toggle as a
  completed audio feature.
- Called host path: `XA/device/ConversationController.kt` to
  `XH/ConversationAudioHost.java`; scoped VolumeShaper gain 0.25, 200 ms down,
  300 ms restoration. This preserves stream volume index and multi-app setting.
  Reuse the existing Bluetooth host boundary with semantic capability checks,
  not X's standalone application service or JSON bridge.
- Track the exact player/shaper handles modified. Release on end, fixed deadline,
  disabled setting/preference, route/call/player loss, disconnect, producer/session
  replacement, host destruction or failed operation. Late callbacks cannot
  reactivate an old lease. Host cleanup must restore only owned shaping, not
  overwrite user volume. A host-local deadline must survive controller failure.
- X reports foreground user acceptance, not universal background/call behavior.
  Completion requires audible down/restore and interrupted cleanup on the final
  HyperEars implementation, beyond report decoding and successful IPC.

### 3. Stem events, long-press mapping and listening-cycle membership

- Primary stem report: `P 19 00 press bud`. Parser minimum is eight bytes;
  implement the known eight-byte variant without inventing future trailing data.
  `A/util/liberated/StemPressType.java:7-11`: 5 single, 6 double, 7 triple,
  8 long; `StemPressBudType.java:7-9`: 1 left, 2 right; other values unknown.
  `AacpProtocol.java:387-388` -> `AirPodsDemoController.java:1146-1165` ->
  registered listeners is an active receive path.
- Long press configuration: generic eleven-byte control `P 09 00 16 R L U V`;
  `LongPressAction.java:7-9`: 1 noise control, 5 digital assistant. Right precedes
  left; preserve unowned U/V and the other bud on a one-side change. Encoder
  `AacpProtocol.java:316-319`; active sender
  `AirPodsDemoController.java:711` and report handler `:998-1020`.
- Cycle configuration: `P 09 00 1A mask U V W`; bits 1 off, 2 ANC,
  4 transparency, 8 adaptive (`A/util/AacpListeningModeSupport.java:7-13`).
  Called sender `AirPodsDemoController.java:732,1968`; report handler `:1021-1029`.
  Preserve unknown/unowned bytes; legal nonzero subset must intersect the actual
  session's listening modes. Do not silently add unsupported adaptive/off modes.
- Stem interception config is generic identifier `0x39`, low nibble composed
  from four event flags (`AacpProtocol.java:422-424`). The primary active
  long-press path writes 0x08 when intercepting long press and 0x00 when returning
  device handling (`AirPodsDemoController.java:598,702`; defaults at
  `AacpProtocol.java:225-238`). This is not passive event observation.
- Active application path: `A/util/LongPressSettingsManager.java:114,201` ->
  `AacpSessionManager.configureLongPress:599-605` -> controller.
  `A/util/liberated/LongPressFeatureManager.java:236-252,411-419` consumes long
  presses for configured app-side actions; this proves a caller beyond constants.
- A3441 X reports did **not** contain long-press or cycle controls. Implement
  typed contracts but expose writes only after respective valid reports; neither
  model ID nor ordinary stem media controls prove configuration support. Keep
  firmware gesture handling as default; do not double-dispatch AVRCP actions.
  Host interception needs an explicit ownership lease, successful observed
  configuration and restoration of the prior value on release where possible.
  Unconfirmed interception/restore must remain a visible incomplete state.
- Physical press -> event -> intended host/device action is separate acceptance
  from configuration readback. Swipe settings already exist, but receiving no
  volume action cannot be fixed by claiming their toggle completed gestures.
  Nod/shake call gestures are not established by motion packets alone.

### 4. Three-band device equalizer

- Supplementary active protocol: `XC/Aacp.kt:46-50,98-100` writes
  `P 63 00 05 00 01 state low mid high` (13 bytes). State domain 1..2;
  each band unsigned 0..100. Preserve neutral enum labels until on/off semantics
  are independently verified; do not infer signed dB, frequency or Q.
- `XA/device/PodsService.kt:325` matches all returned values to pending target.
  X documents A3441 low-band 54 -> 55 -> 54 readback, not a measured response
  curve. AndPods' opcode name `0x53`="eq" is not an active `0x63` implementation
  and must not be presented as the source for this contract.
- Require current valid 13-byte EQ report including known prefix/subtype and
  domains before publishing capability. Implement typed state/request, one
  bounded pending target, notification refresh and matching device confirmation.
  No arbitrary Android system EQ effect. Timeout preserves confirmed values;
  disconnect clears pending/session evidence. Acoustic effect remains acceptance.

### 5. Key bootstrap, identity-verified BLE battery and case-open popup

- Supplementary called bootstrap: `XA/device/PodsService.kt:249-260` sends
  notifications/features and `XC/Aacp.kt:35` key requests
  `P 30 00 type 00`, type 1 or 4. Response opcode `0x31` has count at offset 6,
  followed by `typeLE16 lengthLE16 bytes`; bounds count <=8, record length <=256,
  accepted type 1/4 lengths exactly 16 (`XC/Aacp.kt:83-97`). Require complete
  framing, reject truncation/ambiguous duplicates/trailing junk, and never log
  payload/key material. No new public key-bearing feature state.
- Type 1 IRK associates rotating addresses; type 4 ENC decodes precision battery.
  Bind keys to the admitted paired device and protect persisted keys with local
  platform storage. Revoke on unpair; reject stale generation bootstrap results.
  Existing ownership must authorize the AACP session before any bootstrap.
- `XC/Proximity.kt:11-65` parses manufacturer 0x004C length-delimited Continuity
  records and type 0x07. Verify identity before accepting battery/lid: exact
  selected device address or IRK-resolved RPA; decryption or model match alone is
  insufficient. Actual identity gate is `XA/device/PodsService.kt:395-435`.
- RPA matching uses Bluetooth `ah` with explicit byte order; battery decrypts
  final 16 bytes with ENC. Precise byte low seven bits is 0..100, high bit
  charging, 0xFF unavailable. Approximate nibble fallback stays explicitly
  approximate; bit 5 in message byte 5 changes primary battery ordering. Lid
  open is byte 8 bit 3 clear. Keep source quality separate from AACP battery.
- `XC/Proximity.kt:LidSession` uses capture timestamps, rejects old/out-of-order
  samples (15 seconds), settles 900 ms and debounces popup 3 seconds. Silence is
  not a close event. Process the complete batch before emitting the open event.
  Deliver through current native popup/card ownership, never raw UI broadcasts.
- Background scanning must reuse host lifecycle and bounded registration, stop
  on ownership loss/unpair/host teardown, and avoid permanent all-device scanning.
  A case-open event while disconnected requires previously admitted authenticated
  identity and a fresh native display-ownership decision. Bootstrap cannot solve
  first-ever unknown identity or override Xiaomi-first admission. This integration
  prerequisite is real remaining work, not a reason to claim popup finished.

### 6. RTBuddy motion discovery and native Android spatial rendering

- Primary `AacpProtocol.java:418-426` and active
  `AirPodsDemoController.java:1665` establish start/stop opcode 0x17; fixed
  packet/marker variants are not universal sensor service IDs.
- Supplementary `XC/Motion.kt:28-78`: envelope `P 17 00`, four-byte descriptor,
  body length LE16 at offsets 10/11, bounded body 1..4000. Descriptor must be a
  valid single-bit value. Bounded protobuf and IO-serialization parsing discovers
  service metadata; request field 4, response service descriptors field 5.
- `XA/device/SpatialAudio.kt:263-313,349-355` actually performs setup, descriptor
  request, searches `AccessoryService=devmotion6`, subscribes discovered ID,
  validates acknowledgements and receives poses. Valid service IDs 1..255;
  supported intervals 20,000/40,000 microseconds, 0 unsubscribes. X's target
  discovered ID 15: never assume 16/14/6 from a model. Prefer discovery failure
  over blindly trying services in shared production transport.
- AACP generic control identifier 0x06 is ownership, distinct from wear opcode
  0x0006. X temporarily requests ownership in explicit tracking workflow and
  restores the observed prior value. This does not authorize bypassing HyperEars
  native admission; preserve/restore only owned, confirmed state.
- Host `XH/SpatialHost.java:47,88-168` uses Android Spatializer and ROM
  `com.android.bluetooth.ble.app.headset.Spatialcontroller`, calls
  `createspatialhid`, `sendspatialdata`, `destroyspatialhid`, and enables/restores
  the head tracker. These names describe the audited ROM implementation; discover
  exact compatible signatures/capability transactionally, not by OS/model string.
- Runtime gates: valid discovered motion service and acknowledgements/finite
  quaternion samples, current matching route, normal audio mode, verified host
  renderer and virtual-sensor capability, explicit off/fixed/tracking choice.
  Calibration/orientation conversion and real pose delivery are required; merely
  showing a switch or receiving bytes does not provide spatial audio.
- Bound discovery/subscription attempts and parser resources. X stops stalled
  stream after 2.5 seconds and host stale poses after 800 ms. Cancel on removal,
  route/call/disconnect, ownership/session change, host failure and disabled mode;
  unsubscribe, destroy only owned sensor, restore prior tracker configuration and
  ignore late callbacks. No tracking at boot or permanent diagnostic subscription.
- Native Android renderer output is not Apple's personalized spatial profile.
  Actual spatial movement, recentering, drift and power need final acceptance;
  nod/shake actions remain separate unproven features.

## Implementation order without an architecture replacement

1. Add wear/conversation typed observations and strict framing to the existing
   Apple codec/session. Keep raw parser code free of Context, media calls and
   scheduling. Test split/concatenated frames, invalid domains and stale reset.
2. Add pure bounded wear/conversation policies and explicit host operation state.
   Extend the existing adapter/effect boundary only for concrete operations;
   current `AdapterEffect` provides state requests/cancellation, not audio side
   effects. The Android executor owns callbacks/deadlines, not model policy.
   Existing Bluetooth/MiLink host entrypoints are the integration sites; no
   parallel Apple service, connection manager or new application architecture.
3. Add long-press/cycle and EQ typed requests through `ControlRequest` contracts,
   confirmed feature state and current native details. No default capabilities,
   optimistic readbacks or unbounded reconnect-to-confirm loops.
4. Add secure session bootstrap and authenticated BLE association, then lid policy
   and the native popup projection with correct disconnected ownership handling.
5. Add motion codec/discovery, explicit spatial controls and native host resources
   as one complete workflow; validate teardown as strongly as startup.

These are implementation slices, not permission or installation checkpoints.
Complete the requested feature work before the final device installation and
acceptance stage. Preserve full other-brand scope separately; Coral and this
AirPods target remain the user's priority. Unit/build evidence and upstream
reports do not replace final changed-protocol/lifecycle/card device revalidation.
