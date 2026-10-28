# Samsung explicit earbud fit-test workflow

GalaxyBudsClient pinned754b6fdf, `Message/SppMessageEnums.cs:187–188`,
`Message/Decoder/FitTestDecoder.cs`, and `Interface/ViewModels/Pages/FitTestPageViewModel.cs`
provide the executable contract: message157 bool starts/stops; message158 reports
left/right results0 bad,1 good,2 test-failed. There is no independently proven progress
or running-status query. QuickBuds' similarly named fit functions belong to its OPPO
protocol and are not evidence for Samsung bytes.

The exact GearFitTest spec entries are Buds2, Buds2Pro, FE, Core, Buds3, Buds3Pro,
Buds3FE, Buds4 and Buds4Pro. No rule adds a revision threshold. Names/profile identity
only select eligibility: an actual valid extended-status report establishes the current
model/revision before the explicit start is available. Buds, Buds+, Live and Pro do
not acquire this workflow. Existing legacy framing and ordinary controls are unchanged.

The typed request pins model/revision and a positive local lease. This lease is host
ownership/intent, not device-running confirmation. Start sends one157/01 only after a
user gesture; no initialization query starts a test. Start clears previous results.
The result parser requires exactly two known values. Only an active local lease may
consume a result and enqueue157/00. Duplicate/unsolicited results do not send stop or
claim a new result. ACKs do not confirm a test or fabricate a result. The protocol
carries no transaction ID, so correlation is bounded to the current owned workflow;
physical late-result timing across repeated tests remains a device-validation concern.

The native Settings entry opens the existing themed Samsung details; a separate Start
button requests the test. The panel explains wearing both earbuds, displays requested/
waiting status and separate left/right results, and offers Stop. Closing/detaching or
30-second UI timeout stops only its matching owned lease while the session is still
module-owned. Ownership already transferred prevents the old UI from writing.

A transport-owned30-second cleanup deadline is armed only after successful start write,
so UI-process death does not leave a permanent workflow. Expiry matches the exact lease,
clears local ownership and sends stop once. Result/explicit stop cancels the deadline.
Graceful private-channel release consumes any owned lease before handoff; abrupt reset
clears state without writing a new channel. No telemetry query is reused as a write.

Independent golden frames use source SppMessage CRC-CCITT/XMODEM framing:
start `fd04009d01967edd`, stop `fd04009d00b76edd`, result0/1 `fd05009e00011973dd`.
Tests cover exact model membership, strict result shapes, read-before-start, IPC,
unsolicited/duplicate results, reset/release/deadline matching, native timeout/dismiss
and stale tokens. These are source-backed fixtures, not new hardware captures.

## Wire correlation limit

Result 158 carries only two ear values and no transaction identifier. A local lease scopes
control and cleanup, not the origin of a late wire event. If an earlier stopped test reports
only after a new test starts, the protocol cannot distinguish it from the new result. Neither
the reference nor this implementation supplies a proven stop-ACK/drain barrier. Rapid repeated
tests therefore retain this explicit device-validation limitation; no synthetic wire ID is added.
