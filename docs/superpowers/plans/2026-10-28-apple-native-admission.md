# Apple connection-scoped native admission

## Goal and constraints

Implement Xiaomi-first Apple fallback on each fresh connection, without a frozen Xiaomi model
list, resource watcher, permanent polling, or replacement of the current session architecture.
Reference: [native source audit](../../research/source-audits/apple-dynamic-native-ownership.md).
A missing native model type must not create a circular dependency on an AACP channel that cannot
start until the type is known. Observe the native connection decision itself.

## Evidence and terminology

The installed ROM's AirCoreManager receives HFP connected/service-rebind events and schedules its
own connection handler. The handler checks `AirLocalStorage.isFeatureSupport(Context, Device,
"ConnectL2cap")` before opening the native channel. NATIVE_DECLINED means this actual connection
was declined by the platform; it does not permanently classify the model as unsupported. An
arbitrary provider false, initialization failure, storage error or elapsed wait is UNKNOWN.

## Implementation sequence

- [x] Resolve the unique native `void(BluetoothDevice)` connection method using DexKit semantic
  strings, exact signature and call to the stable feature gate. Never persist obfuscated names
  across host revisions. Observe real calls only; do not invoke a connection method as a query.
- [x] Add a scoped observer around the original method and feature gate. Preserve original
  results/exceptions; accept only same-device/exact-feature evidence from the actual call scope.
  Verify negative storage/cloud-read health; successful empty type is allowed, swallowed read
  errors are not. Native acceptance wins even if the subsequent native socket fails.
- [x] Connect the existing Xiaomi Bluetooth bootstrap to the observer. Use authenticated host
  requests/replies with the current Bluetooth producer and physical connection token. Validate
  current connection at decision entry and delivery; discard stale tokens and clear on disconnect.
  Reuse existing event-driven initialization and cleanup patterns; no resource-update loop.
- [x] Hold Apple candidates in the existing connection manager until native admission. Register
  the Apple adapter/session only for a current NATIVE_DECLINED decision; SYSTEM retains official
  behavior; UNKNOWN leaves standard Bluetooth usable. Cover module startup/late host readiness,
  HFP service rebind and SDP identity refresh without broad name-only private protocol matches.
- [x] Replace blanket Apple reservation only when the connection gate is wired through all runtime
  registration paths. A later actual native positive revokes module permission via existing
  close/reset/presentation cleanup. Do not claim that asynchronous notification alone proves zero
  socket overlap. Preserve all non-Apple registration behavior and native Xiaomi reservations.
- [ ] Test real hook-scope behavior, true/false/missing gate, bad inputs, read failures, nested
  calls, token replacement, retired producer, disconnect, duplicate events, reset, candidate
  gating and non-Apple regressions. Build/lint and then validate actual new-connection behavior on
  user-authorized hardware before claiming AirPods5 fallback works.

## Remaining implementation decisions

Define host connection provenance for decisions observed before the Bluetooth request and for
native delayed HFP work. Reuse evidence only while demonstrably in the same physical connection;
never turn a timeout into a decline. The source-backed main path assumes the normal HFP lifecycle;
media-only/no-HFP configurations require explicit native eligibility evidence, not a guessed model
or an unbounded wait hidden as completed support. These cases stay visible in acceptance tracking.

The full brand/feature integration objective remains unchanged. This plan is a prerequisite to
activating Apple candidates, not the complete Apple implementation or a replacement for the umbrella.

## Implementation and evidence (2026-10-28)

The Xiaomi host now resolves the unique native connection operation with an exact
`void(BluetoothDevice)` signature, semantic strings and the stable feature-call edge. An
independent scan of every method in the actual ROM DEX found exactly one matching operation.
The APK SHA-256 is `b61f58508256528f7dfbb2166c2e8c352fb343a433a051bc1ab75b039eee3544`.
Actual DEX confirms the feature check precedes channel scheduling, and that the native feature
method catches errors and returns false. Normal outer completion alone therefore grants nothing.
The observer retains original return values and exceptions, with nested thread-local cleanup.

Negative evidence requires the actual preferences `getString` read to succeed; swallowed read
errors remain UNKNOWN. For a nonempty type, original cloud and built-in decisions plus cloud
read health must agree with the native decline. Empty type after a successful actual read is a
valid connection decline. Filesystem access errors are distinct from an absent optional cloud
file. Positive native acceptance takes precedence even if later native socket work fails.

Native handler messages carry an HFP epoch established from the actual manager's live HFP
proxy. Connection entry and result delivery both recheck that epoch and current HFP state.
HFP/ACL disconnect invalidates decisions and queued work; delayed old work cannot authorize a
new epoch. Native decisions preceding A2DP, and A2DP-only reconnects with a surviving HFP
connection, reuse the demonstrably current HFP decision. Bluetooth producer replacement retires
prior evidence, retaining only queued work or completed decisions whose original scheduling was
after the new AdapterService producer's birth and whose HFP connection is still current.

Requests and replies authenticate platform sender package and UID, carry the current Bluetooth
Binder producer, physical token and fresh query nonce, and reject malformed typed results.
Monotonic elapsed-time decision revisions reject out-of-order declines after native acceptance
or HFP invalidation. Native-host READY closes only Apple sessions and issues fresh query nonces,
so previously queued host replies cannot restore stale permission. Submission failure remains
retryable on the next existing lifecycle event. No timer treats silence as decline.

The registry remains Apple-reserved by default. Only the connection manager passes explicit
current decline permission; direct registration independently verifies Apple adapter identity.
Name-only candidates can request one SDP read per physical connection and remain pending until
strong AAP UUID plus headset evidence exists. Settings, pause/resume, SDP, direct registration
and session recreation all enter this same gate. Xiaomi name reservation and non-Apple behavior
remain intact. Address-only MiLink claims do not grant or persist Apple's transport ownership;
the epoch-bound real native observer owns that decision. Existing MiLink presentation replay is
unchanged. Later native acceptance uses existing session close/reset/presentation teardown.

Regression coverage includes scoped original-return/exception preservation and nested cleanup,
healthy/failed/mismatched evidence, HFP-before-A2DP ordering, surviving-HFP A2DP reconnect, retired
queued work, producer replacement ordering, malformed result types, sender identity, real manager
registration/settings/pause/disconnect, stale tokens/revisions, native-host restart and failed
request submission. Early and between-connection address-only MiLink claims are retired when
Apple identity is observed, before sticky ownership can suppress admission. Production read-health
classification and transactional reverse hook rollback have focused tests, including original
exceptions swallowed by vendor callers and rollback failures preserving the installation error. Tests exercise the real manager with private adapters disabled where no
socket is needed; this is not device proof of AAP transport or interception.

Initial full Gradle tests passed; initial Release lint found the raw preferences mode constant
and DexKit's duplicate-create heuristic after adding the second independent one-shot resolver.
The mode now uses the native `MODE_MULTI_PROCESS` constant. The two resolver methods carry a
scoped `DuplicateCreateDexKit` suppression: each runs once at host readiness and closes its DEX
bridge promptly, rather than keeping process-global mapped scan memory. Optional notification
and admission failures remain independent. Final reviewed full run passed `testDebugUnitTest :protocol-test:assembleDebug
:system-module:lintRelease :system-module:assembleRelease`: 643 tests (186 protocol,
305 integration, 152 system-module), zero failures/errors. After the final installation activation
guard, affected-module tests, Release lint and Release assembly passed again (181 tasks,
1 minute 22 seconds). Plan markdownlint, repository documentation validation and `git diff --check`
also passed. The DexKit heuristic still prints its diagnostic during analysis, but its narrowly
justified suppression leaves the Release lint task successful; no global rule was disabled.

## Explicit acceptance gaps

No device installation, restart or runtime connection test was performed. Two optional startup
DEX scans and asynchronous hook installation may miss a native decision that completes before
observation is installed. Native HFP service-rebind/new-connection work provides later real
decisions; no connection method is invoked artificially. A media-only/no-HFP connection, or a
startup with no observed authoritative decision, remains UNKNOWN with no Apple private socket.
These cases are not claimed as completed fallback support. Actual startup timing, Binder/broadcast
delivery, HFP-first ordering, SDP refresh and the post-resource-update Bluetooth restart path
still require user-authorized hardware validation. Asynchronous revocation does not prove zero
socket overlap; the module never blocks or changes the native connection operation.

## MiLink physical ownership reconciliation follow-up

A later end-to-end review found that the Bluetooth transport gate alone did not retire MiLink's
old process-lifetime SYSTEM owner. A new Apple fallback session could run while MiLink skipped
its native presentation query, runtime callbacks and card lookup indefinitely. The bounded fix
adds `connectionScopedOwnership` to existing presentation metadata, derived from the manager's
Apple connection evidence rather than Adapter IDs or names in MiLink. It survives a Standard
adapter fallback and disconnect snapshots.

MiLink binds the authenticated Bluetooth producer and physical `HeadsetConnection` before its
SYSTEM branch. Only a new physical scope clears that address's old owner and query/claim delivery
bookkeeping. Logical session replacement in the same scope preserves a current native-positive.
The existing real `checkIsMiTWS` result still decides presentation admission; transport decline
never directly grants it. Native results spanning a physical scope change are discarded without
changing their original return value. State acceptance/scope binding and original-result commit
use the same ownership lock, while the actual native call runs outside it; a late result cannot
stamp its claim onto a replacement scope. An authenticated initial scoped positive received before
local state retains SYSTEM when matching state later arrives. Shared SYSTEM claims carry the physical scope; old or
unscoped claims cannot overwrite an already scoped current connection. Non-Apple sticky ownership
and claim replay remain unchanged.

The JVM regression uses the real `handleStateChanged` and native-query path with a minimal service
fixture invoking the same production observer as the Xposed interceptor. It checks restoration
of the presentation receipt, native runtime callback and Apple card-adapter lookup after an old
SYSTEM owner; fresh native-positive priority; same-physical logical replacement; Standard fallback;
and retired producer/state/claim rejection. This supplements existing native card View tests and
still does not establish live device rendering or actual Xposed interception.

The final quality re-review approved this physical-scope follow-up after both initial-positive
ordering and atomic-commit corrections. The subsequent full Gradle run passed in 1 minute
27 seconds (236 tasks): 650 tests, comprising 186 protocol, 305 integration and 159 system-module,
with zero failures/errors; protocol-test Debug assembly, module Release lint and Release assembly
also passed. Seven real MiLink-flow regressions cover the reconciliation. The verification log is
retained locally as `.analysis/verification/apple-milink-reviewed-full.log`. Documentation lint,
repository documentation validation and `git diff --check` passed; hardware acceptance remains open.
