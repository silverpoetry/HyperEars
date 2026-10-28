# HyperEars full native integration implementation plan

> Execute task by task using subagent-driven-development. Preserve the full objective across
> sessions; a passing milestone is not completion of the complete integration goal.

**Goal:** Deliver the comprehensive HyperEars requested in the reviewed integration report:
missing models, advanced capabilities and uniform native HyperOS presentation, then submit a PR.

**Architecture:** Respect and retain the existing Adapter / ProtocolSession / WireCodec,
typed feature/request contracts and CardAdapter registry. Add missing functionality through those
patterns first. Reuse small native-control helpers where needed; introduce a new shared abstraction
only after documenting a concrete inability of the existing pattern to carry required behavior.
Actual native controls are preferred; advanced functions without equivalent stock controls use
consistent host-themed extensions through the current presentation boundaries.

**Tech stack:** Kotlin, Android 15+, HyperOS/LSPosed, pure wire codecs, serializable feature/control
contracts, Gradle/JUnit, actual installed ROM plugin interfaces captured during research.

## Working state and authority

- Original checkout: `D:/Backup/Desktop/HyperEars`, baseline `26d3151`.
- Development worktree: `D:/Backup/Desktop/HyperEars-full`.
- Development branch: `feat/full-native-integration`.
- Approved direction: user activated goal “完成大而全版本的移植与开发” after receiving the report.
- Subsequent user constraint: minimize architectural changes, adapt extra functions using current
  patterns, and change a pattern only when necessary. This overrides the report's more ambitious
  redesign suggestions without reducing model/feature coverage or native experience requirements.
- Apple-specific user policy: Xiaomi-supported Apple headsets always prefer Xiaomi's existing
  path. HyperEars fills unsupported models, using AndPods as primary identity/protocol reference
  and original HyperPods as supplementary AAP reference. LibrePods and other sources corroborate
  gaps; they do not override the user's source priority or displace working native Apple support.
- Dynamic Apple difference policy: the user confirmed Bluetooth resource updates restart
  Bluetooth. Reevaluate current native support on each fresh device connection after restart,
  even if HyperEars itself was not updated. Native-supported devices go to Xiaomi; unsupported
  devices are eligible for module fallback. Do not persist stale support results, embed a frozen
  Xiaomi model allowlist or infer support from artwork alone. No live resource-update monitor
  or active-connection hot handover is required; use the existing connection lifecycle.
- Research: `docs/research/2026-10-28-hyperears-integration-report.md` and linked inventories/audits.
- Follow-up native Apple connection design:
  [current ROM support and session ownership](../../research/source-audits/apple-dynamic-native-ownership.md).
- Added user reference: NEORUAA/HyperPods-X at `018765be`, based on YeFeng233/HyperPods-X
  `30cc323b`. See [protocol audit](../../research/source-audits/hyperpods-x-protocol.md) and
  [native/spatial audit](../../research/source-audits/hyperpods-x-native.md). Use its A3441 hardware
  evidence, control/readback, authenticated BLE, wearing/conversation and discovered-motion service
  logic to supplement AndPods-first work. Its app-owned socket and independent advanced Settings
  do not replace the requested native-first session model or native presentation requirement.
- Large local source/APK/ROM evidence remains in the original checkout's `.analysis/` directory.
- This plan permits development/build/isolated integration. No device restart or installation is
  implied; follow the user's separate device-action boundaries and retain runtime evidence gaps.
- Do not change `AGENTS.md`; preserve original contributor commits and open maintenance PRs until
  their final changed version has device revalidation. A broad integration PR may remain Draft.

## Verification and progress states

Use pending / implemented / unit-verified / built / device-verified / delivered separately.
For every feature retain source revision, exact model/firmware/transport constraints, state read,
write, acknowledgement/readback, UI projection, tests and remaining device evidence.
Source catalog entries are candidates, not verified capability flags.

At each implementation slice:

1. Refresh the relevant current code and source evidence; identify dependencies and affected files.
2. Add meaningful malformed-frame, state/lifecycle or UI contract regression tests and observe the
   expected failure before new behavior. For imported contributions retain their original history
   and independently execute their regression tests.
3. Implement the complete slice, including contract registration, UI projection and documentation.
4. Run focused tests, then the necessary module/full checks after integration. Inspect every result.
5. Review scope compliance and code quality; fix substantive findings before marking the slice.
6. Record evidence and commit. Do not mark the overall goal complete based on a subset of slices.

## User-defined verification and delivery standard

The user clarified that physical possession is limited and must not block adaptation of other
models. AndPods -> AirPods 5 wireless and RoseLink -> ROSE Coral are the two representative
proprietary-application interoperability paths to complete first and validate with owned hardware.
Reuse their proven integration patterns for the other models in those applications, retaining
model-specific protocol/configuration differences. Other source-based ports require code review
and meaningful unit tests for initial delivery. Record hardware-unverified status accurately;
release issue reports and later available headphones drive additional fixes. Lack of every
physical model is not an incomplete-work gate for the full integration deliverable.

Final changed-build hardware checks focus on the owned representative devices. Existing upstream
contributor-PR workflow requirements still govern PR status and original-PR preservation; source
and unit verification may deliver a Draft PR without falsely claiming those contributors have
revalidated changed device behavior. The full model/function scope remains unchanged.

## A. Integration foundation

- [x] A1 Establish clean baseline tests and build environment in isolated worktree.
- [x] A2 Preserve and integrate PR #99 `f36053ed`: latest admissible popup snapshot during launch;
  validate `NativeWindowRequestTest`, producer/session/connection/revision/native ownership, true
  zero and unknown batteries. Files: `system-module/.../presentation/{NativeWindowRequest,
  NativePresentationRuntime,AirPodsWindowBridge}.kt`, corresponding tests, CHANGELOG.
- [x] A3 Preserve PR #95 `b333f1fe` and original #92 ancestry: ZZZ-ANGELS identity, handshake,
  component replacement, unknown-state cleanup and external-controller recovery. Resolve only
  actual conflicts; preserve the original final-package report without applying it to new changes.
- [x] A4 Preserve PR #96 `eeca05ba` and original #90 ancestry: FloS2/OpenPro S2 aggregate battery
  and four sound effects. Repair partial-install rollback in native card host; add regression tests.
  Final maintenance hardware feedback and companion-app identity remain separate evidence items.
- [x] A5 Recover #50 W820NB dual-gold BES V1 protocol and exact identity without its text-matched
  selected-state UI workaround. Add codec/adapter tests, origin attribution, native projection.
  Merge 91868b3 preserves original d38eea5 ancestry, adds strict V1 parsing/readback/reset and the
  current native host. Original PR remains author-closed; changed hardware validation is pending.
- [x] A6 Correct explicit-offline versus absent-component state semantics, beginning with Pudding;
  retain measured ZZZ firmware behavior instead of guessing offline from battery age.

## B. Native presentation through existing extension patterns

- [x] B1 Inventory how existing CardAdapters and typed feature/request contracts can express each
  required control, including choices/ranges/toggles, availability and confirmed/pending/error state.
  Use existing vendor feature namespaces and control contracts. Do not replace the presentation
  system with a generic descriptor framework by default. Record a specific gap before introducing
  any narrowly scoped additional abstraction or envelope change.
- [ ] B2 Implement native MiLink selectable cards using validated host component contracts,
  variable supported choice counts, native visual state and transactional bind/restore. Replace
  title-row add-ons and brand-specific popups with the shared host where equivalent.
  Shared native selectable hosts, transactional restoration and variable choices are implemented.
  Advanced popup-to-native inline migration remains, as separately tracked under B5.
- [ ] B3 Implement Settings plugin discovery/lifecycle and a per-address native details bridge.
  Native symbols from current ROM include `JavaActivity`, `MiuiAirpodsFragment`,
  `MiuiHeadsetFragment`, `AncController`, `MiuiHeadsetAncAdjustView` and native preferences.
  Add Settings scope and manifest/settings/help declarations consistently. No brand spoofing
  outside the bound module-owned context; never call a Xiaomi vendor command for a foreign device.
  Generic native Settings page bridge now implements address-bound battery/noise/Apple listening/
  Honor depth and existing-editor access with authenticated state and cleanup. Specialized plugin
  routes remain unhooked to avoid initiating their official vendor connections. Source contract:
  ../../research/source-audits/settings-native-page-bridge.md. Host device validation is pending.
- [ ] B4 Implement native/headset ownership policy for system-full, system-partial, module and
  external controller states. For Apple, preserve Xiaomi-first support and enter the module path
  only for unsupported models. One private writer per device; do not equate an Apple name/UUID
  with actual system adaptation. Preserve normal audio and existing confirmed native ownership.
  Reevaluate current native support for each fresh connection. Keep ownership evidence scoped to
  that connection and skip module transport/control/presentation when Xiaomi owns the device.
  Cover an unsupported device becoming native-supported after Bluetooth restart without a module
  update, stale prior-connection callbacks, and unknown/unavailable support observations in tests.
  Connection-scoped Xiaomi-first Apple admission, retired callbacks and mutually exclusive module/
  external ownership are implemented and tested. Generic partial-system sharing is not implemented;
  do not introduce multiple private writers just to satisfy that earlier wording.
- [ ] B5 Route battery, noise, levels, spatial, wearing, gesture, EQ, multipoint, find and device-info
  groups into one consistent native-details hierarchy using existing adapter-selected presentation
  bindings and type-safe requests. Advanced editors use the same host styling.
  Generic Settings now has native lists, switches and sliders for Apple/Coral and common daily
  brand controls. Actual AndroidX tests cover old callbacks, source definitions, one-use audio
  confirmation and host category ownership. The remaining ordinary fields now have native rows;
  complex curve/gesture/find editors continue through their existing address-bound advanced entry.
- [x] B6 Update existing registered brand/model presentations in place through their current
  registries; remove unsupported controls (including FreeClip false ANC), retain working model
  specializations and preserve standard/headphone/TWS topology.
- [ ] B7 Integrate connection popup/super-island/notifications with authoritative snapshots and
  lifecycle cleanup; verify multi-device, unknown/zero, dark/light, layout and accessibility.
- [ ] B8 Refine optional external-controller coexistence beyond broad process presence where
  evidence permits; include AndPods/LibrePods, regional apps, multi-process and user ownership.
  AndPods/LibrePods exact packages, Apple adapter priority, scope declarations, navigation and
  existing handoff cleanup are implemented with tests. A more precise per-device vendor-session
  signal is still evidence-dependent; process presence is not misrepresented as such a signal.

## C. Protocol and model completeness by family

Each family needs its own focused implementation plan/test set when entered; this umbrella ledger
does not treat every source command as universally supported. All items below remain in scope.

- [ ] C1 Apple/Beats: model/device-info and BLE/AACP identity; AirPods5 wireless first complete
  path; expand AAP stream/control decoding; adaptive mode distinct from ANC; wearing, long-press,
  noise-cycle, stem/swipe, conversation/adaptive volume, mic/timing/tone/case/ear settings,
  connected-device events, rename, head tracking and supported hearing/EQ/sensor functions.
  Add AndPods' 31 Apple/Beats model candidates and LibrePods model numbers; preserve HQB separation.
  Apply AndPods-first, original-HyperPods-second source priority, with other sources as cross-checks.
  Tests must prove native-supported Apple stays official-owned while unsupported new models are
  eligible for validated module fallback, instead of blanket Apple exclusion or takeover.
- [ ] C2 OPPO/OnePlus/realme: merge the 141-entry catalog as candidates; product-ID refinement,
  exact aliases, endpoint variants, long frames/fragments, model-index ANC/transparency,
  EQ/custom EQ, game, multipoint, spatial, wearing, prompts, fit and Golden Sound workflows.
  Resolve source length conflicts with fixtures. LE Audio has a separate lifecycle/peer/group
  implementation and actual stereo-playback acceptance, including manual disconnect behavior.
  Identity slice implemented: 141 catalog entries, 118 source-declared SPP headset candidates,
  strict 24-bit product replies, exact aliases and same-session refinement. Daily switches,
  reported EQ selection and existing custom-slot editing now have typed contracts/native details.
  Long-frame/fragment parsing now follows capture-backed canonical lengths, preserving legacy
  frames. Larger EQ writes require live long-frame and budget evidence. Additional workflows
  and LE Audio remain outstanding; identities grant no controls.
  Indexed noise selection now covers 43 source product tables with strict current-state reports,
  pre-identity control gating, native parent choices and model-specific level/transparency options.
- [ ] C3 Huawei/Honor: union HuaweiPods/OpenFreebuds exact routes and aliases; legacy RFCOMM plus HFP battery and SPP
  FreeBuds3 paths, Studio/FreeLace topology, firmware-specific ANC/transparency, EQ, gestures,
  spatial, wearing, dual-device, latency, quality, prompts/language and smart functions.
  Preserve Pro4 conflicting evidence and 5i level gap until resolved on matching hardware.
  Daily slice implemented: nine additional exact candidates, per-model wear/multipoint/EQ/gesture
  profiles, native details, stale-slot guards and aggregate-battery qualification. Other workflows
  and hardware validation remain outstanding.
  Model-qualified low latency and sound-quality preference now have native choices and exact
  readback contracts; the latency query uses a bounded one-second adapter effect.
  FreeBuds3 SPP adds its distinct double-tap mapping with exact native labels, without borrowing
  later ANC commands. The reference legacy RFCOMM ANC writes lack reliable readback; its HFP battery hook is separate.
  SE, SE2, SE4 ANC and Pro5 exact profiles are added. SE/SE2 cannot acquire ANC; SE2 hold is
  call-only. The shared level extension preserves Pro3 bytes and qualifies each model's levels.
  HuaweiPods 4E/5/6/7/7i/FreeArc/Eyewear routes are also implemented. FreeArc, 6i and
  FreeClip 2 now use source-qualified independent left/right swipe snapshots and single-side writes.
  Complete source-qualified custom EQ create/select/update/delete is implemented for6i/Pro3/Pro5/
  FreeLace Pro2; other custom dialects remain separate.
- [ ] C4 Sony: SPP/GATT, V1/V2, Table1/Table2 capability discovery; environment levels/voice,
  EQ/ClearBass, DSEE, LEA/quality, Speak-to-Chat, multipoint/source, gestures/QuickAccess,
  wearing/power-off, legacy VPT/position/optimizer, voice/assistant and bounded safe-listening.
  Daily Table1 EQ/ClearBass/ambient slice implemented with capability discovery, native details,
  Table2 separation and ACK-queue rejection semantics. GATT and remaining Sony functions are open.
  DSEE, connection quality and Speak-to-Chat now have version/inquiry-qualified reports, typed
  requests and native details, with independent protocol review. V2 multipoint now discovers a
  unique GS semantic slot and serializes local confirmation transactions; native alerts expire
  through a single guarded refresh. V1 voice guidance and VPT/sound-position controls now use
  discovered functions and exact Table1/Table2 reports with native editors. Hardware acceptance
  and remaining functions stay open. V1 optimizer discovery now annotates the existing acknowledged
  vendor-app handoff, following the approved rare-calibration strategy; no inline optimizer claim.
- [ ] C5 ROSE: include RoseLink model/feature differences, prioritizing the user's ROSE Coral ANC
  earclip and its independently verified configuration/transport/control semantics; shared UUID
  does not establish compatibility with BudsFeel. Also cover i7/Ceramics U identities distinct from existing Ultra; model-qualified six-mode,
  ANC/transparency depth, EQ, game, find, gain, prompts and touch; preserve i7/MK2 restrictions.
  i7/U exact RFCOMM identity and model-qualified daily settings now implemented; i7/MK2 game,
  U six noise values/EQ/depth/tone settings, strict opaque-field parsing and native details.
  Other RoseLink model families and additional commands remain open.
  Twenty-four RS Common v2 configurations now have exact manufacturer-prefix refinement, bounded
  same-address discovery, connection-scoped identity retention, model-qualified daily controls
  and native cards/details. Bluetooth address correspondence still requires device validation.
  The eight remaining legacy F8/RS Common/ZT find configurations now have bounded source-specific
  actions and truthful local-write intent; the earlier twelve-row find backlog is reconciled.
- [ ] C6 NiceHCK: OriG six ANC/seven EQ, independent wind toggle, game/latency, dual/wearing,
  codec and firmware-qualified mappings, readback and native UI.
  Daily controls and native details implemented, including exact firmware gating; codec writes
  still lack reliable state readback in the reference. No fabricated codec confirmation.
- [x] C7 QCY: optional low-latency/sleep/EQ/PEQ/LDAC/dual/dynamic-EQ/spatial/adaptive-volume,
  additional state sources and strict value/outer-frame validation; preserve targeted endpoint scan.
  Daily-control slice implemented with typed contracts, report gates, native card/details,
  independent review and regression tests; hardware validation remains pending.
- [x] C8 vivo/iQOO: TWS3e double/hold/noise-cycle/DeepX/wear; preserve per-model GAIA differences
  and require separate evidence before family expansion of advanced commands.
  Exact TWS 3e slice implemented from raw RFCOMM JSON reports, including native card/details,
  independent review and regression tests. Call-gesture settings remain unavailable because the
  reference lacks reliable readback semantics; hardware validation remains pending.
- [ ] C9 MOONDROP: Pudding five modes/gain/LED and dialect-specific framing/readback; Robin and
  MIRAGE compatibility; ZZZ pure battery; investigate the 52 catalog records/aliases by protocol
  family, retaining explicit unimplemented protocol gaps until actually filled.
  Pudding's APK dialect now has bounded framing, exact-report dialect admission, five noise
  values, gain and LED with native details; battery alone cannot select a dialect. A one-shot
  handshake fallback uses existing adapter effects. Catalog-family expansion remains open.
  The official BT catalog's unbranded exact names now reach standard family fallback without
  inventing private protocol compatibility from chip labels; existing exact protocols win.
- [ ] C10 Bose: additional product IDs/Frames candidates; CNC levels/EQ/spatial/profiles/buttons,
  sidetone/multipoint/auto-pause/answer/prompts/rename with per-product dialect and readback.
  First daily slice implemented: three-band EQ, prompts, multipoint, model-qualified auto-pause,
  direct CNC and QC35 ANR low, preserving product-specific native slots. Sidetone/auto-answer,
  buttons, 4082 spatial/wind and qualified rename are implemented; 4062 now inherits its explicitly
  sourced call/buttons/spatial/wind contracts. Captured longer daily STATUS payloads are accepted.
  Remaining product/profile gaps stay open.
- [ ] C11 Samsung: original Buds/Buds+ framing and identities; expand existing 11-model controls
  to full confirmed per-model ambient/ANC/EQ/gesture/game/find/fit/spatial/device settings.
  Firmware/recovery/diagnostic operations are explicit separate user workflows, never discovery.
  Original Buds/Plus profiles and daily EQ/ambient/gesture/find controls implemented. Modern
  custom EQ separates transmission intent from confirmed mode; no automatic curve replay.
  Source TODO layouts and enum-only game commands remain unresolved, not silently inferred.
  Buds2 Pro conversation timeout now has strict 5/10/15-second status/ACK qualification and UI.
- [ ] C12 Technics: qualified AZ/A800 candidates, ANC/ambient levels and adaptive state/readback;
  do not advertise upstream's unimplemented game method as working support.
  AZ80 ANC/ambient levels now use confirmed 000A reports and 000B writes retaining the latest
  other level, with mode/session guards and native detail sliders. Adaptive, A800-specific and
  unimplemented upstream game behavior remain separate evidence gaps.
- [x] C13 Existing StarRing/Honor/Edifier and standard adapters: preserve all functions while
  moving to the complete native presentation architecture; retain pending final-device evidence.
  Existing native ANC hosts remain; Honor depth now has a guarded native detail entry. Huawei
  level-only reports open their implemented selector, and four existing Samsung settings reach
  daily details. Independent review and code tests pass; changed surfaces still need device feedback.

## D. Complete Android-side experience

- [ ] D1 Notifications/lockscreen/widgets, connection presentation duration/theme/layout,
  battery speech, connection-volume presets, wearing playback behavior and action remapping.
  Desktop widgets implemented with authenticated event-driven battery projection, historical timestamps,
  per-widget selection and native Settings navigation. Connection-volume presets and bounded
  connection/low-battery speech are also implemented; remaining D1 features stay open.
- [ ] D2 Distinguish phone AudioEffect from on-earbud EQ; add session-aware audio controls.
  The [phone-DSP design](../specs/2026-10-29-phone-audio-effects-design.md) records actual AndPods
  session0 behavior, Android anonymization and constructor-time engine configuration. Implementation
  remains open; this source/design audit does not count as a working audio effect.
- [ ] D3 Last-seen location/RSSI proximity/media ringing and actual device find commands with
  precise capability labels; do not imply cloud offline finding or UWB from approximate RSSI.
- [ ] D4 Head tracking/advanced editors/calibration use explicit bounded sessions, cleanup,
  supported hardware and meaningful physical validation; avoid permanent eager polling.

## E. Release, review and completion audit

- [ ] E1 Update exact compatibility/capability/evidence matrices, controller catalog, notices,
  native interface documentation, README/installation/troubleshooting and CHANGELOG.
- [ ] E2 Run `testDebugUnitTest :protocol-test:assembleDebug :system-module:lintRelease
  :system-module:assembleRelease`, document validation, focused frame/control/session/native-host
  regressions. Review privacy, permissions, signing and upgrade compatibility of the final artifact.
- [ ] E3 Arrange user-authorized final device checks for owned AirPods5 wireless and ROSE Coral:
  native controls, reconnect, contention, controls/readback and actual behavior. Other models use
  code review/unit tests for delivery, with explicit hardware-unverified labels and post-release
  issue/later-device follow-up. Preserve rollback; all reboots remain manual unless instructed.
- [ ] E4 Prepare a reviewable PR with original contributor history, scope and validation limits;
  keep Draft while required final device validation is pending; no closing keywords for open
  device contributions pending their final changed-version revalidation.
- [ ] E5 If upstream explicitly rejects, continue on user's fork with documented upgrade path.
- [ ] E6 Audit every requirement above and report items against authoritative final state.
  Mark the goal complete only when the full requested deliverable and verification are achieved.

## Execution log

- 2026-10-28: User activated full-development goal. Report copied into isolated feature worktree;
  PR99/95/96 HEADs refreshed, original commits fetched. Baseline tests started before integrations.
- 2026-10-28: Baseline passed 522 tests; PR99/95/96 merged with original contributor ancestry
  retained. Merged code passed 587 tests (183 protocol, 294 integration, 110 system-module).
  FloS2 installation rollback fix remains in progress; imported tests do not prove device behavior.
- 2026-10-28: User confirmed architecture preservation, Apple Xiaomi-first / AndPods-first
  fallback, and fresh-connection reevaluation after Bluetooth resource updates restart Bluetooth.
- 2026-10-28: A4 rollback passed before/after-attachment fault regressions and independent spec/
  quality reviews. All 590 tests passed; protocol-test debug APK and unsigned module Release APK
  built. Release lint initially rejected inline test dependency; moved Robolectric into the version
  catalog and reran lint plus system tests successfully. Markdown lint and doc validation passed
  49 files. No device install, signing acceptance or physical headphone validation is claimed.
- Next priority is B4/C1: Apple native-first connection admission and AirPods5 wireless protocol
  path, using the follow-up ROM evidence. A5/A6 remain queued alongside the other brand work;
  no Apple dynamic ownership production change has been made in this foundation milestone.
- 2026-10-28 follow-up: Added user reference HyperPods-X `018765be` and audited its protocol,
  native card and real head-tracking implementation. Its unchanged pure protocol suite passed
  67 tests in an isolated JVM harness; its Android/device behavior has not been independently run.
- 2026-10-28 follow-up: B4's surviving-MiLink ownership-claim replay prerequisite is implemented
  and spec/quality reviewed. Seven hook-level regressions cover fresh/retired tokens, duplicate
  states, initial no-session claims, partial sends, missing context, disconnect and reset.
  All 120 system-module tests, Release lint and Release assembly passed; unchanged protocol and
  integration suites retain 183/294 passing tests (597 total). Native Apple capability probing,
  private-transport admission and new model activation are still pending, so B4 remains open.
- Apple follow-up: typed listening state now preserves ADAPTIVE, with session-qualified mode
  admission and device-report-only confirmation. Its native CardAdapter reuses original/select-card
  native item classes and the existing transactional replacement helper shared with FloS2. Runtime
  Apple registry/reservation remains unchanged until transport admission is complete.
- Native bootstrap follow-up: the actual native onDeviceConnected feature-gate result can provide
  connection-scoped NATIVE_DECLINED even with healthy empty native type. This supersedes mandatory
  product-type discovery before fallback, avoiding a circular dependency. Arbitrary provider false
  remains UNKNOWN. Implementation plan: [Apple native admission](2026-10-28-apple-native-admission.md).
- Apple listening/card milestone verification: protocol 186, integration 303, system-module 127
  tests passed (616 total, no failures/errors/skips); protocol-test debug assembly, system Release
  lint and unsigned Release assembly passed. Both integration and native-card slices passed
  independent spec and quality review. All 56 Markdown files and doc consistency checks passed.
  These checks do not validate live Apple activation, physical controls or actual MiLink rendering.
- Apple native-admission milestone: actual scoped Xiaomi connection decisions now gate the
  Apple private transport. Strong headset/AAP evidence, healthy native decline, current producer,
  physical connection and nonce are required. Native support or unknown evidence preserves the
  system path. MiLink presentation ownership now follows the same physical scope independently
  of transport permission, including Standard fallback and stale-result rejection.
- Milestone verification: 650 tests passed (186 protocol, 305 integration, 159 system-module),
  with zero failures/errors/skips. Protocol-test debug assembly, system Release lint and unsigned
  Release assembly passed. Independent spec and quality reviews approved the final fixes.
  Markdown lint and documentation validation passed 57 files. Installed Xiaomi Bluetooth APK
  hash and actual DEX discovery prerequisites were checked read-only on nezha; no runtime hook,
  rendered native card, physical control or device acceptance is claimed.
- B4/C1 remain open: pre-observer/native-decision timing and media-only unknown admission,
  strict device identity/capability discovery, advanced Apple controls and physical acceptance
  remain. Next protocol work follows [AndPods identity evidence](../../research/source-audits/apple-device-identity-followup.md).
  Other brands, complete native Settings integration and release/PR acceptance remain queued.
- Apple DeviceInfo milestone: strict length-delimited parsing now consumes opaque fields without
  interpreting embedded protocol prefixes as listening reports. Only bounded model/firmware fields
  survive; exact source-declared adaptive candidates plus live session reports work in either order.
  Identity replacement and reset remove inferred capabilities without inventing confirmed mode.
  Full verification passed 662 tests (195 protocol, 308 integration, 159 system-module), protocol-test
  Debug assembly, Release lint and unsigned Release assembly. Specification and quality reviews
  approved; no hardware acceptance is claimed. See [slice plan](2026-10-28-apple-device-information.md).
- Next Apple settings evidence is recorded in
  [the settings audit](../../research/source-audits/apple-settings-followup.md): preserve four-byte
  AACP values, distinguish actual reports from optimistic UI state, and keep ATT loud-sound controls
  separate from AACP one-bud ANC. Advanced settings and native Settings presentation remain pending.
- Apple advanced-settings backend milestone: fourteen typed controls now require their own
  valid session reports, preserve unowned payload bytes, confirm via device reports and reset
  session evidence. Valid settings-only reports establish protocol readiness without listening
  or private battery admission. Full verification passed 676 tests (198 protocol, 319 integration,
  159 system-module), protocol-test Debug build, Release lint and unsigned assembly; independent
  spec/quality reviews approved. See [backend plan](2026-10-28-apple-advanced-controls.md).
  Actual native advanced Settings presentation remains outstanding. Captured plugin research
  identified JavaActivity/MiuiAirpodsFragment entry and official connect side effects; the outer
  Settings activity/plugin-loading contract is under read-only investigation. No device install,
  scope change, restart or UI action has been performed.
- Apple MiLink details milestone: fourteen settings now use the existing native card/details
  extension path, including a settings-only carrier without genuine noise capability. Popup
  epochs, latest-state checks, cleanup and host restoration are covered. All 691 tests passed;
  protocol-test Debug assembly, system Release lint and unsigned assembly passed after resolving
  three locale-formatting lint errors. Independent reviews approved. See
  [MiLink details plan](2026-10-28-apple-milink-details.md). Real host/device acceptance remains open.
- User clarified their native-UI question was informational, not a cancellation or direction change.
  Existing HyperEars native extension patterns remain first choice; Settings research is retained
  as evidence for a concrete future gap rather than an automatic replacement architecture.
- User added explicit RoseLink analysis and all missing model/features to scope, with ROSE Coral
  ANC earclip first. Installed RoseLink 3.8.2 has an actual CoralDeviceConfig in rs_common_v2;
  passive UI confirms ANC. Independent diagnostics identify unstable private RFCOMM while current
  A2DP playback remains connected, with vendor-app yield disabled. Endpoint reuse and a candidate
  match in installed HyperEars do not yet establish compatible grammar or prove contention cause.
  Source/AOT analysis continues; no device control, settings change, install or restart performed.
- User explicitly confirmed delivery tiers: complete AndPods/AirPods5 and RoseLink/Coral paths,
  validate owned hardware, then reuse those patterns for their other models; source ports require
  code review and unit tests initially. Do not demand unavailable hardware or leave implemented
  models indefinitely incomplete for that reason. Publish evidence labels and iterate from issues.
- Combined Coral/Apple implementation checkpoint: Coral normal controls, audio selection and
  firmware display are wired to existing native cards/details. Apple reported wear/activity,
  long-press, cycle and EQ are wired; wear playback and bounded conversation shaping now run in
  the existing authenticated Xiaomi host. Review found and fixed cached-START rearming and dead
  producer admission; direct runtime tests cover callback/resource cleanup and ownership changes.
  Combined unit suites passed (219 protocol, 340 integration, 219 system-module); Release lint
  and unsigned assembly passed after local text-formatting fixes. Documentation lint and validator
  passed for 69 files. This is code-level evidence, not actual earbud acceptance.
- Coral AB OTA image/transfer engine is implemented with 12 protocol tests; exclusive transport
  execution, metadata retrieval, upgrade UI and device reconnect verification remain to connect.
  Apple BLE/bootstrap/case, host gesture interception and spatial audio remain outstanding;
  the broader source-port and model-expansion inventory remains in scope.

## Revised delivery priorities: user clarification on rare features

The user clarified that comprehensive integration does not require implementing every rare
vendor workflow. Complex firmware updating used only occasionally may use detection, explicit
vendor-app navigation and private-channel handoff. This supersedes earlier full-OTA expectations.

- P0: reliable daily Coral/Apple use: identity/native admission, cards, battery/charging,
  noise/listening controls, wear/media behavior, common settings and control-app coexistence.
- P1: Apple case-open/authenticated BLE behavior, gestures and spatial/audio features; extend
  source-backed model families and reviewed open-source ports without requiring unavailable hardware.
- P2: lower-frequency fine tuning and convenience features, using existing UI/adapter patterns.
- P3: firmware updating and similarly complex maintenance tasks: detect availability, guide the
  user to the vendor app and explicitly yield the private channel. Do not build a parallel flashing
  workflow merely to satisfy a feature-count target. Reconsider only if inexpensive or nearly done.

Coral transfer engine is preserved as protocol research. The uncommitted exclusive-flash
executor and Binder/UI workflow are being withdrawn; update metadata checking remains useful.
No phone settings or installed application state change is implied by this development decision.

Current sequencing: finish the already-started Coral workflow without interruption. The user
explicitly authorized parallel Apple work; keep separate file ownership and serialized Gradle
verification. Coral is not abandoned or restarted while Apple work progresses independently.

The user explicitly requires Xiaomi-style connection popups for adapted headphones, in addition
to native settings cards. Existing SMART/native connection presentation remains the shared path.
A new regression verifies Coral's real decoded L/R/case battery projection and once-per-connection
presentation; Apple confirmed case edges also use the native path. Physical rendering still needs
hardware verification; a passing projection/gate test is not an actual displayed popup.

Apple/Coral code-scope checkpoint: connected and disconnected Apple case discovery, phone
long-press actions and native spatial/head tracking are wired. Shared cleanup flushes bounded
ownership restoration before close; per-address predecessor completion prevents overlap with a
new connection. Full suites pass 849 tests; protocol-test Debug and module Release lint/assembly
pass. No install/phone modification/physical acceptance. Other brand/model migration remains
outstanding; this checkpoint does not complete the comprehensive goal.

### Latest verified checkpoint

The Huawei EQ CRUD / legacy ROSE find / Sony optimizer handoff checkpoint passes 1,501 tests
(359 protocol, 588 integration, 554 system-module), zero failures/errors/skips, Release lint and
assembly, and protocol-test Debug assembly. Log: `.analysis/verification/eq-crud-legacy-find-final.log`.
Documentation validation covers94 Markdown files and34 control applications; Markdown lint covers93.
No device operations occurred.

Huawei6i/Pro3/Pro5/FreeLace Pro2 reuse the existing editor for explicit creation, full-record
selection, curve edits and confirmed deletion of the current custom slot. A full inventory and
monotonic revision pin every request; partial/invalid inventory revokes edits, old request classes
cannot bypass the managed path, and only successful writes arm pending state. Full readback alone
clears it. Source review removed unjustified mandatory optional fields and zero-only name padding.
A provider exception on delete-cancel is now guarded and regression-tested.

Eight exact older ROSE configurations now have source-specific find actions, including source-
qualified i5 without name-only promotion. F8/RS Common lack a state query; the UI distinguishes
submission, successful local write and unconfirmed device behavior. ZT i5 has one proven stopped
notification but no fabricated whole-state report. Local request leases and internal deadline
generations remain separate; current UI/model/token/ownership is checked before stop. Bluetooth-
owned thirty-second cleanup covers host UI death. Family literals, all-model qualification and
old/no-longer-owned leases have tests. Two old tests were updated for the additional valid find
availability projection while retaining their noise/touch/malformed-report assertions.

Sony V1 actual function81 hex annotates the single existing controller row for optimizer use in
the official app. No start/calibration/status frames are invented. The original handoff waits for
fresh matching ownership confirmation before launch; removed functionality invalidates old
annotated callbacks. V2/Table2/decimal81 and reset are covered by adapter and native-policy tests.

Phone-side audio effects remain explicitly unimplemented after source audit. Other open family
features, Android experience tasks, final capability reconciliation, current owned-device feedback
and Draft-PR delivery remain in the full goal. Source and unit checks do not replace hardware
acceptance or authorize unrequested device operations.
