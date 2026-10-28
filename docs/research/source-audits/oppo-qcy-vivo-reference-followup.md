# Additional OPPO / QCY / vivo protocol sources audit — 2026-10-28

Read-only inspection, clones obtained using per-command proxy <http://127.0.0.1:7890>. No production files/device actions. All findings are implementation/document evidence, not new hardware validation. Local CONTRIBUTING/AGENTS read; Leaf AGENTS read. Source paths below are relative to `.analysis/upstreams/`.

## Revisions, dates, licenses and baseline comparison

- Leaf-lsgtky/OppoPods: master HEAD `929d813f1b82d5b09dd39a84b4f18744e8ef7e64`, commit 2026-10-24T21:52:11+08:00, pushed2026-10-24T13:52:43Z. GPL-3.0 declared in README:108-110, no standalone recognized license. HyperEars notices do not pin a Leaf baseline, so changes are differences against current local implementation/1812z snapshot, not claimed post-pin chronology.
- SouthautumnYa/QCYpods: main HEAD `7bfbd951ad3fb803be84864a2a8493c78695bd61`, commit2026-06-16T09:21:11+08:00, pushed2026-06-16T01:23:02Z. This is EXACTLY HyperEars' cited baseline: no new upstream commit. README declares GPL-3.0; GitHub license null and no standalone LICENSE. Many capabilities were already unused in this baseline.
- moculll/ScrewVivoTWS: debug HEAD `81beeef0aaca22c7018ba03863c4f0ef759fde9f`, commit2025-06-02T04:40:29+08:00, pushed2025-06-01T20:40:51Z. No license declaration/file identified; GitHub license null. HyperEars notices unpinned. Qt/Windows program, not HyperOS integration.
- Star-ZER0/Pods-Protocol-Reverse-Engineering: main HEAD `7c5c56620232463e7da84132b1dd927a7a35d635`, commit2026-09-20T17:19:56+08:00, pushed2026-09-20T09:20:05Z. LICENSE CC BY-SA4.0. Fetched local cited `2d97d85b2cde9ee1446e9e7f67c222ac9b9f2bb9`; diff changes only handmade OPPO markdown (+54/-7) and removes old OPPO txt. Handmade MOONDROP and vivo protocols are unchanged since pin. README explicitly distinguishes hand-researched files from lower-confidence AI-generated files.

## Leaf fork: capabilities beyond local and beyond 1812z

Package source prefix `OppoPods-Leaf/app/src/main/java/moe/chenxy/oppopods/`.

- Identity/catalog: `pods/DeviceModelRegistry.kt`, `docs/Device_Whitelist.md:46-65`: 141 catalog entries, versus1812z's137. Four additions are `06EC10 OPPO Enco X4`, `06F450 OPPO Enco Buds3s`, `067814 OnePlus Buds Pro4`, `06E410 OnePlus Buds Clip`. Child sales aliases are also used in name matching. These are catalog/configuration evidence, not individually tested models. Full finite catalog is appended below.
- Model-dependent ANC now comes from `protocolIndex` bitmaps instead of a single family map; `pods/Packets.kt:599-717`, `DeviceModelRegistry.buildAncOptions()`. Supports distinct intensity/auto and transparency variants. `docs/Calibration_Features.md:9-23`: X2/X3 vocal enhancement (child modeType6,index9) versus Free2/Free2i/Air2Pro/RPro Listen (top-level modeType6,index3). A raw modeType6 means different things by nesting. Local Air2Pro special-case/family map loses these distinctions; avoid assuming all newer product names share X3/Air5 behavior.
- Endpoint alternatives: `pods/OppoRfcommSocketFactory.kt:16-34` UUID1107 first then079A, or explicitly selected channel15. Local OPPO endpoint has only079A. Add candidates only with bounded lifecycle and model/session evidence; do not globally force channel15.
- Handshake and telemetry: `pods/Packets.kt:63-82,403-468` supports0100/8100 handshake,0103 product ID,010B color,0200 advertised events,0205 subscription acknowledgements, EQ and existing advanced controls. Local battery/ANC-only protocol readiness does not exploit product/color or subscription-result evidence.
- Long-frame support: `pods/Packets.kt:31-59,185-360` implements variable-length OPOv1 framing and multi-fragment reassembly(control1/2/3, ordered fsn). Local `protocol/.../OppoWireCodec.kt` assumes one-byte length/max248-byte payload. This is important before customEQ/earscan larger payloads. **Source discrepancy:** Star documentation says encode totalLen-1 for multibyte lengths and add1 on decode; Leaf code encodes totalLen directly as standardLEB128. Resolve against known raw captures/tests; do not choose based on repo freshness.
- Earbud-fit test: `pods/CalibrationProtocol.kt`, `CalibrationEngine.kt`; `docs/Calibration_Features.md:25-39` query wear0109, start/stop0405, event4 result, bounded16sec timeout, device-status error handling.
- Golden Sound/hearing personalization: same engine plus `HearingProfileStore.kt`, docs:41-87. Ear scan040D, hearing tones040E, current profile0115, filter0116, description0411, ear scan storage0415, feature0B enable. Actual state machine checks wearing, cancels/disposes, verifies saved UID/name. Full interactive workflow, not a generic boolean card. Local OPPO has none of these. Source uses vendor imagery; protocol research does not authorize bundling proprietary images.
- Auto-play/pause wear toggle: `Packets.kt:388,866-949` feature04; spatial1B/0422; EQ/readback and multiconnect11. Local OPPO has no typed state/controls for these.
- Native Settings and MiLink hooks are semantic research references; `docs/Settings_Headset_Detail_Page_Analysis.md`, `docs/MiLink_17.2_Custom_Button_Analysis.md`. More upstream UI does not imply every function is native; calibration pages are module UI. Implement shared native service/state projection separately from vendor protocol.
- Stability: Leaf now has proper framing and hot-reload cleanup (AGENTS mandates shutdown). Preserve local ownership, per-address state, bounded retries and feature gates. Inspect framer malformed/incomplete-header behavior and strict length bounds in tests rather than copying it wholesale.

## QCY: unchanged baseline already contains a large unused feature surface

Source prefix `QCYpods/app/src/main/java/moe/chenxy/oppopods/`.

`pods/QcyProtocol.kt:20-79` commands: lowlatency09, noise0C, sleep10, EQv1 20, EQv2 22, LDAC23, dual-device24, dynamicEQ27, spatial2D, battery2F, adaptive-volume40. On=01; off=02 except adaptive-volume/dynamicEQ off=00. Spatial mode0..2 is exposed. Do not extrapolate vendor spatial mode2 as physically proven head-tracking on every model.

`QcyProtocol.kt:81-192`: PEQ encoding masterGain x100 signedLE16, per-band frequencyLE16/gainx100/Qx100/type; custom10-band31..16000Hz and gains-8..8. `QcyController.kt:305-408` actually queries/sets these, supports customEQ persistence. HyperEars `QcyWireCodec`/`QcyProtocolSession` implements battery and noise only; no new commit is needed to discover these gaps. Existing noise OUTDOOR02 is collapsed into ANC in both source/local card rather than a distinct selectable mode.

Additional endpoint/read sources: `QcyProtocol.kt:7-13` battery characteristic0008, version0007 alongsideA001/1001/1002; advertisement battery parsing:289-314. Local advertisement codec primarily supplies association identity, and private battery is currently confirmed via2F. A read-only additional characteristic source needs independent format validation before activation.

Finite concrete model evidence: only CrosskyC50S is specifically named by `pods/PodDetector.kt:7-12` (plus its-APP endpoint); generic qcy/crossky substring families are not enumerated tested-model lists. Local also includes contributed spellingQYCC50S. README does not provide further model guarantees. Advanced controls must be independently read-confirmed per session, never opened merely by matchingQCY.

Native UI: existing `hook/SettingsHeadsetHook.kt`, `hook/milink/MiLinkSpatialAudioHook.kt` can guide native service translation; app detail page exposes the broad optional set. Preserve separation between protocol support, module UI and genuine native widget projection.

Stability issues worth avoiding: `QcyProtocol.kt:199-215` minOf truncation accepts complete prefix commands from truncated outer frame; local strict streamed framing is stronger. `QcyProtocol.kt:237-240` any one-byte value other than01 is parsed false, not invalid; newly added toggle parsers should reject unknown values. `QcyController.kt:321-365` optimistically publishes feature values before confirmed write/device response; local acknowledgement/readback should remain authoritative. The12sec upstream scan and eager query-all path should not replace local8sec targeted companion scan and per-feature evidence gates. Test invalid replies, partial/coalesced frames, reconnect/state reset and native card pending/error state.

## ScrewVivoTWS: one exact model, extra controls already present

Finite exact supported list `ScrewVivoTWS/VivoController/VivoController.h:391-393`: **vivo TWS3e only**. No native HyperOS hooks; Qt UI/Windows Bluetooth.

`VivoController.h:229-235` includes existing unused GAIA vendor001B controls:

- double-tap mapping0102 (left/right bit encoding in helper);
- double-click/long-press behavior0150;
- long-press noise-cycle selections0131;
- DeepX preset0118 (default0,voice1,bass2,treble3,audiobook5);
- wear-detection enable0103 (0/1).

HyperEars `integration/.../VivoEarbudAdapter.kt` only supports battery/noise requests. The above writes are implementation evidence for TWS3e, not proof of same payloads on Air3Pro or allvivo/iQOO. Current local family already distinguishes GAIA version/query suffix/set suffix; carry the same discipline into advanced controls.

Source uses RFCOMM channel13 and JSON status with C=left_right battery and wear/noise settings (`ScrewVivoTWS.cpp` around535), not current local0207 battery query. No matching complete readback/error contract established for all extra controls. Qt socket lifetime, repeated connected-handler registration and pending single-write storage are not architectural templates for Android sessions. No automatic headset firmware/control changes performed.

## Star reference: what changed, and what must not be copied blindly

- New OPPO markdown clarifies request sequence, subscription replies and rejected event IDs, adds explicit read/write mode mismatch (query bitmaps differ from writes), and battery decoding. Local mode mapping already covers these coarse read values, so this alone is not new product support.
- At least two literal examples appear inconsistent with the document's own7+payload length: empty battery query saysAA06 rather thanAA07, adaptive4-byte payload saysAA0A rather thanAA0B. Build frames from validated formula/captures; never copy these prose hex strings unchecked.
- LEB128/fragment handling research is broader than local decoder; conflict with Leaf encoding noted above needs fixture validation.
- Handmade vivo and Robin docs unchanged since local pin; no new models. Vivo lists family protocol with no finite retail inventory, Robin doc identifies Robin. Keep AI-generated OPPO pending notes classified as leads, not equivalent to captured commands or actual working code.

## Implementation ordering

1. Capture-pinned identity/transport/parser improvements: product IDs, exact child aliases, variantANC, variable-length frames only after resolving source conflicts.
2. Typed vendor state/control contracts for confirmed optional toggles and EQ, preserving private capability gates/readback/ownership.
3. Shared native Settings/MiLink semantic projection with per-address snapshots and capability-based controls; do not import app activities as proof of native functionality.
4. Interactive fit/hearing workflows as separate lifecycle-managed features and separate hardware acceptance; no golden-sound/calibration writes on discovery.

## Full Leaf model catalog (catalog evidence, not tested support)

- 06F010: OPPO Enco Air4s
- 06B450: OPPO Enco R5; aliases: OPPO Enco xiapu OF
- 066812: realme Buds Air8
- 067414: OnePlus Flow Buds; aliases: OnePlus Nord Buds Laplace 4
- 06E810: OPPO Enco Air5s（星光版）; aliases: OPPO Enco Laplace 3
- 06C810: OPPO Enco Air5s; aliases: OPPO Enco Laplace 1, OPPO Enco Laplace 2
- 067014: OnePlus Nord Buds 4; aliases: OnePlus Nord Buds ohm EX
- 06E010: OPPO Enco Clip2; aliases: OPPO Enco Nobel
- 06D810: OPPO Enco Air5; aliases: OPPO Enco ohm DO, OPPO Enco ohm EX, OPPO Enco ohm
- 06C410: OPPO Enco Air5 Pro; aliases: OPPO Enco Einstein 1, OPPO Enco Einstein 3
- 066414: OnePlus Buds Ace 3; aliases: OnePlus Nord Buds Einstein 1
- 066814: OnePlus Nord Buds 4 Pro; aliases: OnePlus Nord Buds Einstein 2
- 066054: OnePlus Buds 3V; aliases: OnePlus Nord Curie DO
- 065854: OnePlus Nord Buds 3r; aliases: OnePlus Nord Curie, OnePlus Nord Newton, OnePlus Nord Newton
- 06B810: OPPO Enco Clip
- 065C14: OnePlus Open Buds
- 061410: OPPO Enco X
- 060410: OPPO Enco Free
- 062010: OPPO Enco Free2
- 061C10: OPPO Enco Play
- 061810: OPPO Enco Air
- 062410: OPPO Enco Buds
- 062810: OPPO Enco Air Lite
- 060C10: OPPO Enco W51
- 060810: OPPO Enco W31
- 061010: OPPO Enco W11
- 060412: OPPO O-Free
- 062C10: OPPO Enco W31 Lite
- 050410: OPPO Enco M31
- 050010: OPPO Enco Quiet
- 060414: OnePlus Buds
- 060814: OnePlus Buds Z
- 3D59CF: realme Buds Air
- F85450: realme Buds Air Neo
- E3009F: realme Buds Wireless Pro
- 8CD10F: realme Buds Air Pro
- BA5D56: realme Buds Air 2
- 1B5374: realme Buds Air 2 Neo
- C1E2B1: realme Buds Wireless 2
- C70A6D: realme Buds Wireless 2 Neo
- 693878: realme Buds Q2
- 49074B: realme Cobble Speaker
- C70A75: realme Pocket Speaker
- A5FD66: realme Brick Speaker
- 060812: realme Buds Air 3
- 060C12: realme Buds Q2s
- 050412: realme Buds Wireless 2S
- 061812: realme Buds Air 3 Neo
- 061412: realme Buds T100
- 061012: realme Buds Air 3S
- 061C12: realme Buds Air 5 Pro
- 063012: realme Buds Air6 Pro
- 050812: realme Buds Wireless 3
- 062012: realme Buds Air 5
- 063412: realme Buds Air6
- 063812: realme Buds N1 Pro
- 063C12: realme Buds T310
- 064012: realme Buds N1
- 062412: realme Buds T300
- 064812: realme Buds Air7
- 064C12: realme Buds Air7 Pro
- 066C12: realme Buds Air8 Pro
- 066452: realme Buds T500 Pro
- 067452: realme Buds T500 Pro Harry Potter Edition
- 067052: realme Buds T500
- 065C12: realme Buds Clip
- 065452: realme Buds T200
- 062812: realme Buds T110
- 064412: realme Buds T01
- 065052: realme Buds T200 Lite
- 065852: realme Buds T200x
- 066012: realme TechLife Buds
- 050C12: realme Buds Wireless 3 Neo
- 051412: realme Buds Wireless 5 ANC
- 052052: realme Buds Wireless 6 ANC
- 051812: realme Buds Wireless 6 Neo
- 051C52: realme Buds Wireless 6
- C70A6E: DIZO Wireless
- C70A6F: DIZO GoPods D
- C70A70: DIZO GoPods
- 060C14: OnePlus Buds Pro
- 062014: OnePlus Buds Pro 2
- 063014: OnePlus Buds Pro 2（轻享版）
- 063414: OnePlus Buds Pro 2R
- 064C10: OPPO Enco Air3
- 065C10: OPPO Enco Air3 Pro
- 065810: OPPO Enco R2
- 066010: OPPO Enco Free3
- 061014: OnePlus Buds Z2
- 050810: OPPO Enco M32
- 063010: OPPO Enco R
- 063410: OPPO Enco Air2
- 061C14: OnePlus Nord Buds CE
- 064010: OPPO Enco Free2i
- 068414: OnePlus Buds
- 063C10: OPPO Enco X2
- 068814: OnePlus Buds Z
- 063810: OPPO Enco Air2 Pro
- 050414: OnePlus Bullets Wireless Z2
- 061414: OnePlus Nord Buds
- 061814: OnePlus Buds N
- 064410: OPPO Enco Air2i
- 064810: OPPO Enco Buds2
- 065010: OPPO Enco R Pro
- 062414: OnePlus Nord Buds 2
- 062C14: OnePlus Nord Buds 2r
- 062814: OnePlus Buds Ace
- 066C10: OPPO Enco Air2（新声版）
- 067010: OPPO Enco Air3i
- 050C10: OPPO Enco M33
- 050C14: OnePlus BulletsWireless Z2 ANC
- 051014: OnePlus Bullets Wireless Z3
- 063C14: OnePlus Buds 3
- 066810: OPPO Enco X3i
- 067410: OPPO Enco X3
- 064014: OnePlus Buds Pro 3
- 067810: OPPO Enco Air3s
- 068010: OPPO Enco Buds2 Pro
- 064814: OnePlus Buds V
- 068810: OPPO Enco Air 3i
- 068410: OPPO Enco R3
- 067C10: OPPO Enco Air4 Pro
- 064414: OnePlus Nord Buds 3 Pro
- 069010: OPPO Enco Air4
- 065014: OnePlus Nord Buds 3
- 069C10: OPPO Enco R3 Pro
- 06A010: OPPO Enco Buds3 Pro+
- 064C14: OnePlus Buds Ace 2
- 068C10: OPPO Enco Free4
- 06C010: OPPO Enco Free4（丹拿版）
- 06BC10: OPPO Enco X3s
- 065414: OnePlus Buds 4
- 06A850: OPPO Enco Buds3 Pro
- 069850: OPPO Enco Air4i
- 06AC50: OPPO Enco R4
- 06A450: OPPO Enco Buds3
- 06B050: OPPO Enco Air4（新声版）
- 06EC10: OPPO Enco X4; aliases: OPPO Enco Bohr
- 06F450: OPPO Enco Buds3s
- 067814: OnePlus Buds Pro 4; aliases: OnePlus Buds Bohr
- 06E410: OnePlus Buds Clip; aliases: OnePlus Open Nobel
