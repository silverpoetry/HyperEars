# HuaweiPods / SonyPods source comparison (2026-10-28)

Read-only research; no production edits, build, device operations, or runtime acceptance performed. Clones are shallow snapshots. Read AGENTS.md and CONTRIBUTING.md before review.

## Revisions and adoption

- HuaweiPods: <https://github.com/Nshpiter/HuaweiPods/tree/dc5b9d14e9030be95b178699d674d1a3217e1e6d>
- SonyPods: <https://github.com/Mercury000/SonyPods/tree/3f701ff8f9b30c6909b9dbe8d99248f20d55560e>
- Both have actual GNU GPL v3 LICENSE files (lines 1-2), unlike sources that merely mention GPL in README. HyperEars declares GPL-3.0-only in CONTRIBUTING.md. Direct code adoption needs preserved source notices and documented modifications/source distribution; license text does not establish rights to vendor artwork, extracted assets or every dependency. Audit adopted files separately. Existing THIRD_PARTY_NOTICES.md has neither upstream entry. Both upstream hook stacks contain app-specific lifecycle/UI assumptions; wholesale transplantation is inappropriate technically even where source licensing allows use.

## Huawei models and real deltas

Source prefix H = `.analysis/upstreams/HuaweiPods/app/src/main/java/moe/chenxy/huaweipods/`.

`H/pods/DeviceCapabilities.kt:3-22` explicitly defines 18 enabled device routes: FreeBuds 3, 4, 4E, 5, 5i, SE 4 ANC, 6i, 6, Pro 3, Pro 4, Pro 5, 7i, 7, FreeClip, FreeClip 2, FreeArc, Eyewear, Eyewear 2. FreeClip 2 Collector is an alias of FreeClip 2 (`:223-230`); Eyewear/Eyewear3 aliases share a route. README's 20 marketed models is not 20 different protocol implementations. Do not turn route count into verified-hardware count.

HyperEars exact adapters currently include Pro 3, 5i, FreeBuds 4 and FreeClip 2; other FreeBuds/FreeClip/FreeLace names can enter family fallback (`integration/.../HuaweiFreebudsProtocolAdapter.kt:84-148`). FreeArc/Eyewear are absent from these family prefixes. Therefore exact profile coverage differs substantially, but calling all other FreeBuds completely unsupported would be inaccurate.

High-value additions beyond current component battery / three-state ANC / Pro 3 levels:

- FreeBuds 3 legacy HFP control and 9-position ANC direction dial (dedicated HuaweiHfpController plus DeviceCapabilities.kt:43-50). Needs separate protocol path, not adding a name to shared SPP.
- 4/4E two-level ANC and gestures/wear/EQ; 5/6 three-level ANC; 5i/6i/SE4/7i four-level profiles; Pro5 distinct transparency and smart features. DeviceCapabilities is capability declaration only: verify corresponding controller, fixtures and per-model feedback. Upstream explicitly labels FreeBuds 4 unvalidated (`README.md:35`), SE4 dynamic ANC pending comparison (`:39`). HyperEars already documents its 5i level tests failed, so upstream claims do not supersede local gap.
- Model-specific gesture configuration, wear detection, EQ presets/custom bands, low latency, dual-device controls, spatial fixed/tracking, quality, adaptive volume, case tones and head gestures have executable controllers, not only UI names.
- Strong concrete example: `HuaweiFreeBuds7Controller.kt:21-106,153-225,238-246` queries/writes settings and matched readback, spatial, 10-band EQ, per-ear triple tap, smart/quality/latency/wear controls. `docs/FREEBUDS7_ADAPTATION.md:14-42` distinguishes tested controls from untested effects, physical behavior and long-term use. Protocol commands are explicitly documented there (2BB4 features, 2B49/4A EQ, 2B92/93 pinch, etc.).
- FreeClip2 current HyperEars battery-only profile can gain spatial, gestures, EQ, head/call actions and reported audio toggles; no ANC. Corresponding upstream `HuaweiFreeClip2Controller.kt` and FreeClip2BooleanReadbackTest/AudioStateTrackerTest exist. Collector identity is not a new ANC-capable product.
- Low latency is not universally device-reported: `README.md:100` explicitly describes last successful setting + reconnect replay for older profiles. Typed state must label desired/saved versus confirmed state rather than fabricate readback.

Native UI evidence and limits:

- `H/hook/SettingsHeadsetHook.kt:302-450` hooks actual MiuiHeadsetActivity/support/service proxy for native Bluetooth details; this is beyond merely app-native styling.
- FreeBuds 7 uses actual `MiuiHeadsetAncAdjustView.setPointCount(3)` and stock rendering/listeners/haptics (`docs/FREEBUDS7_ADAPTATION.md:57-61`). Stock battery, three-state ANC, discrete depth and suitable transparency variants are clear native-component candidates.
- Its MiLink stock card already maps spatial modes and authoritative per-address state (`same doc:45-55`). Fixed/head-tracking mapping must remain separate from wire enum values.
- This is not an all-native advanced UI solution: `SettingsHeadsetHook.kt:2534-2618,2992-3154` also hides sibling/view branches and installs a custom ANC direction dial; many advanced controls live in module UI. HyperEars CONTRIBUTING rule 6 rejects brittle view-hierarchy heuristics. Need semantic host capability resolver and reusable extension rows/pages for EQ, detailed gestures, multipoint list, smart toggles where no real native counterpart exists.

## Sony: capability expansion, not a trustworthy finite model matrix

Source prefix S = `.analysis/upstreams/SonyPods/app/src/main/java/dev/sonypods/`.

SonyPods deliberately moved away from static model profiles (`docs/DEVICE_ADAPTATION.md:99-117`). README_EN.md:17-19 says theoretical support for all Sony headphones; this is NOT confirmed support or an enumerated adapter list. Existing HyperEars explicit set (`integration/.../SonyEarbudAdapter.kt:114-179`) is WH-1000XM2/XM3/XM4/XM5/XM6, WH-CH720N, ULT WEAR; WF-1000XM3/XM4/XM5/XM6, WF-C500/C510/C700N/C710N, WF-SP800N; WI-SP600N, WI-C100; LinkBuds, LinkBuds S. Keep these identities; extend transport/capability coverage rather than invent additional names.

Concrete source-backed delta:

- `S/headphones/HeadphoneAdapter.kt:19-24,78-111` supports V1/V2 x Table1/Table2, SPP and three GATT channels (HPC and MC service UUIDs). HyperEars Sony currently only private RFCOMM V1/V2 subset. This expands actual transport compatibility, including LE-only endpoints; must not equate it with proven compatibility for every model.
- `S/headphones/SonyCapabilityProbe.kt:30-95,137-155,279-470` requests support functions and capability/extended info, derives per-feature channel/variant and EQ band geometry, and supports dynamic multipoint/LEA restrictions. This is more useful than a new name table.
- `S/data/SonyHeadphoneRepository.kt:1933-2027` ambient 1-20/voice focus/adaptive settings; `:2063-2208` multipoint and source selection; `:2273-2314` Speak-to-Chat and LE audio; `:2518-2805` EQ/ClearBass/gesture/QuickAccess; `:4030-4053` DSEE and connection quality. Associated typed protocols and protocol/route/capability tests are present. Tests were inspected by file, not executed.
- Feature enum (`S/headphones/HeadphoneAdapter.kt:33-55`) additionally includes power-off, wearing, playback and safe-listening pressure. Safe listening entails polling (`:52-54`), requiring explicit lifecycle/energy design; it is not just another static settings row.
- `S/hook/SettingsHeadsetHook.kt:186-267` hooks actual native Settings details/support/service proxy and ANC; `README_EN.md:37-45` describes native firmware/codec badges and removal of inapplicable depth slider, MiLink ANC/battery, while advanced control popup is separate. Upstream does not demonstrate that EQ, every gesture and multipoint editor are native Settings widgets.
- Strong regression assets: SonyCapabilityProbeTest, SonyTandemProfileRoutingTest, EqProtocolEngineTest, SonyGattTandemSessionTest, SonyTandemCommandCollisionTest and MiLinkNativeRoutingArchitectureTest. Reuse protocol fixtures with attribution only after validating against HyperEars boundaries; do not use passing upstream tests as target-ROM/device acceptance.

## Suggested migration acceptance phases (design input, not implementation authorization)

1. Build a model x confirmed feature x transport x native-host-component matrix. Keep current exact devices and fallbacks; separate upstream fixture evidence, upstream hardware reports and HyperEars final-commit acceptance.
2. Generalize shared native projection for battery/ANC/level/spatial where actual host components support semantics. Verify legacy MiLink and HyperOS4 separately. Undefined native controls remain hidden; no repurposed find-earbud/ANC slot for unrelated features.
3. Add typed feature-state/control contracts and pure codecs first (Huawei matched feature/CRC/TLV; Sony channel/variant collisions and dynamic capability gate). Tests cover truncation, ACK-only false positives, stale sessions, rejected writes and reconnect.
4. Migrate advanced controls by model cohort with actual device readback and official-app contention tests. Distinguish configured state, acknowledged write and real auditory/gesture behavior. Validate power budget and no repeated background work.
5. Release only after final card/protocol/lifecycle build is revalidated on relevant device+ROM. Draft maintenance PR and original contributor PR remain open until revalidation, per CONTRIBUTING.md; preserve contributor commits and licenses. Upstream reports and local unit/CI/build success are not substitutes.
