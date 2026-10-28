# Apple native listening card

Date: 2026-10-28

## Scope and design

Add the concrete `apple-listening` MiLink presentation without admitting Apple to
live matching or changing protocol, transport, ownership, or native classification.
Use the existing original and select-card contracts in `NativeAncModeRow`.
Keep the host order: transparency, ANC, off. Add adaptive as the fourth native
item only when the confirmed feature's `supportedModes` admits it.

The card consumes `AppleListeningModeFeatureState` and sends only
`AppleControlRequest.SetListeningMode` through `environment.controlSender`.
Each click reads current state again. Missing evidence, inactive sessions,
disconnected private transport, pending handshake, external ownership, unsupported
choices, and requests for the already-confirmed mode produce no command.
Selection always follows confirmed state; a successful send does not select a
requested mode.

The three stock icons remain mapped to their own modes. Adaptive borrows the
ANC icon with a distinct label and a unique sibling ID. All replacements are
constructed before host mutation and start disabled; adaptive starts hidden.

## Implementation sequence

1. Add a registry test and observe its assertion fail because `apple-listening`
   has no registered card adapter.
2. Add real-View tests for both native layouts, construction, selection, visibility,
   typed requests, stale-state gating, and restoration.
3. Extract only FloS2's transactional native-row replacement into
   `NativeAncModeHost`; keep its behavior and existing rollback tests.
4. Implement and register `AppleListeningMiLinkCardAdapter`, with a small pure
   policy for feature and lifecycle projection.
5. Run system-module unit tests and release lint. Leave live admission to the
   separate native-ownership work and device acceptance to real validation.

## Validation

`./gradlew.bat --no-daemon :system-module:testDebugUnitTest
:system-module:lintRelease` passed. The system-module run contains 32 suites and
127 tests, with zero failures or errors. The Apple suite contains seven tests;
its two-layout checks exercise both original and select-card native contracts.
Markdownlint passed for this plan and `git diff --check` reported no whitespace
errors. Robolectric emitted the existing JVM native-access warning during tests.

## Evidence and limits

The first registry-only run compiled successfully and failed its `assertNotNull`
assertion. The expanded test suite also failed while the registration was absent.
The real-View fixtures use Android views under Robolectric and the actual host
class names and expected resource shapes; they do not execute a MiLink APK.
Existing rollback tests cover failure before insertion, failure after attachment,
unrelated siblings, original layout parameters and visibility, and repeated
restoration. Tests are evidence of local binding and transaction behavior, not
physical AirPods control or native classification on any ROM.

No device installation, runtime UI acceptance, signed build, commit, push, or PR
operation is part of this slice. Live Apple matching remains excluded until the
native-admission implementation is complete.
