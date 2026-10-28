# Apple MiLink advanced details implementation plan

> Continue approved full integration using the existing CardAdapter, native host item and
> in-host details pattern, with test-driven implementation and independent reviews.

## Current user direction

The user asked how HyperEars currently presents native UI; this was a question, not cancellation
of the goal or a new architecture requirement. Development resumed after a separate goal pause.
Prefer existing native MiLink extension patterns. Settings research remains useful evidence,
but no Settings scope or host hook is required for this slice.

## Implementation

- [x] Extend Apple listening card with an advanced entry preserving its four native choices and
  transactional restoration. Render fourteen confirmed settings via the existing in-host details
  lifecycle and typed sender, with bounded slider/enum/toggle controls and accepted-state display.
- [x] Supply a narrow sessionTokenProvider in MiLinkCardEnvironment from ProcessStateStore so
  open panels and stale gestures cannot move across sessions. Require matching address, active
  profile/private channel/handshake and MODULE ownership on every interaction; suppress no-ops.
- [x] Support advanced-only reports through a separate apple-settings presentation, using the
  verified stock carrier approach already used by FitClip game mode. Reusing the carrier must
  not create real noise capabilities, listening state or noise-control requests. Late listening
  reports select the existing listening presentation through normal coordinator rebind.
- [x] Follow detach/unbind/disconnect/ownership/feature loss and failed-window cleanup. Remove
  observers exactly once; handle repeated open/close and readback without optimistic state.
- [x] Run focused regression tests, independent specification/quality review, full unit/build/lint
  and documentation checks; preserve actual device and rendered-host acceptance as pending.

## Evidence and scope

Current native host structure comes from NativeAncModeHost, NativeAncModeRow and the existing
SamsungTouchControlDetails pattern. No new protocol owner, parallel service, raw UI command map,
app redirection or Settings package scope is introduced. Consistent native presentation for other
brands remains in the full plan; this Apple slice is not completion of the overall objective.

## Verification

Missing entry/carrier and unattached-anchor subscription cleanup failures were observed before
fixes. Factory exception tests likewise failed before fail-open cleanup was added. All 691 tests
passed (198 protocol, 320 integration, 173 system-module). Protocol-test Debug assembly and
unsigned Release assembly passed. Release lint initially found three dynamic-text formatting
errors; locale-aware formatting fixed them and system tests, lint and Release assembly passed.
Independent spec and quality reviews found no blocking issue. Physical host rendering, theme,
window interactions and actual headphone readbacks remain pending.
