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

- [ ] Resolve the unique native `void(BluetoothDevice)` connection method using DexKit semantic
  strings, exact signature and call to the stable feature gate. Never persist obfuscated names
  across host revisions. Observe real calls only; do not invoke a connection method as a query.
- [ ] Add a scoped observer around the original method and feature gate. Preserve original
  results/exceptions; accept only same-device/exact-feature evidence from the actual call scope.
  Verify negative storage/cloud-read health; successful empty type is allowed, swallowed read
  errors are not. Native acceptance wins even if the subsequent native socket fails.
- [ ] Connect the existing Xiaomi Bluetooth bootstrap to the observer. Use authenticated host
  requests/replies with the current Bluetooth producer and physical connection token. Validate
  current connection at decision entry and delivery; discard stale tokens and clear on disconnect.
  Reuse existing event-driven initialization and cleanup patterns; no resource-update loop.
- [ ] Hold Apple candidates in the existing connection manager until native admission. Register
  the Apple adapter/session only for a current NATIVE_DECLINED decision; SYSTEM retains official
  behavior; UNKNOWN leaves standard Bluetooth usable. Cover module startup/late host readiness,
  HFP service rebind and SDP identity refresh without broad name-only private protocol matches.
- [ ] Replace blanket Apple reservation only when the connection gate is wired through all runtime
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
