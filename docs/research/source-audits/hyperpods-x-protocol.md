# HyperPods-X protocol audit

## Source and scope

Source: <https://github.com/NEORUAA/HyperPods-X>; audited HEAD `018765be94f24a49bf28be26fbb5924a3652018f`, authored2026-10-28T04:21:57+08:00, title Add settings screenshots and prepare the fork preview release. Checkout `.analysis/upstreams/HyperPods-X`. Read-only review; no builds/device operations/remote changes. Hardware results below are upstream documented reports, not independently reproduced HyperEars validation.

License GPL-3.0-or-later (LICENSE and SPDX headers). THIRD_PARTY.md credits LibrePods2025 contributors for AACP/BLE cryptography/socket facts, HyperPods (Mo-SeTian/aap link, not necessarily identical to Art-Chen default), RTBuddy/DEVMOTION references Untrosss/HeadTracker, FIocker/aap-head-tracker, apple-wireshark and Apple XNU serialization. Keep copied/adapted source attribution; GPLv3-or-later permits choosing GPLv3 consistent with HyperEars, but preserve upstream notices. SF Pro/SF Symbols and Apple product photographs are explicitly excluded from source grant; do not import them. No Glasmio UI dependency needed for protocol reuse.

## What A3441 support actually means

- docs/DEVICE-BASELINE.md:16 identifies AACP model A3441 as AirPods5 wireless-charging-case version. TEST-REPORT.md:16-26 records actual L2CAP/device information/battery and later sections record tests. This is stronger than a model-registry declaration alone.
- It is principally a single tested-device implementation: Xiaomi17ProMax, HyperOS4.0.4.0/4.0.5.0.XPBCNXM, A3441. There is no full Apple/Beats model registry equivalent to AndPods. Do not infer additional AirPods/Beats support from generic protocol code.
- Protocol information parsing accepts an A#### field; general advanced controls are allowed only after corresponding control reports, rather than statically enabled by model. Native panel eligibility is explicitly A3441-gated in hook/.../NativeControlHost.java:108. That restriction must not become HyperEars native-ownership policy.
- User wants AndPods primary, HyperPods supplement. HyperPods-X provides corroborating implementation and unusually detailed A3441 evidence, not authority to replace AndPods complete device matrix or Xiaomi-first ownership.

All paths below relative to repository root; abbreviated core = core/protocol/src/main/kotlin/com/hyper/podsrx/protocol, app = app/src/main/java/com/hyper/podsrx.

## Concrete protocol and control implementation

- `core/Aacp.kt:29-50`: BR/EDR L2CAP PSM0x1001; AACP1.3 handshake (byte6=03 vs HyperEars/original HyperPods02); source feature flags D7 (vs earlier FF); notifications; key types1/4; generic settings0x09; rename; custom EQ0x63.
- `core/Aacp.kt:52-117`: batteries, control reports, strict8-byte wear, device info, bounded key response, EQ, conversation, audio-source and RTBuddy motion parsing. `AacpFramer:135+` reassembles known fixed/length frames, bounded pending65536 and frame4096; unknown frames rely on L2CAP SDU/next header. Keep HyperEars stricter existing battery record validation rather than wholesale replace decoder: this battery parser accepts status3 and skips unknown component IDs, lacking existing marker checks.
- `app/device/PodsService.kt:249-260`: actual handshake/features/notifications/key calls in connection loop with200ms spacing. These are called code, not constants only. Keys saved encrypted through SecureKeys; IRK/ENC enable later authenticated BLE association.
- `app/device/Controls.kt:10-26`: controls listening0x0d; one-bud ANC1b; CA28; personalized volume26; adaptive2e; allow-off34; firmware wear0a; mic01; click17/hold18; swipe25/interval23; chime1f; sleep35. Values are validated and app enqueue requires report existence (PodsService:185-206). Call configuration0x24 handled separately, preserving other bytes. Registered UI option alone is not hardware evidence.
- `Aacp.equalizer` sends custom EQ0x63 state+three bands; parser requires bounded0..100 bands; PodsService:325 confirms exact returned values. TEST-REPORT:118,133-134 reports changes to low band54->55->54 via reconnect/readback. Sound quality/EQ response curve not independently measured. This is3-band device EQ, not arbitrary Android system equalizer.

## Adaptive strength: keep sending, acknowledgement and effect separate

Current HEAD intentionally changed earlier three-anchor implementation. Controls.kt range0..100; PodsService.kt:124-145 coalesces obsolete target commands, sends one-byte value zero-padded, clears pending SEND after socket flush, keeps adaptiveTarget separate from confirmed controls. It does not force a reconnect to get matching adaptive reply. Leaving Adaptive or disconnect clears target. Other settings still request reconnect after1.2s without reply and time out after12s (PodsService:149-169).

Historical A3441 tests confirmed0/50/100;40/60 and51..62 lacked matching replies and reconnect still read50. Current merged physical UI report sent37/71/final50 and drag emitted seven writes; confirmed strength stayed50 while sent target differed. Thus actual continuous dispatch exists and upstream documents UI acceptance, but101 discrete hardware states, intermediate acoustic effect and persistence are NOT proven. Do not advertise continuous confirmed-device readback. Use HyperEars typed requested/sent/confirmed state separation; don't import application-specific reconnect-every-setting behavior into shared session runtime.

## Wear, gestures and media policy

`core/EarPlaybackPolicy.kt` and `app/device/EarDetectionController.kt` implement two-buds->one pause, put-back resume only for app-owned pause, single-ear usage, route match and call-state eligibility. Wear byte order is primary/secondary, NOT fixed left/right (`Aacp.kt:12-15,70-75`); state0=in-ear,1=out,2=case,3=disconnected. Original HyperPods left/right labeling is less precise.

TEST-REPORT:185-195 distinguishes code/test pass from later user acceptance. Controller adds a short polling check atEarDetectionController:133-137; do not transplant hidden polling into WireCodec/Session. Implement any media automation as explicit opt-in host policy with documented bounded lifecycle, keeping wear telemetry separate.

Press speed/hold/swipe settings have actual write/readback and restore records (TEST-REPORT:119-128). Mic side, device-side wear config, left/right hold actions and listening-cycle mask were NOT reported by this A3441 (line140), so no fabricated defaults or blind writes. Head tracking is implemented; nod/shake call-control gesture recognition is not established merely by motion data.

Swipe volume eventually works by user report AFTER stopping module, placing buds in case~60s and reconnecting; still worked after module restart and headtracking. Earlier logs showed switch affected earbud feedback but no inbound volume command. Root cause remains unknown; no new gesture-mapping fix was added. Preserve this distinction in compatibility claims.

Chime volume0x1f has A3441 boundary evidence:14 rejected/15 accepted; various intermediate values accepted and restored, with user audible acceptance. Upper100 is range/test coverage, not hardware maximum measurement. Preserve remaining three config bytes; don't generalize15 minimum across all models absent evidence.

## Conversation awareness

- AACP0x4b length-bearing framing and subtype/status retained (`core/Aacp.kt:101-130`); current controller only acts on subtype1 (`ConversationController.kt:147-166`). Policy starts on1/2/3, ends6/7/8/9, ignores unknown4; duplicates do not extend absolute60s lease (`ConversationPolicy.kt:15-50`). Cancellation/connection generation protect callbacks.
- Actual media ducking implemented through scoped VolumeShaper in existing Xiaomi Bluetooth host (`hook/.../ConversationAudioHost.java`), gain0.25/200ms down/300ms restore; keeps multi-app audio setting and media-volume index unchanged. Not just a CA toggle UI or unused setter as original HyperPods.
- TEST-REPORT:218-230 documents user-confirmed foreground attenuation/restoration and event-to-submit5–24ms start/3–6ms end. Those are IPC/runtime latency, NOT speech-detection/acoustic latency. Quiet-speech sensitivity remains unresolved; no verified sensitivity command found. Background/lockscreen/call/player/death/continuous-headtracking combinations remain pending.
- Transplant packet/policy facts first. Audio automation needs separate host lifecycle/cleanup design and acceptance; don't equate receiving CA reports with working ducking.

## BLE identity and lid events

`core/Proximity.kt:11-65`: manufacturer0x004c may contain multiple length-prefixed Continuity messages; extract type07; RPA IRK validation with AES and proper byte order; decrypt final16bytes using ENC for precise battery; otherwise nibble battery marked approximate; bit5 swaps primary battery order; bit3byte8 clear means lid open. `LidSession:70+` uses capture timestamp, rejects stale/out-of-order (>15s), settles900ms and cooldown3s; silence does not invent lid-close.

Actual use `PodsService.kt:395-435`: only exact selected Bluetooth address or verified IRK RPA processes battery/lid. AES decode alone/model ID is not identity authority. TEST-REPORT:37-55 contains background IRK-verified lid-open user confirmation and documents early failure corrected by scan settings. Key bootstrap first requires AACP control connection; therefore this does NOT by itself solve first-ever unknown-device identity before Xiaomi-native ownership decision. Reuse only within connection/session and ownership architecture; no scanning all nearby Apple devices as module-owned.

## Motion and system spatial audio

`core/Motion.kt:28-57` dynamically requests RTBuddy service descriptors, bounded protobuf/IO serialization, subscription service ID and interval; decode quaternion/error acknowledgements. `app/device/SpatialAudio.kt:263-313,341-356` discovers devmotion6 and subscribes actual discovered ID; only fallback tries16/14/6. Target later reports service15, demonstrating why IDs must not be guessed solely from model. It requests current AACP ownership0x06 only in explicit tracking workflow, restores on cleanup. This protocol ownership command must never bypass Xiaomi-first module ownership.

TEST-REPORT:84-91 describes6583 real poses, subjective dynamic audio and sensor cleanup on both-bud removal; 0.4.5 records off/fixed/tracking transition and cancellation cleanup, no new listening trial. SpatialHost bridge uses ROM system renderer and real virtual sensor, NOT Apple's personalized spatial profile. Long-term drift/power/precision/interruption coverage remains incomplete. Reuse bounded discovery/decoding/calibration math as optional feature; dedicated host integration is a separate substantial change, not a codec-only checkbox.

## Comparison and minimal transplant order

AndPods4.3.8 remains primary local source (`decompiled/sources/cn/xiaolongonly/andpodsop/util/liberated/AacpProtocol.java:283-426`: CA, handshake, listen-cycle, long-press, headtracking, stem packets; model-inventory.json includes AIRPODS5_WIRELESS). HyperPods-X corroborates A3441 real behavior, strict primary/secondary wear interpretation, confirmed-vs-target control semantics and robust BLE identity/lid ownership. This audit did not re-prove every AndPods control; don't describe HyperPods-X full feature list as all new relative to AndPods.

Current HyperEars Apple codec/session remains battery+three-state noise, adaptive collapsed to ANC and Apple globally reserved; original Art-Chen HyperPods audit used9796d947daa18a379948349442632510424a1a15 and has many uncalled advanced setters, noA3441 specific proof. Thus useful staged targets:

1. Preserve Xiaomi-first fresh-connection ownership from apple-dynamic-native-ownership.md before registering/opening Apple transport.
2. Add vendor typed feature/control states for four listening modes, confirmed generic settings, model/firmware, wear primary/secondary, EQ; source legal domains and per-model exceptions from AndPods and X's real reports. Do not expand public NoiseMode with model-only fields.
3. Port bounded codec facts/tests for0x4b, keys and control response; preserve existing robust battery parser; choose handshake/profile by confirmed interoperability evidence, not blanket protocol version rewrite.
4. Add connection-scoped IRK/ENC association, freshness and lid policy if open-case popup scope is approved. Native UI gets typed projections, not app state maps or raw broadcasts. Secure keys remain local/redacted.
5. Treat optional wear automation, CA VolumeShaper and real headtracking as separate independently gated host capabilities with lifecycle tests. Headtracking/VolumeShaper cannot be represented as completed just because source exists.

Native panel uses A3441 restrictions and native template identity; more-settings opens its own app. That is not a complete reusable native Settings page and must not override officially supported Xiaomi Apple devices. App-specific Compose/Glasmio/UI, persistent foreground service, standalone connection manager and hardcoded model gate should not be transplanted into HyperEars architecture.

## Evidence risks to retain

- Current HEAD docs intentionally include historic and upstream-before-merge test counts/hashes. Those do not certify every current merge source file on device. Final source audit is not our runtime validation.
- There is no broad Beats or all-AirPods support proof here.
- AACP control appearance is not proof of every legal value; UI write sent is not matching readback; matching readback is not actual gesture/hearing/sleep result.
- Keep original source hashes, SPDX and attribution; no SF font, Apple photo, decompiled APK or packet capture bundled.

## Independent protocol test execution

After the read-only review, the parent agent ran the unchanged upstream `core/protocol`
production and test sources in an isolated ignored JVM harness. All 67 tests passed, with zero
failures, errors or skipped tests. Environment: Kotlin 2.4.21 (upstream version), target JVM 21,
local JDK 24 and Gradle 9.6. The harness points directly to the pinned source checkout, whose
working tree remains unchanged; it does not copy or alter the test logic.

This verifies the portable protocol test suite only. It is not a reproduction of the complete
Glasmio/SF-font Android build, an APK signature/installation check, or independent headphone,
MiLink, acoustic or head-tracking device validation. Harness and XML results remain under
`.analysis/verification/hyperpods-x-protocol/` in the original checkout.
