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
- [ ] Complete independent specification and quality reviews; run full tests, protocol-test
  Debug assembly, Release lint/assembly, documentation checks and commit the reviewable change.
- [ ] Later authorized physical acceptance must verify actual card layout, controls/readbacks,
  charging/unknown components, reconnect and vendor-app coexistence on the final changed version.

## Remaining full-scope work

Audio selection now has exact Coral configuration/callback/encoder evidence and is implemented
with report gating and inline confirmation; firmware display is also implemented. AB OTA remains
an explicit remaining Coral feature: its image/transfer engine now has 12 passing protocol tests,
while exclusive transport execution, metadata retrieval, upgrade UI and reconnect verification
remain to connect. The combined code checkpoint passes 778 unit tests and Release lint/assembly.
Firmware flashing is a separate explicit workflow, not initialization/discovery. Other RoseLink
model/configuration differences stay in the full inventory. This milestone is not completion of
all Coral behavior, all Rose models or the comprehensive HyperEars goal.
