# Apple advanced controls implementation plan

> Continue the approved comprehensive integration goal through subagent-driven-development,
> failing regression tests, implementation and independent specification then quality review.

## Design and source constraints

Retain the existing WireCodec / ProtocolSession / Adapter / serializable feature/request path.
The [settings source audit](../../research/source-audits/apple-settings-followup.md) defines
primary AndPods behavior and corroborating HyperPods-X / LibrePods controls. Generic AACP
opcode 0x09 frames are eleven bytes, with one identifier and four unsigned value bytes.
Frame the complete report before interpreting values; retain listening 0x0d behavior.

Publish typed optional settings, not protocol byte maps. Private session state retains the
four received bytes needed to preserve fields not owned by a command. Requests use stable
namespaced serialization and existing DEVICE_REPORT confirmation. No optimistic state,
new polling, reconnect-for-readback loop, per-control GET invention or native admission change.

Implement microphone 0x01; one-bud ANC 0x1b; conversation 0x28; personalized volume 0x26;
wearing configuration 0x0a; timing levels 0x17/0x18/0x23; volume swipe 0x25; adaptive
strength 0x2e; and call mapping 0x24. Confirm active source UI before including allow-off
0x34, chime volume 0x1f and sleep pause 0x35. Toggles use 1/2, timing uses neutral levels
0/1/2, microphone uses automatic/right/left 0/1/2, strength uses unsigned 0..100, and
chime uses 15..100. Call mapping owns byte 1 (2 flipped / 3 default), preserving the other
three bytes. Scalar writes preserve other fields except adaptive strength, whose source
encoder explicitly zero-pads. Adaptive strength additionally requires confirmed Adaptive mode.

A valid current-session report is required for each control. Invalid and unknown values
must never be treated as a valid false/default. Reset discards settings and all internal
report bytes. Configuration is separate from phone-side behavior: enabling conversation,
wearing or sleep settings does not prove host volume ducking or playback automation.
Loud-sound reduction belongs to a separately evidenced ATT path, not AACP one-bud ANC.

## Tasks and verification

- [x] Add failing codec tests for complete framing, fragmentation, concatenation, unsigned
  four-byte preservation, unknown identifiers and existing listening/battery/DeviceInfo behavior.
- [x] Add typed settings, requests and session parsing/encoding within existing contracts;
  add failing admission, value-domain, reset, serialization, readback and no-optimism tests.
- [x] Verify multi-byte preservation and adaptive-strength mode precondition at both request
  admission and encoding boundaries. Record policy for invalid subsequent known reports.
- [x] Run focused suites, independent specification review and independent quality review.
- [x] Run full tests, protocol-test Debug assembly, system Release lint and unsigned assembly;
  update protocol documentation, compatibility/attribution/changelog and validate documents.
- [x] Commit reviewed backend milestone; native advanced settings presentation remains queued. Backend
  controls alone do not complete the user's native Settings requirement or the Apple work.

## Remaining end-to-end acceptance

Native rows must consume typed state and send typed requests through existing lifecycle and
ownership checks. Research the actual host contracts before implementing the presentation.
Rendered native workflow, headphone readbacks, firmware behavior and final device validation
remain separate from JVM tests and builds. No installation, restart or remote PR action here.

## Verification record

The initial running regression failed because a legitimate microphone response did not establish
settings evidence. Final full verification passed 676 tests: protocol 198, integration 319,
system-module 159, with zero failures/errors/skips. Protocol-test Debug assembly, system Release
lint and unsigned Release assembly passed. Specification and quality reviews approved the final
backend. Logs remain ignored under `.analysis/verification/apple-settings/`.

A malformed subsequent report retains the last valid setting and its corresponding private bytes,
matching existing listening behavior; it never establishes new support or false/default state.
Valid supported settings can complete protocol handshake without creating battery/listening/card
capabilities. Fourteen settings are implemented; physical effects and native advanced UI are not
claimed complete. Document validation covers 61 Markdown files and the 30-app control catalog.
