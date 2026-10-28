# Apple settings follow-up source audit

Read-only audit dated 2026-10-28. This is evidence for the next typed-control slice,
not a claim of device support or complete Apple integration. No device, build,
adapter, or test changes were made.

## Source priority and local reference roots

1. `A` = `D:/Backup/Desktop/HyperEars/.analysis/andpods-4.3.8/decompiled/sources/cn/xiaolongonly/andpodsop/`.
   Primary application behavior; descriptions below are independently summarized,
   with no copied implementation.
2. `H` = `D:/Backup/Desktop/HyperEars/.analysis/upstreams/HyperPods-X/`,
   checked HEAD `018765b` (requested pinned `018765be`).
3. `L` = `D:/Backup/Desktop/HyperEars/.analysis/upstreams/LibrePods/`,
   checked HEAD `89356c0`. Corroboration and explicitly labeled gap filling.

Paths below are relative to those roots. Local source snapshots, not live-device
captures, are the evidence. No unique device identifiers or captured private data
are included.

## Frame contract and observation limits

`A/util/liberated/AacpProtocol.java:28-36,125-136,367-381` constructs opcode
`0x0009` using the four-byte AACP prefix, two-byte little-endian opcode, one-byte
identifier at offset 6, and exactly four data bytes at offsets 7..10. Total length
is 11 bytes. The standalone parser accepts length >=11 and retains only the first
four value bytes. `A/util/liberated/AacpFrameDecoder.java:43-51` frames this opcode
as exactly 11 bytes. Therefore exact 11-byte validation belongs after framing;
short reports must not confirm state and concatenated frames must first be split.

Treat identifier and data bytes as unsigned. No signed numeric field is established
for the controls below. Preserve all four report bytes even when a typed control
uses only one: a single-byte UI abstraction loses real multi-byte semantics.

The primary application has an active encoder, manager call and report handler for
conversation awareness. Its generic report handler handles `0x16`, `0x1A`, and
`0x28`; other identifiers are only logged there
(`A/util/liberated/AirPodsDemoController.java:994-1052,1168-1170`). A human-readable
name for `0x1B` or `0x2E` in `AacpProtocol.java:248-272` does not establish an active
setting. The following additional settings rely on corroborating upstream code.

## Controls suitable for a typed follow-up

- **Microphone, `0x01`:** byte 0 is enum 0=automatic, 1=right, 2=left.
  Active `H/app/src/main/java/com/hyper/podsrx/device/Controls.kt:18` definition is
  wired by `H/.../ui/IosSettingsPages.kt:173-179` into `PodsService.setControl`.
  Independent active LibrePods UI: `L/android/app/src/main/java/me/kavishdevar/librepods/presentation/screens/MicrophoneSettingsScreen.kt:42-54`.
- **One-bud ANC, `0x1B`:** byte 0 1=enabled, 2=disabled, not 0/1.
  `H/.../device/Controls.kt:12`; active LibrePods callback
  `L/.../presentation/screens/AccessibilitySettingsScreen.kt:248-253`.
- **Conversation awareness, `0x28`:** byte 0 1=enabled, 2=disabled;
  unknown values remain unknown, not false. Primary encoder
  `A/util/liberated/AacpProtocol.java:283-286`, boolean decoder
  `A/util/liberated/AirPodsDemoController.java:970-978`, report handler
  `:1030-1050`, active manager call `A/util/ConversationAwarenessManager.java:260`.
  Configuration is separate from speaking/activity opcode `0x4B`
  (`A/util/liberated/AacpProtocol.java:397-403`). Enabling this setting alone is not
  proof that host audio ducking/restoration works.
- **Personalized volume, `0x26`:** byte 0 1=enabled, 2=disabled.
  `H/.../device/Controls.kt:14`; actual callback
  `L/.../presentation/screens/AirPodsSettingsScreen.kt:450-469`.
- **Adaptive strength, `0x2E`:** unsigned byte 0 range 0..100.
  `H/.../device/Controls.kt:15,26-39` validates the range, allows adjustment only
  when reported listening mode is adaptive (4), and deliberately sends one value
  with zero padding rather than preserving other bytes. LibrePods active UI
  `L/.../presentation/screens/AdaptiveStrengthScreen.kt:94-109` uses reversed
  visual coordinate `100 - wireValue`. Keep wire range and visual direction
  explicit; do not infer signed dB or label physical attenuation without evidence.
- **Automatic wearing detection configuration, `0x0A`:** byte 0 1=enabled,
  2=disabled (`H/.../device/Controls.kt:17`, active LibrePods setter
  `L/.../presentation/viewmodel/AirPodsViewModel.kt:712`). This is not the actual
  ear-presence report: primary opcode `0x06` uses offsets 6 and 7, with zero
  interpreted as in-ear (`A/util/liberated/AacpProtocol.java:204-207,377-378`).
- **Double-press interval `0x17`, hold interval `0x18`, swipe interval `0x23`:**
  byte 0 enum 0/1/2. HyperPods-X labels double-press default/slower/slowest,
  hold default/shorter/shortest, and swipe default/longer/longest
  (`H/.../device/Controls.kt:19-22`). LibrePods active accessibility callbacks
  are at `L/.../presentation/screens/AccessibilitySettingsScreen.kt:208-237,333-342`.
  LibrePods hold labels at `:146-149` say slower/slowest, so timing direction is
  not consistently documented. Prefer neutral levels until device revalidation
  establishes intended localized labels; do not invent milliseconds.
- **Volume swipe `0x25`:** byte 0 1=enabled, 2=disabled.
  `H/.../device/Controls.kt:21` and actual LibrePods callback
  `L/.../presentation/screens/AccessibilitySettingsScreen.kt:312-324`.

Here `H/...` abbreviates `H/app/src/main/java/com/hyper/podsrx/`, and `L/...`
abbreviates `L/android/app/src/main/java/me/kavishdevar/librepods/`.

## Two important exclusions from a scalar generic-control implementation

**Loud sound reduction is not AACP `0x1B`.** LibrePods implements it through ATT
handle `0x1B`, with one-byte 1=enabled, 0=disabled
(`L/.../bluetooth/ATTManager.kt:31`,
`L/.../presentation/screens/HearingProtectionScreen.kt:90-94`). Its active main
settings callback also uses ATT (`L/.../presentation/screens/AirPodsSettingsScreen.kt:479-483`).
AACP identifier `0x1B` instead means one-bud ANC and has 1/2 encoding. This feature
needs an independently evidenced ATT transport/readback slice; it must not be
advertised by adding a generic AACP toggle. No active AndPods implementation was
established in this audit.

**Call management `0x24` has two meaningful bytes.** LibrePods reference markdown
labels it single-byte, but its actual navigation callback writes `[0x00,0x02]`
for flipped mapping and `[0x00,0x03]` for default mapping
(`L/.../presentation/navigation/AppNavGraph.kt:236-249`). The screen reads byte 1
and treats 2 as flipped (`L/.../presentation/screens/CallControlScreen.kt:46-63`).
Default maps mute/unmute to one press and ending a call to two; flipped swaps
these. HyperPods-X correctly modifies byte 1 while preserving the rest of the
observed four-byte value (`H/.../device/PodsService.kt:49-52`) and only offers the
UI when byte 1 is in 2..3 (`H/.../ui/IosSettingsPages.kt:84,259-276`). Do not model
this as a Boolean in byte 0 or zero out unowned bytes. Unknown first-byte meaning
and other mappings remain unresolved. The same multi-byte issue also applies to
long-press `0x16` (right byte first, left byte second), already explicit in primary
`A/util/liberated/AacpProtocol.java:316-319`.

## Gating, requests, and confirmation

AndPods gates conversation awareness through a model method, with ordinals 4,
8/9/10 and 14 accepted (`A/entity/HeadsetEnum.java:450-462`; names map in
`:115` to AirPods 4 ANC, Pro 2, Pro 3, Pro 2 USB-C, Max 2). Its phone gate always
returns true (`:349-350`). This is an application allowlist, not protocol proof,
and establishes no firmware threshold. LibrePods also has model capability sets
(`L/.../data/AirPods.kt:35-48,173-228`). Do not promote either to a universal
firmware/model guarantee. No numeric firmware minimum was established here.

HyperPods-X's useful complementary behavior is report-driven exposure:
`H/.../ui/IosSettingsPages.kt:124-139` requires a received control;
`H/.../device/PodsService.kt:189-197` additionally requires connectivity,
known control, valid domain and adaptive-mode precondition for adaptive strength.
This approach fits existing HyperEars source-priority policy better than exposing
all controls merely because a model name matches.

Primary notification request is opcode `0x0F`, four `0xFF` bytes, total 10 bytes
(`A/util/liberated/AacpProtocol.java:322-327`), sent by the controller initialization/
refresh paths (`A/util/liberated/AirPodsDemoController.java:1499,1551,1569,1588`).
The examined source does not establish a separate per-control GET transaction or
transaction ID. A successful socket write is not an acknowledgement. The primary
conversation setter optimistically changes UI state after sending (`:1905-1919`),
and LibrePods setters notify local listeners before sending
(`L/.../bluetooth/AACPManager.kt:309-343`); neither behavior should be imported
into HyperEars' confirmed state.

HyperPods-X keeps desired and actual values separate and reports a timeout without
replacing actual values (`H/.../device/PodsService.kt:135-167,204-215,309-320`).
Its reconnect/readback machinery is application-specific; avoid transplanting that
lifecycle wholesale into the native service.

## Minimal implementation recommendation

1. Extend the existing Apple adapter/parser path, not a parallel Apple service.
   Define typed enums, on/off values, adaptive percentage and a separate call
   mapping type; retain all four raw report bytes internally. Unknown identifiers
   and invalid values remain unexposed and never become an implicit false/default.
2. Start with microphone, one-bud ANC, conversation awareness, personalized volume,
   wearing configuration, timing/swipe and adaptive strength. Each advertised
   control requires a valid report from the current device session. If call
   mapping is included, require the proven shape and change byte 1 only.
3. Preserve `DEVICE_REPORT` confirmation, source priority and native schema:
   the user target is pending intent; only a valid matching current-session device
   report changes confirmed state. Preserve the previous reported value on failure
   or timeout. Disconnect clears session support/pending correlation. A cached,
   inferred or host-generated value must not outrank a device report.
4. Use the existing serialized transport and bounded notification refresh; do not
   create reconnect loops, fabricated per-ID reads, polling, or optimistic reports.
   Adaptation for native cards/settings should consume these typed capabilities.
5. Subsequent implementation tests should cover exact framing, unknown/invalid
   domains, unsigned bytes, no-write confirmation, stale-session rejection,
   multi-byte preservation, and ATT/AACP namespace separation. Tests and builds
   would still leave device confirmation, UI workflow, timing labels and audio
   side effects awaiting actual revalidation.
