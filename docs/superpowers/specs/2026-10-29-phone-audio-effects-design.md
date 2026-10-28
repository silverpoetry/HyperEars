# Phone-side audio effects: source audit and implementation design

## Scope and current status

D2 is still unimplemented. This specification separates phone DSP from earbud EQ and
records the concrete lifecycle needed before porting AndPods' audio effects. It does not
claim an active effect, permission availability, hardware support or audible validation.
Existing earbud protocol EQ and native vendor controls remain independent.

## Inspected evidence

Local AndPods4.3.8 `util/EQHelper.java:55–84,124–155` constructs DynamicsProcessing on
session0, with ten pre/post bands, stereo channels and limiter. A displayed band gain is
applied to pre-EQ and another0.7 times to post-EQ. Its `EqualizerLoader` schedules these
writes on an executor. This is Android processing, not an AirPods AAP EQ command or a
curve stored in the headphones. The source also contains music/model preset arrays and
channel controls; these are tuning data, not proof of measured headphone response.

Android35 local SDK `DynamicsProcessing.java:182–220` explicitly deprecates global
session0 and configures the engine during construction. `AudioEffect.java` documents
shared engines, control priority and ownership changes. `AudioPlaybackConfiguration.java:
391–417` removes device/session/client identity from anonymized playback configurations;
`getSessionId()` at470 is a hidden API. Consequently a list of active players alone does
not establish the session or its headset route. No extra system-server scope is authorized
or needed for the proposed capability-gated implementation.

## Chosen integration direction

Use the existing Bluetooth host and authenticated current connection/ownership state.
A default-off phone-audio configuration is distinct from device features. Enable controls
only for active, identifiable media sessions whose actual output is the same connected
headset. Session0, anonymous IDs, ambiguous/multiple output routes, calls, unsupported
engines and lost effect ownership are explicit unavailable cases, not global fallbacks.
Verify the host's actual capability at runtime; the cached `com.xiaomi.bluetooth.sample`
manifest is not proof of the installed host's permissions.

A coordinator owns at most a bounded set of session effects. Playback callbacks and
connection/settings/route changes reconcile that set; no polling or eager idle engine.
Keys include session, player/client identity and physical connection generation so recycled
IDs cannot inherit a stale engine. No audio capture, focus acquisition, route switching,
media restart or stream-volume write is required. Disconnect, external/system takeover,
producer loss, pause, effect control loss and settings disable release owned engines.

The first complete UI should offer explicit enable, ten-band editing and channel balance,
with a clear phone-DSP label in both settings styles and a native settings entry using the
existing presentation path. Values must match actual engine topology/ranges; unavailable
channels must not be fabricated. Preset data requires separately resolved source constants;
model labels must not imply physical acoustic calibration. Applying a curve does not modify
any private earbud EQ or persist it into the headphones.

## Implementation gates and tests

Before construction, establish a nonzero real session, current exact route and host API
capability. Resolve shared-engine ownership policy before configuring DynamicsProcessing:
its constructor writes parameters, so a post-construction `hasControl` check alone is
insufficient evidence of a nonintrusive probe. Do not claim this unresolved policy is solved.
A lower-priority request, failure bounds and lifecycle tests require review against actual
framework behavior before implementation. No repeated constructor retry on each report.

Tests must cover anonymous sessions, recycled IDs, route changes, multiple outputs, calls,
ownership loss, settings replacement, partial construction, parameter failure, simultaneous
players, disable and cleanup. UI must separate requested configuration from observed engine
application; an API success code is not acoustic acceptance. Real listening remains part of
the user's later AirPods/Coral validation, not a reason to block other source-backed ports.
