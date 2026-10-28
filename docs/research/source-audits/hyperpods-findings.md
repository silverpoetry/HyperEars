# HyperPods source audit (2026-10-28)

## Provenance and limits

- Upstream: <https://github.com/Art-Chen/HyperPods>
- Current fetched HEAD: `9796d947daa18a379948349442632510424a1a15` (2025-10-05, `build: update version & compile sdk version`). This is already HyperEars THIRD_PARTY_NOTICES.md:33 pinned revision.
- License: GNU GPL v3.0 (LICENSE; README.md:23). Code references aln as AAP source (README.md:19); it is not evidence that HyperPods originated all later projects.
- No production changes or device actions performed. No device verification implied.
- No AirPods 5 / Beats product identification implementation found in Java/Kotlin or README. HeadsetStateDispatcher.kt:24-27,87-95 matches just two AAP UUIDs. BLE listen explicitly removed (README.md:15). Artwork files are not support evidence.

All upstream Kotlin paths below are relative to `app/src/main/java/moe/chenxy/hyperpods/`.

## Transferable protocol deltas

1. Ear detection: `pods/Packets.kt:68-94`: 8-byte notification `04 00 04 00 06 ?? left right`; in-ear=0, out=1, in-case=2. Upstream prefix check is only 5 bytes; strengthen exact packet framing/reserved-byte checks against independently observed protocol before adding. `pods/L2CAPController.kt:149-197` uses in-ear status for pause/resume and speaker switching. Separate telemetry from policy; do not import global media ownership or name-based route matching.
2. Adaptive ANC is a distinct fourth value=4 (`Packets.kt:24,196`; `ui/components/AncSwitch.kt:112`). HyperEars `AppleAirPodsAdapter.kt:136` currently collapses ADAPTIVE to ANC and offers only three states (line 146). Model it as Apple-specific serialized feature/control under existing typed contracts, rather than extending the common enum with model-only fields. Only expose after confirmed feature evidence.
3. Conversation awareness: settings selector 0x28, enabled=1, disabled=2 (`Packets.kt:25-27,189-203`); 10-byte notification prefix `04 00 04 00 4b 00 02 00`, status at byte9 (`Packets.kt:165-183`). Upstream processes status but performs no ducking in handler (`L2CAPController.kt:272-273`); README still calls feature scheduled. Command encoding is not full support proof or persistent-setting readback.
4. Additional command templates (`L2CAPController.kt`): off listening selector0x34 (438), adaptive strength0x2e (443), press speed0x17 (449), hold duration0x18 (455), single-bud ANC0x1b (461), swipe-volume toggle0x25 (467), swipe speed0x23 (473), tone volume0x1f (479), case charging sound proprietary 7-byte frame (485), rename (564), personalized volume two frames (574), loud-sound reduction (585). Except off-listening used by ANC, these functions have no callsites in upstream UI and lack validation/readback/model capability detection. Keep as candidate encoders until corroborated, not advertised working controls.
5. Long-press ANC cycling masks in `Packets.kt:207+`: selector0x1a, mask bits off=1/ANC=2/transparency=4/adaptive=8. Enum entries offer evidence for compact encoder but not readback or per-model support.
6. Existing HyperEars battery codec is better framed: count1..3 and components by ID, fragmented/coalesced input support. Upstream `Packets.kt:131-162` insists on exactly22 bytes, retains old unknown battery values. Do not regress to upstream parser/state model.

## Non-transferable architecture / ROM assumptions

- `hook/DeviceCardHook.kt:42-100` hooks SystemUI plugin `MainPanelController.onCreate` and `DeviceInfoWrapper.performClicked`, polls MAC with Thread.sleep (up to500ms), intercepts `third_headset` click, then opens standalone Compose module activity. This does NOT implement native HyperOS Settings UI.
- `hook/HookEntry.kt:12-14` scopes SystemUI, Bluetooth, Xiaomi Bluetooth. No Settings plugin or MiLink ownership interface.
- `hook/MiBluetoothToastHook.kt:183+` hooks MiuiBluetoothNotification constructor, installs broadcast handlers, constructs notifications, and writes/deletes internal animation files; old UI assumptions should not be copied.
- `pods/L2CAPController.kt:313-399` has global singleton connection/state, exported command receiver, coroutine timing (500ms and200ms), and no per-address concurrency. HyperEars session lifecycle and typed control contracts are superior.
- `app/src/main/cpp/hyperpods.cpp:62-67` globally replaces l2c_fcr_chk_chan_modes with return1. Discovery assumes 0xc50000 scan window, ARM instruction fragments and vendor-dependent 0x84/0x44 offsets (69-128). This is not endpoint-scoped and does not establish HyperOS4 compatibility; do not transplant.

## Native ownership integration implications

- HyperEars `PlatformReservedHeadsetPolicy.kt:11-22` treats native ownership OR any AirPods name OR any AAP UUID as reserved. This prevents unsupported Apple devices from ever reaching adapters. `EarbudAdapter.kt:663-664` also deliberately omits Apple registrations; `:715` applies global reserve. `BluetoothProcessHook.kt:85` independently applies reserve. Removing just the name guard is insufficient.
- Native supported devices must remain official-owned. Any future Apple fallback needs verified native ownership/coverage signal, unknown state fail-open toward official path, and one owner for protocol/card/settings; UUID/name are candidate identity only.
- Current decompiled Settings evidence already contains native `airpods/MiuiAirpodsFragment.java` lifecycle at522-523/642-643 and native state context, plus WearDetectionController. `HeadsetIDConstants.java:238` provides single-battery IDs0A20/1F20, but product names/support should be sourced from actual native library model registry, not inferred from these UI constants.
- Protocol improvements alone will not yield native device settings/card integration. Bridge confirmed typed capabilities into existing HyperEars native backends and current Settings semantic interfaces; preserve official owner and per-window isolation.

## Evidence needed before claiming models/features complete

- AirPods5 and Beats: concrete product ID mapping, actual endpoint/profile, response captures or independent upstream implementation, native current-ROM ownership evidence.
- Extended setters: legal argument ranges, capability discovery, readback packet decoding, unsupported-command behavior, controls bound to current session.
- Final changed version: real device validation of protocol, native card, Settings actions, reconnect, native-device coexistence. Source/build/unit tests remain distinct evidence.
