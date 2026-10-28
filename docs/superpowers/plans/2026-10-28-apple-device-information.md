# Apple device information and model capability implementation plan

> Continue the approved full integration plan using subagent-driven-development, test-driven
> implementation and independent specification then quality review.

## Goal and boundaries

Complete the next Apple C1 identity/capability slice without changing native ownership or the
Adapter / ProtocolSession / WireCodec architecture. AndPods 4.3.8 is the primary structural
reference; the pinned LibrePods model registry corroborates adaptive candidates. Source facts
and exact model numbers are documented in the
[identity audit](../../research/source-audits/apple-device-identity-followup.md).

Parse opcode 0x001d with its explicit unsigned LE16 length at offset 7; total size is 9 plus
that length. Offset 11 starts eleven NUL-terminated strings, then two opaque 17-byte segments,
then two NUL-terminated strings and exact end. Preserve empty fields and strictly validate UTF-8;
skipped header fields have unknown semantics. Read and discard private serials, opaque identifiers
and pairing timestamps. Expose only the fields needed by the protocol session.

The exact modelIdentifier field selects the candidate adaptive range. A valid listening report
in the same session is also required before offering the control. Either arrival order works.
Unknown models retain existing observed-Adaptive support. Editable names never determine model;
reset clears all identity and listening evidence. A changed model report replaces model-derived
eligibility; direct Adaptive evidence remains valid until reset. DeviceInfo alone opens no card,
battery source or listening command. Existing Xiaomi-first admission remains independent.

## Implementation and verification

- [x] Add failing protocol tests for explicit framing, all fragment boundaries, empty fields,
  concatenated frames, embedded protocol prefixes in opaque bytes, malformed length/UTF-8,
  missing terminators, extra trailer and reset. Keep pending input bounded by the 16-bit format.
- [x] Implement strict minimal DeviceInformation projection in AppleAapWireCodec.
- [x] Add failing integration tests for exact source models, spoofed display names, unknown and
  non-adaptive models, both report orders, changed identity, reset and confirmed-state controls.
- [x] Implement model candidate plus live listening evidence in AppleAapProtocolSession using
  the existing typed AppleListeningModeFeatureState and control contract.
- [x] Review specification compliance, then code quality; address concrete issues and rerun
  affected tests. Run full unit suites, protocol-test debug build and system Release lint/build.
- [x] Update AAP documentation, compatibility evidence, attribution, changelog and umbrella log;
  run Markdown lint, documentation validation and diff checks before committing.

## Acceptance limits

This slice uses synthetic protocol regression fixtures and pinned source declarations. It does
not establish live AirPods 5 behavior, firmware compatibility or rendered native controls.
No device installation, Bluetooth restart, settings change or remote PR action is included.
The complete integration goal remains active after this milestone.

## Verification record

Meaningful failing regressions preceded implementation: three protocol and two integration
failures, including a false noise report from opaque DeviceInfo bytes. Final full verification
passed 662 tests: protocol 195, integration 308, system-module 159; zero failures/errors/skips.
Protocol-test Debug assembly, system Release lint and unsigned Release assembly passed.
Independent specification and quality reviewers found no blocking issue. Logs remain ignored
under `.analysis/verification/apple-deviceinfo/`. Final device acceptance remains pending.

The decoder deliberately trusts a valid length field until the full frame arrives. A corrupt
large declaration can delay later reports until its declared bytes arrive or the session resets;
scanning opaque payload for a new header would reintroduce false status reports. Buffering stays
bounded. Source model declarations do not guarantee every firmware accepts Adaptive.
