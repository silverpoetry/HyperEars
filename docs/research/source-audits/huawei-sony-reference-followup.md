# Huawei/Sony remaining protocol-reference follow-up

Audit date: 2026-10-28. Read-only source research; no production/device changes. All four source trees fetched; Gadgetbridge uses shallow blob-filtered sparse checkout limited to Sony device/service trees (cone checkout also brings parent-level files). Tests inspected, not executed. Relative paths below are under `.analysis/upstreams/` unless stated otherwise.

## Refreshed revisions and provenance

- OpenFreebuds HEAD `0b4f14c0b500d491f0fb4ea38f2984ec334e3649`, commit date 2026-10-24T15:13:58+07:00; local pin `035bf2a87cdda9e9c8e8e90c662b7fa61270c6ee` fetched and directly diffed. GPL v3 LICENSE:1-2. <https://github.com/melianmiko/OpenFreebuds/tree/0b4f14c0b500d491f0fb4ea38f2984ec334e3649>
- SonyHeadphonesClient HEAD `5620e8ed5deccb957338b54e371b215146080819`, 2025-07-19T22:55:46+03:00, EXACTLY the existing local pin. README:1-10 declares archival. MIT LICENSE:1-17; preserve named-author notice on adoption. <https://github.com/Plutoberth/SonyHeadphonesClient/tree/5620e8ed5deccb957338b54e371b215146080819>
- Gadgetbridge HEAD `86703c8006fc701a81505a28c48e399d1428e0c1`, 2026-10-27T19:59:30Z; local pin `15d45691c7eed5195fb7020dfefda49ff2be8a68` fetched and Sony trees directly diffed. AGPL-3.0-or-later is explicit in Sony source headers (e.g. SonyHeadphonesCapabilities.java:1-15), not ordinary GPL. Current HyperEars notice explicitly excludes copying this AGPL implementation; retain independent protocol-fact implementation unless project licensing treatment is deliberately reviewed. <https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/86703c8006fc701a81505a28c48e399d1428e0c1>
- Sony spec source HEAD `e38bf35c19e219ced93dd748ac6b48c2c4fe6ffd`, 2023-10-28T22:31:19+02:00; LICENSE:1-3 = CC0 1.0. Existing notice has no source commit pin. Live spec shows last update 2023-10-28 19:56:59. <https://github.com/ohm-app/sony-headphones-bluetooth-documentation/tree/e38bf35c19e219ced93dd748ac6b48c2c4fe6ffd> and <https://ohm-app.github.io/sony-headphones-bluetooth-documentation/Protocol/Specification/>

## OpenFreebuds: actual changes versus old unused features

Finite code registry (`OpenFreebuds/openfreebuds/driver/constants.py:4-25`), excluding debug and duplicate FreeBuds3 alias, has 20 marketed models:
HONOR Earbuds 2 / 2 SE / 2 Lite; HUAWEI FreeBuds 3, 4i, 5i, 6i, Pro, Pro 2, Pro 3, Pro 4, Pro 5, SE, SE 2, SE 4 ANC, Studio, FreeClip, FreeClip 2, FreeLace Pro, FreeLace Pro 2.
Pro4 and FreeClip reuse the Pro3 driver; those names are not independent protocol implementations. README omits Pro5 although executable registry includes it. Do not use README count as source count or infer uniform capability from shared driver.

NEW SINCE LOCAL PIN:

1. FreeBuds3 driver and two aliases added. `openfreebuds/driver/huawei/driver/per_model/buds_3.py:5-28` uses SPP channel 1, info, in-ear state, battery, double-tap and voice-language handlers. No ANC handler is declared here. This complements HuaweiPods' older HFP/ANC path; do not wrongly conclude FreeBuds3 has only one transport for all features.
2. Gesture codes are model-specific: FreeBuds3 next-track=4 and ANC-cycle=3; `handler/abstract/multi_tap.py:9-28,41-80` now accepts options_map, preserves unknown codes, deduplicates reported options and prefers device-advertised codes for writes. `test/test_buds3.py:27-58` provides explicit query/reply/write fixtures and assertions. Useful shared design lesson: typed gesture action does not equal globally fixed numeric opcode.
3. New Pro4 device report (`docs/devices/HUAWEI_FreeBuds_Pro_4.md:3-26`) reports tested battery, THREE-state ANC/levels, gestures, presets/custom EQ, dual connect and latency using existing Pro3 driver on Windows11/0.18.1. Dynamic ANC and language are untested; wearing does not imply Windows auto-pause; quality preference dropped Windows connection. This is evidence expansion, NOT a newly added Pro4 protocol adapter. It conflicts in coverage with HuaweiPods' conservative two-state/no-readback Pro4 declaration; preserve both evidence contexts and require HyperEars firmware/device validation.

OLD FUNCTIONALITY STILL UNUSED BY HYPEREARS:

- Pro3 and related handler profiles already had EQ presets/custom, sound quality, auto-pause, dual connect, wearing, voice language, double/long/swipe gestures and low latency (`per_model/buds_pro_3.py:12-33`), so this is backlog rather than upstream-new work. Source itself flags several handlers untested; broad driver inclusion is not hardware validation.
- Compared with HuaweiPods research, distinct useful model/profile references include 4i + three Honor aliases, original Pro/Pro2, SE/SE2, Studio and FreeLace Pro/Pro2. HyperEars family prefixes may already connect to some; missing exact profile/topology/controls is the precise gap. Studio explicitly sets `w_tws=False` (`per_model/buds_studio.py:8-14`), so preserve aggregate battery and headphone topology.
- Voice-language configuration is an additional capability absent from the first HuaweiPods summary. It is vendor setting functionality; no evidence here of a native HyperOS widget mapping.
- Desktop HTTP server, hotkeys and tray battery additions are host-product features, not Android headset protocol/native-card upgrades. Keep separate from mobile capability consolidation.

## SonyHeadphonesClient: no new commits, still useful old legacy capabilities

README:70-78 finite reported models: WH-1000XM3 (working), WH-1000XM4 (partial), MDR-XB950BT (working). README explicitly warns newer-model forks were not reviewed. No device-profile registry that establishes all other Sony models.

`Client/Headphones.h:19-34`, `Client/Headphones.cpp:80-131`, and CommandSerializer implement ambient level/voice focus plus old Virtual Sound VPT and sound position. HyperEars Sony minimal subset has no VPT/sound-position controls. These are useful legacy additions alongside SonyPods modern EQ/DSEE/LEA work. README:66-71 explicitly lists battery/state fetching and EQ as unfinished; do not count those as upstream capability. `Property::fulfill` copies desired to current after send, so its state model is not authoritative readback and must not replace HyperEars device-confirmation architecture.

No HyperOS/native settings integration: this is a desktop client. Use wire facts/permissively licensed serializer with attribution, not its UI or optimistic control state.

## Gadgetbridge Sony: refreshed lifecycle fix, larger existing capability backlog

Finite coordinators remain 19; no new coordinator filename since local pin:
LinkBuds, LinkBuds S; WF-1000XM3/XM4/XM5, WF-C500/C510/C700N/C710N, WF-SP800N; WH-1000XM2/XM3/XM4/XM5/XM6, WH-CH720N, WH-ULT900N (ULT WEAR); WI-C100, WI-SP600N.
Directory: `Gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/coordinators/`.
HyperEars already has these profiles plus WF-1000XM6; this audit does not find a new Sony model to add from current Gadgetbridge.

NEW SINCE LOCAL PIN (direct two-tree diff):

- `service/devices/sony/headphones/SonyHeadphonesSupport.java:45-49,110-116,202-208`: explicit main-thread Looper and RESET `initRetries` on each initialization. Important lifecycle regression candidate for reconnect, but HyperEars has different session ownership and evidence-specific XM4 retry policy; adopt invariant/test, not arbitrary global retry count.
- V1/V2 codec display preferences now lowercase with Locale.ROOT (`protocol/impl/v1/SonyProtocolImplV1.java:1146-1150`, V2:1164-1168).
- New `devices/sony/headphones/SonyHeadphonesQuickControls.kt:16-89` surfaces ANC/ambient slider/EQ in Gadgetbridge's OWN app cards; preferences and coordinator settings migrated. These are not Xiaomi native MiLink/Settings components and not new headphone wire capabilities.

OLD MISSING CAPABILITIES (most overlap SonyPods, but legacy gaps deserve inventory):

`SonyHeadphonesCapabilities.java:19-56` plus executable V1 get/set methods include ANC optimizer, timed/wearing auto-off, wearing pause, wide-area tap, capture-voice-during-call, service link, assistant choice, voice notifications/volume, old surround mode and sound position, as well as EQ/LDAC/upscaling/dual connect/Speak-to-Chat/gestures.
Concrete encode/decode entry points in `service/.../protocol/impl/v1/SonyProtocolImplV1.java`: setAutomaticPowerOff:341; setWideAreaTap:355; setCaptureVoiceDuringCall:373; setPauseWhenTakenOff:469; setSoundPosition:537; setSurroundMode:560; setVoiceNotifications:608; setVoiceNotificationsVolume:627; setVoiceAssistant:638; capability-specific init queries:781-794.
These functions existed before the pin: current source comparison must label them unintegrated backlog, not recent upstream discoveries. Protocol availability remains model-capability-dependent. Native host controls for these are unproven; use confirmed semantic native components where available and a unified extension UI otherwise.

## Sony specification: old incomplete reference, not a new implementation

Source has only `docs/Protocol/Specification.md` and one explicit device page `docs/Devices/LinkBuds S.md` (plus homepage/scripts). Repository topics mentioning other models are not profiles.
The live/source spec discusses RFCOMM framing and marks fields unknown. LinkBuds S page:5-16 explicitly says data is not fully understood and distinguishes capture-level RFCOMM framing from payload. Page:20-42 gives ANC/ambient samples and tentative Speak-to-Chat examples. Do not splice capture-wrapper/unknown bytes into application SPP payloads; stronger SonyPods/Gadgetbridge typed codecs should be preferred as corroborating protocol facts. No newer capability than prior SonyPods findings and no native UI. CC0 status should be recorded when pinning this documentation source.

## Consolidation implications

1. Maintain three statuses: upstream-new since pin; upstream-old but not integrated; absent/unproven. This prevents an unchanged archived source being mistaken for no useful feature backlog.
2. Add FreeBuds3 SPP feature fixtures, per-model gesture maps, Studio aggregate topology, Pro4 conflicting evidence and old Sony virtual/optimizer/voice settings to the capability matrix.
3. Neither desktop OpenFreebuds/SonyHeadphonesClient nor Gadgetbridge own-app quick controls solves native HyperOS presentation. Prior HuaweiPods native ANC/spatial references and SonyPods native battery/ANC integration remain the useful host evidence, with advanced feature extension work still needed.
4. Keep licensing lanes distinct: MIT serializer reuse; GPL Huawei work with source attribution; CC0 spec facts; independently implement Gadgetbridge AGPL protocol facts under current project policy. Do not collapse all sources into one copy-and-merge operation.
5. Fixture tests, source tests, upstream device reports, final HyperEars on-device protocol readback and native host acceptance are separate gates. Changed protocol/lifecycle/card maintenance builds still require final-commit device revalidation and Draft/open PR discipline from CONTRIBUTING.md.
