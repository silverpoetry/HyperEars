# LibrePods / HyperRose / NiceHCK / HyperOriG corpus audit

Fetched default HEADs on 2026-10-28; read-only source comparison, no production edits or device operations. Full THIRD_PARTY_NOTICES.md was read and pinned revisions fetched for direct comparison. All source paths below relative to each `.analysis/upstreams/<repository>` checkout. These are implementation declarations, not new hardware validation.

## LibrePods

- Source <https://github.com/librepods-org/librepods> (old kavishdevar/aln location).
- Current default HEAD `89356c08cea3db0b6b54792d9329d09a87cc85fa`, 2026-10-16; baseline `b5a3eaee8fbe5a0c83c360bb0fdcd6705a59cc25`. Diff: 88 files changed, including model metadata, AirPodsService, UI, Android17/rootless detection; AACPManager unchanged between these pins but substantially exceeds HyperEars subset.
- GPL-3.0-or-later (LICENSE and source headers).
- Complete explicit Android registry (`android/app/src/main/java/me/kavishdevar/librepods/data/AirPods.kt:50-269`):
  1. AirPods1: A1523,A1722.
  2. AirPods2: A2032,A2031.
  3. AirPods3: A2565,A2564.
  4. AirPods4: A3053,A3050,A3054.
  5. AirPods4 ANC: A3056,A3055,A3057.
  6. AirPods5: A3531,A3532,A3533.
  7. AirPods5 with Wireless Charging Case: A3439,A3440,A3441.
  8. AirPodsPro1: A2084,A2083.
  9. AirPodsPro2 Lightning: A2931,A2699,A2698.
  10. AirPodsPro2 USB-C: A3047,A3048,A3049.
  11. AirPodsPro3: A3063,A3064,A3065.
- No Beats or Max entry in this explicit Android registry. Generic AACP commands (e.g. crown rotation) do not prove a tested product profile. Model declarations likewise need protocol/native-owner evidence rather than marketing inference.
- Concrete source-based deltas versus HyperEars AppleAapWireCodec/AppleAirPodsAdapter: device-information opcode0x1d and model/serial/version parsing (`bluetooth/AACPManager.kt:1282`); generic control-command status and listeners (210-280,401-490); ear detection, conversation awareness, head tracking, stem-press (401-520); connected devices and audio-source/ownership events; proximity keys; rename; hearing accommodation and custom EQ.
- Control identifiers (`AACPManager.kt:88-116`) cover mic mode, click/hold timing, mode-cycle mask, single-bud ANC, crown direction, auto-answer, tone volume, swipe interval/toggle, call management, adaptive volume, software mute, conversation detection, hearing aid, adaptive strength, HRM, case sounds, hearing assist, off-mode permission, stem config, sleep detection, ear detection, auto connection, PPE and charge completion. HyperEars currently only battery plus three-state noise, even flattening ADAPTIVE into ANC. Treat each identifier with actual response/value validation; enum existence alone is not exposure permission.
- Capabilities are declared per model in AirPods.kt. They should be candidate metadata, with actual state confirmation retained in HyperEars. AirPods5WCC adds SWIPE_FOR_VOLUME; Pro3 adds HRM/PPE; do not flatten all to generic AirPodsPro.
- Native UI: LibrePods is its own Compose application/widgets/quick-settings dialog, not a ready-made HyperOS native Settings or MiLink solution. Android README mentions icon integration/root hooks, not native feature-page adoption. Preserve HyperEars native-owner boundary and implement typed Apple feature/state projections separately.

## HyperRose

- Source <https://github.com/DOHEX/HyperRose>.
- HEAD `4e212f79bb1929892e4ac8ae22ed5c2d351e6944`, 2026-08-22, release v0.1.6; baseline `635c0dd89ee07aab95c4138ca1275e44fd666165`. Diff105 files includes new catalog, EarFeel i7/Ceramics U, shared RFCOMM parser, UI/IPC refactor.
- README:128 declares GPL-3.0 and links LICENSE; current checkout still has no LICENSE file. Keep existing notice caveat and independently reconstruct interoperability facts, not copy UI/resources.
- Complete current DeviceCatalog.kt:19-27: EarFeel i5 (alias ROSE EARFREE i5), EarFeel i7, ROSE BudsFeel MK2, ROSE Ceramics U. README stale text says two models; registry is four.
- New exact model candidates vs current HyperEars: EarFeel i7 and Ceramics U. HyperEars existing Ceramics Ultra is NOT automatically same retail model as Ceramics U; require actual identity evidence, preserve separate catalog names.
- `profile/EarFeelI7Profile.kt:9-35`: RFCOMM UUID0cf12d31-fac3-4553-bd80-d6832e7b3931; four ANC states and game mode; advanced controls explicitly disabled. BudsFeelMk2Profile.kt:21-35 also disables advanced controls and declares game mode. Never promote shared parser's EQ/depth capabilities to these two profiles.
- `profile/CeramicsUProfile.kt:12-62`: same RFCOMM endpoint; six ANC modes (normal/off, ANC, transparency, wind, adaptive, extreme); depth light/medium/deep; transparency standard/deep; EQ HiFi/pop/rock/Rose classic; game mode, find earbuds; commands for gain, tone language/level, touch remap.
- `profile/rfcomm/RoseRfcommProtocol.kt:16-121`: noise0x09 values1..6, ANC depth0x2c values1/3/5, transparency0x2d values1/5, EQ0x2a values0..3, game0x0e, find0x2f, gain0x45, tone language0x07, tone level0x2e, touch command0x03. `RoseRfcommResponseParser.kt:35-73` supplies status readbacks for battery/noise/game/EQ/depth/transparency/tone, but skips a21-byte touch section; gain/touch/find full readback not established by parser.
- `profile/earfeel_i5/EarFeelI5CommandSet.kt:27-95` already contains depth, transparency level, EQ, game, find and status queries. Current i5 profile exposes EQ Classic/Instrument/Fresh, NOT Japanese despite JAPANESE opcode constant. Current HyperEars rose sessions only expose battery/noise; these are genuine missing features even where protocol facts were already in baseline.
- Current HyperEars `RoseEarbudAdapter.kt:400-527` sessions and Rose wire codecs must gain vendor-specific typed features/controls, confirmed state, and card/settings projection; do not just add model aliases.
- Native UI pattern `hook/MiLinkProcessHook.kt:30,133-218`: fake device ID01010607, hooks ANC/battery, redirects switchToHeadsetActivity to standalone QuickControl. Does not satisfy native full Settings requirement. Do not transplant global fake-ID/redirect architecture.
- Detailed protocol notes and tests available under docs/protocols/{earfree-i7,ceramics-u,budsfeel-mk2}.md and app/src/test/.../profile. Those are source evidence separate from final HyperEars real-device revalidation.

## NiceHCK_Controller

- Source <https://github.com/ZaeXT/NiceHCK_Controller>.
- HEAD `f1348a8d09fc57e3b7098a4f15bae5f926475771`, 2026-01-07; equals baseline. MIT LICENSE, Copyright2025 Zhang Xinlin.
- Explicit supported product scope: NiceHCK/YuanDao OriG in (one concrete product; no model registry proving additional NiceHCK models).
- `app/src/main/java/com/zaext/nicehckcontroller/NiceHckProtocol.java:4-96`: six ANC states off00/transparency01/normal02/deep03/experimental10/wind11; EQ presets blue00/balanced01/bass02/pure03/game04/fine05/vocal06; codecs AAC00/LHDC01/SBC02; game, low latency, multipoint, in-ear detection, wind toggle, firmware/full-state.
- `BluetoothController.java:291-382` decodes firmware and EQ/game/lowlatency/dual/in-ear/wind responses; setters at544-574; queries594-614. HyperEars NiceHckEarbudAdapter currently queries only battery+ANC and collapses normal/deep/experimental into ANC. Thus unchanged upstream HEAD still has large unintegrated behavior.
- Firmware-sensitive EQ mapping at551-561 (older firmware requires offset mapping), codec API change around firmware408, and version response at334-340 must be incorporated as observed compatibility facts. Upstream `supportsNewCodec` compares main>=4 AND sub>=8 at461; do not copy that as correct lexicographic version logic without evidence.
- Separate wind suppression boolean0x02e1 is not equivalent to ANC enum wind11. Keep distinct typed state/controls.
- Native UI: standalone Android application and QS tiles, no HyperOS native Settings implementation. Keep MIT notice if derived code adopted; do not copy stale unknown-battery caching behavior.

## HyperOriG

- Source <https://github.com/KiriChen-Wind/HyperOriG>.
- HEAD `8d0014d3dc0a8ce420a3f9847179cf436f33e6cf`, 2026-09-12; baseline `d2f2a4b1111e085f9720cc32ed072abb343778d0`. Diff30 files includes LSPosed102/Android17, SystemUI restoration, popup lifecycle, cleanup of disconnected RFCOMM sessions.
- Still no LICENSE file or explicit software license. Behavioral reference only; MIT NiceHCK provides independent protocol source.
- Explicit product only YuanDao OriG in. Broader substring match helpers are not extra verified products.
- `pods/Packets.kt:20-110` corroborates same six ANC modes/seven EQ presets/query+set opcodes; parsers include game/latency/dual/wind/in-ear/EQ beyond HyperEars subset. Case/ear battery handling (`:119-153`) rejects0 and lacks upper bound; do not copy this regression.
- `docs/hyperos4-compatibility.md:3` explicitly says APK/source adaptation without real-device display/root restart testing. Documents semantic candidates for Android17, current MiuiBluetoothNotification constructors, PluginFactory and AncBatteryController; useful lead, not confirmation for this user's current ROM.
- `hook/SettingsHeadsetHook.kt:3-23` deliberately blocks native MiuiHeadsetActivity for fake model ID. `MoreSettingsRedirectHook.kt:10-38` redirects native more-settings to module app. `MiLinkServiceHook.kt:53-79,167-184` synthesizes ID, battery and three ANC slots. This remains incompatible with the requested fully native Settings page.
- Lifecycle improvements and per-hook fail-open are useful behavioral checks, but independently implement within HyperEars rather than import unlicensed source.
- Document references HyperPods os4-dev `a620af1b6ce08ba8ad23d87a069293da23d5899d`. Live Art-Chen/HyperPods refs checked: aap9796d947, main9c24fc86, os3-dev9113d16; no current os4-dev ref. Treat document citation as historic/local reference, not an existing default-branch guarantee.

## Cross-cutting implementation implications

- Design native UI projection after this full corpus review; model registry coverage and complete actionable features are separate axes.
- Keep actual native-owned devices official-owned. Broad Apple name/UUID reservation currently blocks all fallback, but blindly lifting it creates dual ownership; resolve ownership first.
- Use existing ControlRequest/DeviceFeatureState namespaces, legal-response activation and lifecycle/session boundaries. Complex ANC modes, EQ, gesture mappings, firmware, toggle options need explicit state/value constraints and native field mapping.
- Do not label upstream code, source tests, successful build or old-device report as final HyperEars hardware validation. Model aliases/firmware thresholds and command effects remain independently testable evidence gaps.
