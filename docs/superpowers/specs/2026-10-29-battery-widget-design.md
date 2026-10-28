# D1 desktop battery widget: bounded implementation design

Status: root technical review accepted; implementation in progress under the approved D1 plan. No device changes,
new scope, notification permission or private controls are proposed.

## User experience

One original resizable RemoteViews provider, compact2x2 and wide4x1 arrangements.
Show device name, only reported left/right/case or overall components, charging,
connection/ownership status as a recorded observation, and an absolute timestamp.
Unknown percentages remain unknown;0% is valid. Do not duplicate overall into buds.
Use existing original/module artwork or a neutral original drawable, not vendor assets.

Per-widget selection defaults to Auto: most recently connected accepted device,
with stable address tie-break (never most recent packet, avoiding motion-driven flips).
An optional/reconfigurable configuration activity selects Auto or a fixed previously
observed address. Fixed missing/offline devices do not silently fall back to another.
Opening the battery area uses a user-triggered nonexported Activity PendingIntent,
resolves the current widget selection and calls BluetoothDeviceSettings.open.
A separate configuration button opens this widget's selector. No control requests.

## Source and architectural evidence

Original AndPods4.3.8 SmallAppWidgetCommonProvider delegates updates to AppWidgetHelper
and deletes per-ID database records; AppWidgetHelper manages small/middle/4x1 variants,
lastHeadsetInfo and per-widget preferences. This justifies lifecycle/per-widget selection;
its implementation, styling, database model and resources will not be copied.

Current StateBroadcaster.publish/reply already sends authenticated state from Bluetooth,
ModuleContract carries revisions/session identity, and NativePresentationContract carries
producer Binder + physical connection/admission. DeviceStateRegistry and
MiLinkDeviceOwnershipRegistry already guard retired sessions, revisions and scopes.
Widgets consume these contracts; they never create a transport or grant native admission.

## Delivery and lifecycle contract

1. Keep the AppWidgetProvider enabled for launcher discovery. Add a separate exported
   explicit-only WidgetStateReceiver with no intent-filter, disabled by default.
   Existing package-targeted full STATE_CHANGED broadcasts therefore never match it.
2. Provider onEnabled/onUpdate/reconfiguration reconciles actual owned widget IDs,
   enables that receiver only while IDs exist, and requests ModuleContract.requestState
   for MODULE_PACKAGE. onDeleted removes per-ID choice; last removal disables the state
   receiver, clears in-memory producer links and drops device display cache.
3. A small Bluetooth-side BatteryWidgetPublisher is invoked from StateBroadcaster
   publish/reply. It emits an explicit component-targeted reduced state through
   BroadcastIdentity.send, retaining original revision/session and native metadata.
   Feature payload contains battery only; no motion, EQ, gesture, vendor settings,
   control envelopes or artwork blobs. Keep only fields needed for display/validation.
4. Publisher dedup key includes producer/physical connection/session, address/name,
   battery components, form factor, lifecycle/ownership/admission. Exclude general
   revision and unrelated features. Head-tracking packets cannot trigger widget sends.
   Cache is bounded; disconnect/ownership/producer transitions are never suppressed.
5. StateBroadcaster.reply to MODULE_PACKAGE forces a reduced replay even when the
   projection is unchanged: newly added widgets must receive a snapshot. This uses the
   existing request path, not a timer/query job. An empty unscoped reply cannot revoke
   a newer scoped live record; initial/restored widgets remain historical or waiting.
6. Receiver checks platform supplied package+UID is com.android.bluetooth, valid address,
   live producer, monotonic connection scope, revision and retired-token guards before
   rendering/persisting. Reuse existing guard classes; do not trust Intent credentials.
   System admission withdraws private battery display immediately. UNKNOWN does not
   become HYPEREARS through the widget; initial conservative policy waits for admission.
7. Persist display-only bounded records and per-widget address choices in app-private
   preferences: name, percentages/charging, form factor, last recorded status/time.
   Never persist Binder, session/connection tokens, serialized EarbudState, controls or
   trust assertions. Process-restored cache is explicitly historical until fresh replay.
8. Link to producer death while the app process is alive; death/replacement revokes live
   records and renders historical/awaiting-confirmation state. No service keeps the app
   alive. RemoteViews always says "last recorded", so process eviction cannot leave an
   unqualified live/connected claim. Resize/configuration/start requests a fresh replay.

## Timestamp choice and limit

EarbudState currently has no battery report timestamp. Using publish time on every
state update would make motion packets and replay falsely refresh battery age.
For the bounded first implementation, producer projection stores first-observed or
changed-battery time and preserves it across unrelated updates/replay. UI wording is
"电量记录于 MM-dd HH:mm", not "最近一次设备上报" or "实时". Empty/uncached replay
may have unknown record time; do not substitute current time. If exact repeated-report
freshness is required, add a battery-event timestamp hook separately; StateBroadcaster
alone cannot reconstruct it. No periodic age-refresh is required for an absolute time.

## Files and integration boundary

- New widget/BatteryWidgetProjection.kt: pure projection, stable auto selection,
  dedup/timestamp policy and display DTO; unit tests.
- New bridge/BatteryWidgetPublisher.kt: reduced explicit authenticated delivery;
  root wires two calls in StateBroadcaster.publish/reply.
- New widget/BatteryWidgetStateStore.kt + WidgetStateReceiver.kt: authentication,
  existing identity guards, bounded display persistence and producer lifetime.
- New widget/BatteryWidgetProvider.kt + BatteryWidgetRenderer.kt: IDs, deletion,
  resize threshold/compact-wide layouts, immutable unique PendingIntents and replay.
- New widget/BatteryWidgetConfigurationActivity.kt and BatteryWidgetClickActivity.kt:
  validate own provider/widget IDs; auto/fixed selection and existing Settings routing.
- Manifest receiver/activity entries; widget XML metadata (updatePeriodMillis=0,
  resizeMode horizontal|vertical, optional/reconfigurable configuration), two layouts,
  original neutral drawable and strings. No scope or permission additions.
- Documentation/CHANGELOG; AndPods is research reference, no copied code/assets.

## Implementation checklist / acceptance tests

- [ ] Pure projection:0/unknown/charging/overall-vs-buds, same-MAC normalization,
  stable auto order, fixed missing target, disconnect/system-owner revocation.
- [ ] Publisher: thousands of motion/revision-only updates cause zero extra explicit
  sends; battery/lifecycle changes send; forced replay bypasses dedup without new time.
- [ ] Receiver: spoofed sender/UID, dead/retired producer, stale connection/session/
  revision and old disconnect cannot replace newer state; malformed reduced data rejects.
- [ ] Persistence: only display data survives; process reload is historical; last widget
  removal disables receiver and removes choices/cache; adding again forces replay.
- [ ] Actual RemoteViews/provider Robolectric tests: compact/wide resize, charging and
  unavailable labels, multiple widget IDs/selections, restore/deletion/reconfiguration.
- [ ] Actual click routing tests: unique immutable PendingIntents, invalid foreign widget
  ID rejected, same current selected address passed to BluetoothDeviceSettings.open,
  existing Bluetooth-settings fallback respected, no ACTION_CONTROL emitted.
- [ ] No alarm, polling worker, foreground service, notification permission or scope added.
- [ ] Root coordinated unit/lint/release verification after technical review; no phone.

## Trade-offs

An explicit-only disabled-when-unused receiver is preferred over subscribing a manifest
receiver to the existing full state action, which would wake the app for high-frequency
telemetry. A foreground service/polling worker is unnecessary and outside this slice.
Reusing one responsive provider reduces duplicated layouts/lifecycle logic versus
copying AndPods' multiple providers. Conservative native admission and historical
wording prioritize truthful display over pretending every persisted record is live.
