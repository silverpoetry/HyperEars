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
- Research: `docs/research/2026-10-28-hyperears-integration-report.md` and linked inventories/audits.
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

## A. Integration foundation

- [ ] A1 Establish clean baseline tests and build environment in isolated worktree.
- [ ] A2 Preserve and integrate PR #99 `f36053ed`: latest admissible popup snapshot during launch;
  validate `NativeWindowRequestTest`, producer/session/connection/revision/native ownership, true
  zero and unknown batteries. Files: `system-module/.../presentation/{NativeWindowRequest,
  NativePresentationRuntime,AirPodsWindowBridge}.kt`, corresponding tests, CHANGELOG.
- [ ] A3 Preserve PR #95 `b333f1fe` and original #92 ancestry: ZZZ-ANGELS identity, handshake,
  component replacement, unknown-state cleanup and external-controller recovery. Resolve only
  actual conflicts; preserve the original final-package report without applying it to new changes.
- [ ] A4 Preserve PR #96 `eeca05ba` and original #90 ancestry: FloS2/OpenPro S2 aggregate battery
  and four sound effects. Repair partial-install rollback in native card host; add regression tests.
  Final maintenance hardware feedback and companion-app identity remain separate evidence items.
- [ ] A5 Recover #50 W820NB dual-gold BES V1 protocol and exact identity without its text-matched
  selected-state UI workaround. Add codec/adapter tests, origin attribution, native projection.
- [ ] A6 Correct explicit-offline versus absent-component state semantics, beginning with Pudding;
  retain measured ZZZ firmware behavior instead of guessing offline from battery age.

## B. Native presentation through existing extension patterns

- [ ] B1 Inventory how existing CardAdapters and typed feature/request contracts can express each
  required control, including choices/ranges/toggles, availability and confirmed/pending/error state.
  Use existing vendor feature namespaces and control contracts. Do not replace the presentation
  system with a generic descriptor framework by default. Record a specific gap before introducing
  any narrowly scoped additional abstraction or envelope change.
- [ ] B2 Implement native MiLink selectable cards using validated host component contracts,
  variable supported choice counts, native visual state and transactional bind/restore. Replace
  title-row add-ons and brand-specific popups with the shared host where equivalent.
- [ ] B3 Implement Settings plugin discovery/lifecycle and a per-address native details bridge.
  Native symbols from current ROM include `JavaActivity`, `MiuiAirpodsFragment`,
  `MiuiHeadsetFragment`, `AncController`, `MiuiHeadsetAncAdjustView` and native preferences.
  Add Settings scope and manifest/settings/help declarations consistently. No brand spoofing
  outside the bound module-owned context; never call a Xiaomi vendor command for a foreign device.
- [ ] B4 Implement native/headset ownership policy for system-full, system-partial, module and
  external controller states. Permit explicit complete-module control. One private writer per
  device; native admission alone does not prove complete feature support. Preserve normal audio.
- [ ] B5 Route battery, noise, levels, spatial, wearing, gesture, EQ, multipoint, find and device-info
  groups into one consistent native-details hierarchy using existing adapter-selected presentation
  bindings and type-safe requests. Advanced editors use the same host styling.
- [ ] B6 Update existing registered brand/model presentations in place through their current
  registries; remove unsupported controls (including FreeClip false ANC), retain working model
  specializations and preserve standard/headphone/TWS topology.
- [ ] B7 Integrate connection popup/super-island/notifications with authoritative snapshots and
  lifecycle cleanup; verify multi-device, unknown/zero, dark/light, layout and accessibility.
- [ ] B8 Refine optional external-controller coexistence beyond broad process presence where
  evidence permits; include AndPods/LibrePods, regional apps, multi-process and user ownership.

## C. Protocol and model completeness by family

Each family needs its own focused implementation plan/test set when entered; this umbrella ledger
does not treat every source command as universally supported. All items below remain in scope.

- [ ] C1 Apple/Beats: model/device-info and BLE/AACP identity; AirPods5 wireless first complete
  path; expand AAP stream/control decoding; adaptive mode distinct from ANC; wearing, long-press,
  noise-cycle, stem/swipe, conversation/adaptive volume, mic/timing/tone/case/ear settings,
  connected-device events, rename, head tracking and supported hearing/EQ/sensor functions.
  Add AndPods' 31 Apple/Beats model candidates and LibrePods model numbers; preserve HQB separation.
- [ ] C2 OPPO/OnePlus/realme: merge the 141-entry catalog as candidates; product-ID refinement,
  exact aliases, endpoint variants, long frames/fragments, model-index ANC/transparency,
  EQ/custom EQ, game, multipoint, spatial, wearing, prompts, fit and Golden Sound workflows.
  Resolve source length conflicts with fixtures. LE Audio has a separate lifecycle/peer/group
  implementation and actual stereo-playback acceptance, including manual disconnect behavior.
- [ ] C3 Huawei/Honor: union HuaweiPods/OpenFreebuds exact routes and aliases; old HFP plus SPP
  FreeBuds3 paths, Studio/FreeLace topology, firmware-specific ANC/transparency, EQ, gestures,
  spatial, wearing, dual-device, latency, quality, prompts/language and smart functions.
  Preserve Pro4 conflicting evidence and 5i level gap until resolved on matching hardware.
- [ ] C4 Sony: SPP/GATT, V1/V2, Table1/Table2 capability discovery; environment levels/voice,
  EQ/ClearBass, DSEE, LEA/quality, Speak-to-Chat, multipoint/source, gestures/QuickAccess,
  wearing/power-off, legacy VPT/position/optimizer, voice/assistant and bounded safe-listening.
- [ ] C5 ROSE: i7/Ceramics U identities distinct from existing Ultra; model-qualified six-mode,
  ANC/transparency depth, EQ, game, find, gain, prompts and touch; preserve i7/MK2 restrictions.
- [ ] C6 NiceHCK: OriG six ANC/seven EQ, independent wind toggle, game/latency, dual/wearing,
  codec and firmware-qualified mappings, readback and native UI.
- [ ] C7 QCY: optional low-latency/sleep/EQ/PEQ/LDAC/dual/dynamic-EQ/spatial/adaptive-volume,
  additional state sources and strict value/outer-frame validation; preserve targeted endpoint scan.
- [ ] C8 vivo/iQOO: TWS3e double/hold/noise-cycle/DeepX/wear; preserve per-model GAIA differences
  and require separate evidence before family expansion of advanced commands.
- [ ] C9 MOONDROP: Pudding five modes/gain/LED and dialect-specific framing/readback; Robin and
  MIRAGE compatibility; ZZZ pure battery; investigate the 52 catalog records/aliases by protocol
  family, retaining explicit unimplemented protocol gaps until actually filled.
- [ ] C10 Bose: additional product IDs/Frames candidates; CNC levels/EQ/spatial/profiles/buttons,
  sidetone/multipoint/auto-pause/answer/prompts/rename with per-product dialect and readback.
- [ ] C11 Samsung: original Buds/Buds+ framing and identities; expand existing 11-model controls
  to full confirmed per-model ambient/ANC/EQ/gesture/game/find/fit/spatial/device settings.
  Firmware/recovery/diagnostic operations are explicit separate user workflows, never discovery.
- [ ] C12 Technics: qualified AZ/A800 candidates, ANC/ambient levels and adaptive state/readback;
  do not advertise upstream's unimplemented game method as working support.
- [ ] C13 Existing StarRing/Honor/Edifier and standard adapters: preserve all functions while
  moving to the complete native presentation architecture; retain pending final-device evidence.

## D. Complete Android-side experience

- [ ] D1 Notifications/lockscreen/widgets, connection presentation duration/theme/layout,
  battery speech, connection-volume presets, wearing playback behavior and action remapping.
- [ ] D2 Distinguish phone AudioEffect from on-earbud EQ; add session-aware audio controls.
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
- [ ] E3 Arrange user-authorized device acceptance: AirPods5 wireless first, representative other
  protocols/native controls, multiple devices, reconnect, contention, controls/readback and actual
  behavior. Preserve rollback. All reboots remain manual unless explicitly instructed otherwise.
- [ ] E4 Prepare a reviewable PR with original contributor history, scope and validation limits;
  keep Draft while required final device validation is pending; no closing keywords for open
  device contributions pending their final changed-version revalidation.
- [ ] E5 If upstream explicitly rejects, continue on user's fork with documented upgrade path.
- [ ] E6 Audit every requirement above and report items against authoritative final state.
  Mark the goal complete only when the full requested deliverable and verification are achieved.

## Execution log

- 2026-10-28: User activated full-development goal. Report copied into isolated feature worktree;
  PR99/95/96 HEADs refreshed, original commits fetched. Baseline tests started before integrations.
