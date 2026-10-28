# Apple listening mode slice

Preserve Apple listening values 1 (off), 2 (ANC), 3 (transparency), and 4
(adaptive) through vendor typed state and requests, without extending shared
`NoiseMode`. Apple remains excluded from runtime registration pending native
admission. No hook, model selection, battery parser, or handshake changes. The
Adapter declares a presentation identifier after a report; the parent task owns
the native card implementation.

## Evidence and design

- Primary local AndPods 4.3.8: `util/liberated/ListeningMode.java:6-11` maps
  all four values. `AacpProtocol.java:28-38,311-314` encodes opcode 9, control
  13, one value and zero padding to four bytes.
- AndPods `util/AacpListeningModeSupport.java:18-20` excludes Beats from
  adaptive but otherwise uses a broad allowance. That is app policy, not proof
  that every older AirPods supports adaptive; do not transplant that inference.
- HyperPods-X `018765be94f24a49bf28be26fbb5924a3652018f`,
  `device/Controls.kt:8-11` corroborates all four modes and
  `protocol/Aacp.kt:37-40` corroborates encoding. The source audit records
  report-gated controls and A3441 device results, not general model coverage.
- Original HyperPods `pods/Packets.kt:194-199` also defines values 1 through 4.
  Implementation here uses independently expressed protocol facts; no external
  source or artwork is copied or adapted.
- A real listening report admits baseline three-state controls. Adaptive is
  writable only after its value was reported during this connection. Remember
  that evidence across mode changes, clear it on reset, and never infer it from
  battery, names, or a three-state report.
- Keep reported selection separate from capability evidence. The protocol session
  remembers adaptive observation and the typed feature carries the actual reported
  mode plus session-admitted `supportedModes` for request validation and presentation.
  All writes require device reports for confirmation and request notification
  readback. Existing three-state projection remains for compatibility only.

## Implementation and verification sequence

1. Add a regression asserting adaptive has a vendor feature; observe failure.
2. Add serializable Apple mode/feature and publish it from the existing session.
3. Add request contract and report/reset evidence tests before implementing
   gated writes and readback. Verify transport round trips for all four modes.
4. Exercise every fragmentation boundary, invalid mode bytes, reset, and
   report-versus-request state. Preserve existing battery tests.
5. Run protocol and integration unit tests. Record results here. These tests
   do not constitute headphone, native-panel, or lifecycle device validation.

## Execution evidence

- Regression first: `adaptiveReportRetainsVendorListeningState` failed at the
  missing feature assertion before production changes, then passed with typed
  state publication.
- Expanded contract suite next: four expected failures identified missing
  admitted modes, adaptive retention/reset, vendor write acceptance, and standard
  notification readback. After implementation all nine integration tests pass.
- Existing codec needed no production modification. Additional tests cover every
  split point for all four values, every invalid one-byte mode value (0 and
  5 through 255), recovery to the next report, and incomplete-frame reset.
  Existing battery parser tests remain unchanged.
- Final command: `./gradlew.bat --no-daemon :integration:testDebugUnitTest
  :protocol:testDebugUnitTest` succeeded. XML totals: integration 303 tests;
  protocol 186 tests; zero failures, errors, or skips in either module.
- `git diff --check` passed. No devices, remote services, or runtime registration
  were changed. Hardware listening transitions, notification readback behavior,
  and final native card presentation still require device validation.
