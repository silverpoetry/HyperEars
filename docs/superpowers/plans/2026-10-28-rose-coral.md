# ROSE Coral implementation plan

> Complete a reviewable Coral path through existing codec, adapter, typed control and MiLink
> extension patterns. The full RoseLink and all-brand integration goal remains unchanged.

## Source and accepted scope

The [Coral audit](../../research/source-audits/rose-coral-followup.md) records exact installed
RoseLink 3.8.2 configuration, AOT dispatch, frame grammar, control domains and passive UI/socket
observations. Shared UUID alone is not model or command compatibility. The user explicitly
chose to keep the phone unchanged: this plan permits local development, not device writes,
installation, mode changes, diagnostics settings or restart.

## Protocol and integration

- [x] Add exact ROSE Coral candidate before family matching, requiring standard headset shape
  and excluding native-owned devices. Keep the published UUID and TWS left/right/case topology.
- [x] Implement bounded nonrecursive DD/LTV receive framing and FF transmit framing, exact
  component query, checksum and sequence wrap. Unknown opaque fields cannot invent capabilities.
- [x] Admit ANC/OFF only from valid mode reports; decode independent battery components and
  charging bits, clearing invalid percentages independently rather than losing valid peers.
- [x] Add typed, per-report game/multipoint, bass 0..2, prompt 1..5, touch master and eight-slot
  touch snapshot/actions, plus incremental left/right find state with four proven write targets.
  Keep observed values unchanged on send and use existing notification readback scheduling.
- [x] Reset all capability/state/sequence evidence and keep external-controller behavior intact.
  Register distinct main and advanced-only native presentation IDs without inventing ANC.

## Presentation and verification

- [x] Present ANC/OFF with existing native host items; add advanced controls via existing MiLink
  details patterns, including settings-only sessions, rather than adding another host process.
- [x] Cover all typed settings, eight gesture selectors and find actions with current address,
  session, ownership, handshake and feature checks. Render accepted readback, clean up on every
  detach/rebind/disconnect/ownership loss, and retain stale-dialog rejection.
- [x] Complete independent specification and quality reviews; run full tests, protocol-test
  Debug assembly, Release lint/assembly, documentation checks and commit the reviewable change.
- [ ] Later authorized physical acceptance must verify actual card layout, controls/readbacks,
  charging/unknown components, reconnect and vendor-app coexistence on the final changed version.

## Remaining full-scope work

Audio selection has exact Coral configuration/callback/encoder evidence and is implemented with
report gating and inline confirmation; firmware display is also implemented. The user explicitly
revised low-frequency firmware work: detect updates and hand control to RoseLink instead of
implementing a complete independent flasher. The protocol research engine is retained but has no
runtime flashing entry point.

The details panel now checks official metadata only after a click, cancels on dismissal, rejects
stale session/version results and opens RoseLink only after acknowledged private-channel release.
Explicit handoff does not change the global automatic-yield setting. A failed launch cancels its
own lease; missing App registration expires after 30 seconds; observed process exit restores normal
ownership policy. Process lifetime is not inferred from an Activity leaving the foreground.

Combined verification now passes 802 unit tests (219 protocol, 341 integration, 242 system-module),
Release lint and unsigned assembly. Final handoff review found no concrete code blocker;
outdated full-flasher scope wording was corrected. There is no explicit reclaim button in this
panel; the backend matching-lease release is used by failed launches and bounded recovery.
Physical acceptance remains outstanding; other RoseLink model differences stay in the inventory.
Coral code completion does not mean completion of all Rose models or the comprehensive goal.
