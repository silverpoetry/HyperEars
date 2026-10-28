# Open PR audit — HyperEars

Raw JSON, diffs and extracted `95/96/90/92` source paths referenced below are located in
`.analysis/pr-audit/` at the repository root, not alongside this published report copy.

Audit snapshot 2026-10-28; current local main `26d3151ee1c1257b51fa68d679ce08a66336b983`. Read CONTRIBUTING.md and used live GitHub PR bodies, comments, commits, file lists, diffs, check rollups, inline-review comments and issue98. No PR comments/status changes, merges, branch changes, production edits or device commands. No builds executed in this report-only pass. Raw JSON/diffs saved beside this report; new-file patch content extracted into95/96/90/92 for review.

## Overall result

Five open PRs are THREE functional workstreams, not five independent features:

1. #90 original -> #96 maintenance: Dreame Flo S2 / TheAstro OpenPro S2 protocol, battery and four sound effects.
2. #92 original -> #95 maintenance: MOONDROP ZZZ-ANGELS-OWS battery-only model.
3. #99: shared native popup startup-state race fix, applicable across module-managed headsets.

Preserving originals matters: independently checked `git merge-base --is-ancestor` exits0 for original#92 commit132c91e -> maintenance#95 b333f1fe, and original#90 head26078c59 -> maintenance#96 eeca05ba. Their contributor commits are already preserved; do not implement original and maintenance as competing adapters or squash away attribution casually.

Important new evidence: #95 body still says awaiting revalidation, but original#92 has an author report dated2026-10-27T10:37:08Z explicitly validating the EXACT current maintenance commit b333f1fe and matching artifact SHA. #96 has no equivalent final-version author response. All five inline review-comment lists are empty; no additional submitted reviews were present.

## #92 / #95 — ZZZ-ANGELS-OWS

- Original <https://github.com/silverpoetry/HyperEars/pull/92> ; head `132c91e624d74115436931d9547e1e27ca1269fc`, non-Draft, one contributor commit.
- Maintenance <https://github.com/silverpoetry/HyperEars/pull/95> ; head `b333f1fe9aff25db5bbb149768b9b037ffca18ec`, Draft; retains original plus maintenance commits.
- Product: MOONDROP x Zenless Zone Zero ZZZ-ANGELS-OWS clip/open-ear model; normalized accepted names `zzzangelsows`, `moondropzzzangelsows`; explicitly rejects LEA identity, native-owned devices and non-headsets. No noise controls; do not infer ANC from MOONDROP family.
- Transport: standard SPP UUID only as endpoint, never identity. GAIA family FF/type/reserved/length-u16le/command3/payload framing. API request `0A 03 00`; successful response `0A 83 00` with4 parameters, status0/protocol1..4. Query left/right battery `1D 1A 01 01 02`; accepts observed response `1D 1B 01`.
- Useful implementation: own session decoder/reset; initial handshake then telemetry; legal battery response gates private capability; component presence wraps nullable reading, so absent!=explicit-unknown; valid all-unknown frames clear previous values; invalid percent/duplicate IDs rejected; typed BatteryFeatureState; existing ControlAppCatalog.moondrop ownership; model-local bounded first-report policy at500/800/1200/1600ms, with cancel once complete; no steady polling or guessed push dialect.
- Original shortcomings corrected in#95: arbitrary zzz+angel substring match; null merging retained stale battery; code/document disagreed between push1D8101 and1D1A81; shared-family first-report policy broadened unrelated-model scope. Maintenance keeps only proven query response and isolates policy.
- Source pointers:95/integration/.../MoondropZzzAngelsAdapter.kt:46-66,99-159; MoondropZzzAngelsBatteryPolicy.kt:14-40;95/protocol/.../MoondropZzzAngelsWireCodec.kt:93-117. Original differences independently compared using local git objects.

### Final-version hardware evidence now exists

Author YifePlayte reports `HyperEars-v3.0.1-b333f1fe-signed`, matching SHA prefix/suffix of `275eb0c59fbab291a0181619d6a7d0ce66a4646785e69ea3632aa3b582135c67`; firmware1.2.0; HyperOS4.0.5.0.XPMCNXM/Android17; MiLink18.2.2.2.2610091932; MOONDROP Link2.26.2c-261008ai.

Reported pass: first connection/API handshake/two-ear battery; Bluetooth process restart/reconnect; vendor-App yield removes private features, fallback standard.battery, resume reconnects and re-handshakes; explicit00 clears previous reading after bounded startup; valid all-unknown frames clear both ears; subsequent per-component recovery works.48 query responses only used1D1B01; no suspected push variants observed.

Firmware limitation identified by author:00 is observed when a component is in-case at connection establishment, NOT a reliable live put-in-case event. With one bud staying connected, the in-case other bud continues reporting its last battery, sometimes for40minutes; official MOONDROP Link behaves identically. Do NOT fabricate in-case/offline status or infer it from unchanged percent. Record this limit and avoid heuristic expiration without a separate decision.

CI rollup at audited maintenance head: `Test, lint and build` SUCCESS. Signed artifact and SHA are described in PR; this pass did not download/reverify APK certificate. Author report is specific final-version evidence, not our own device run.

Still to incorporate after approval: update stale PR/docs revalidation language (95/docs/moondrop-zzz-angels-protocol.md:28-29), add firmware-freeze semantics, resolve changelog conflict against current main. Full actual case-battery support remains unobserved: codec accepts03 as family-compatible extension but hardware supported-component response is only01/02; report feature as left/right only. Codec comment still says two/three pairs while actual parser permits one-to-three; docs correctly state1..3.

## #90 / #96 — FloS2 / TheAstro

- Original <https://github.com/silverpoetry/HyperEars/pull/90> ; head `26078c591ba240a25b7c6b83f5d2b1a723a9c9d5`, four preserved contributor commits9871d5f/491e24c/da55213/26078c5.
- Maintenance <https://github.com/silverpoetry/HyperEars/pull/96> ; head `eeca05ba50fd6251f393fce11801688251ccae5c`, Draft, merges contributor history then11ec239/eeca05b fixes.
- Concrete device names: OpenPro S2, TheAstro OpenPro S2, Flo S2, Dreame Flo S2. Same-hardware relationship is contributor evidence; actual hardware report specifically OpenPro S2. HaptiFit is intentionally excluded pending independent model/protocol evidence. AC00 alone is not candidate identity.
- Transport BLE GATT AC00 service/AC01 write-without-response/AC02 notify. Frame `AE|primary|sub|length-u8|payload|CRC16-CCITT-FALSE LE`, fragmented/coalesced input and bad-CRC resync. Full-state66/FF exact72-byte payload; aggregate batteryu16le offset25; sound effectbyte49. Battery push66/E0 exact2-byte payload. Switch67/03 accepts0standard/1swimming/2vocals/3enhanced;67/83 ACK carries NO selected-value evidence, state readback determines confirmation.
- Actual telemetry granularity:105 CRC-checked samples60/70/80/90, device-side10% quantization. Single aggregate battery, no charging telemetry (device off while charging). No fabricated two-ear/box values. Native selector represents sound effects, not ANC.
- Good code: dedicated FloS2ControlRequest and FloS2SoundEffectFeatureState with serialized namespaces; readback DEVICE_REPORT policy; battery-only evidence cannot open sound effects; resets remove vendor feature; no regular polling; module-control ownership+connected private channel+handshake+feature gate clicks. Four native item instances with unique fourth view ID; module resource IDs retain release resources; optional native title and restoration.
- Maintenance corrections: exact known full-name candidate; excludes unproven HaptiFit/UUID-only match; closes stale control window while yielding/reconnecting; local Resources vs static Context/name lookup; preserves selected state drawable structure and release shrinker reachability; restores native row/title on unbind.
- Unabsorbed current-main capability: this entire family, codec and four-option sound-effect presentation. Codec additionally parses firmware/hardware/MCU version/device name/serial from72-byte status, but current session projects ONLY battery and sound effect. These information fields could be separately modeled for future native detail page; sport recording/file transfer remain intentionally outside headphone feature scope.
- Source pointers:96/integration/.../FloS2EarbudAdapter.kt:14-47,70-96; FloS2ProtocolSession.kt:12-58;96/protocol/.../FloS2WireCodec.kt:124-164;96/system-module/.../FloS2SoundEffectMiLinkCardAdapter.kt:39-62,164-201,276-292.

### Evidence and unfinished boundaries

Original author reports Debug hardware pass on HyperOS4.0.2.0 + OpenPro S2: identify, battery, four effect changes/readback, selected icons. Original did not locally run release lint/build. Maintenance CI `Test, lint and build` SUCCESS; PR records signed Release SHA `cfe31baeda80e0291f4691ed6b8b506d53c0077de2f4b7b566b1e1ae877a724c` and resource checks. First maintenance CI lint issue was fixed in eeca05b.

Live #90 comments contain maintainer request for final maintenance revalidation but NO author reply. Thus #96 is still genuinely pending hardware revalidation: Release icons, each effect and audible/readback result, selection, close/reopen, reconnect and title-less/native layout variants. Keep both PRs open and#96 Draft per CONTRIBUTING; other historical exceptions do not apply.

Official companion App is mentioned as protocol source, but its actual name/package/version is still missing. Empty controlApps means automatic vendor-App backoff is not implemented. Original text says no vendor control App despite sourcing one; maintenance accurately records missing identity. Need verified catalog/queries/scope/navigation/ownership wiring, not guessed package.

Additional review concern (not hardware-reproduced): NativeAncHost.install removes originals before adding four replacements (lines164-178), without transactional try/finally restore. If a host-layout exception occurs during addView, binding has not yet returned and therefore cannot be unbound by caller (MiLinkHeadsetDetailExtension.kt:170). A shared/native selector abstraction should restore on partial installation failure. Current source tests cover pure card policy, not Android view rollback or title-less layout execution; do not present those as UI acceptance evidence.

## #99 — Shared native popup startup race

- <https://github.com/silverpoetry/HyperEars/pull/99> ; head `f36053ed64e965b0cf160a4262911bd2b9baf4ca`, base current26d3151, non-Draft, one contributor commit. Five changed files: three production presentation sources, tests, changelog. No model adaptation.
- Root cause matches current code: launch Intent captures unknown battery; later battery is accepted before Controller Binding exists; update loop has no controller to update; Binding then consumes stale launch state. Standard fallback Refresh does not republish state and cannot fix race reliably.
- Fix: AirPodsWindowBridge resolves launch request via runtime latest accepted same-connection state before Binding; request becomes single source of current state; NativeWindowRequest.updated enforces address/producer/session/full connection equality, active lifecycle, HyperEars native admission and monotonic revision. Known-ended/official-owned state rejects stale fallback; only genuinely missing consumer cache retains validated launch snapshot. stateArray preserves unknown,0%,component/charging semantics.
- Good reusable boundary: latest accepted state at presentation handoff, independent of vendor protocol/model; no polling, fake battery, new cache or expanded native ownership. This aligns with consolidated native UI work and should be treated as a shared prerequisite rather than Apple product support (AirPodsWindowBridge is reused display implementation).
- Tests: author reports104 system-module tests plus12 new regression cases; key pre-fix regression expected100 but null; covers pre/post binding update, stale producer/session/connection/revision, native ownership, aggregate/components,0/unknown/charging. Live statusCheckRollup empty. Author explicitly did not run full-project testDebugUnitTest/release lint/release assembly for this PR. This pass did not rerun tests.
- Hardware: issue98 andPR99 distinguish earlier package containing identical THREE production source files reportedly fixed by user vs final rebased current-head APK not yet installed. Environment HyperOS4.0.5.0.XBLCNXM Android17 on standard fallback earbud; exact LSPosed/MiLink versions unrecorded. Preserve this distinction; no final-artifact hardware pass claim.
- Source read found no new protocol/model ownership broadening. Final-package validation and shared native popup lifecycle/official-device coexistence remain appropriate verification scope.

## Current-main overlap / integration order considerations

- Independently ran git merge-tree using existing objects only; no worktree/index/branch change. #95 versus26d3151 and#96 versus26d3151 both report ONLY CHANGELOG.md content conflict; #95 docs/control-apps auto-merges. Existing API/registry/card/presentation source between maintenance baseeacd6e1 and current26d3151 is unchanged (git diff --quiet exit0 for inspected framework paths).
- GitHub current metadata marks#95/#96/#90 dirty;#99 and#92 mergeable but unstable. These are merge metadata, not correctness votes. Raw#92 being mechanically mergeable is not a reason to discard its maintenance fixes.
- #95/#96 both touch registry, compatibility/source/user docs and About listing; consolidate registrations intentionally and preserve order, not duplicate originals. #96 also adds card registry entry/resources;#99 touches independent presentation runtime plus shared changelog.
- Suggested report prioritization after user approval: shared popup fix#99; absorb revalidated#95 with author evidence/docs corrected and changelog-only resolution; continue#96 verification/companion-App evidence with exact original commits preserved. This is an implementation recommendation, not authorization to merge.
- Native unified detail UI needs projection for FloS2 four effects and ZZZ battery-only semantics. Neither device PR adds a complete native Settings page; avoid promising that these alone finish consolidated UI work.
