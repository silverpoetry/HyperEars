# Apple native settings presentation follow-up

Source audit dated 2026-10-28. Initial audit was source-only; the follow-up below
adds explicitly authorized read-only device package inspection and extraction.
No build, UI, restart, install, setting or scope change was performed.
This document recommends the next presentation slice;
source existence is not runtime or device acceptance.

## Decision

The current-ROM follow-up below establishes a smaller **actual native Settings**
route: bind typed preferences into `BluetoothDeviceDetailsFragment` reached by
the existing system-settings navigator. Prefer that route over forcing the
specialized AirPods plugin page. Its verified layout-reconciliation requirement
is documented in the final section. Earlier plugin unknowns below describe the
initial evidence boundary and are superseded where explicitly resolved there.

The final target remains actual native Settings. An Apple-owned details sheet
inside the existing MiLink card binding is only an optional intermediate slice.
`SamsungTouchControlDetails.kt` already establishes
the host-window, accepted-state subscription, typed-request and dismissal pattern.
Keep `AppleListeningMiLinkCardAdapter`, its presentation ID, the selected Adapter,
and the existing session/control transport. Add no Apple service, Activity,
standalone settings destination, generic feature registry, or second connection.

This meets **native-style advanced controls inside the existing host, without an
app redirect**. It does **not** establish that all controls are native Settings
preferences or that the stock Settings device-detail page contains them. A
PopupWindow with host widgets remains module-created UI. If the acceptance target
is specifically the stock Settings full detail page, that remains a separate,
currently missing Settings host integration; do not report it complete after
shipping the sheet.

## Existing reusable contracts

Paths in this section are under
`system-module/src/main/java/dev/hyperears/` in HyperEars-full.

- `hook/MiLinkCardAdapter.kt`: `bind(root, address, environment)` returns a
  `MiLinkCardBinding` with `render` and `unbind`. `MiLinkCardEnvironment` provides
  exactly `hostClassLoader`, `stateProvider`, `controlSender` and `observeState`.
  There is no Activity/navigation context or native Settings host contract.
- `hook/AppleListeningMiLinkCardAdapter.kt`: native ANC carrier resolution and
  replacement are already owned by Apple, including the fourth adaptive choice.
  `AppleListeningCardPolicy` requires active session, private channel, accepted
  handshake, MODULE control ownership and a reported feature. Preserve these
  gates for advanced controls; a feature report alone does not authorize a write.
- `hook/MiLinkHeadsetDetailExtension.kt`: normalizes addresses, selects the
  presentation from active accepted `state.adapter`, releases a binding on
  address/presentation change or root detach, and can unbind when the system
  reclaims a device. Observers receive address-filtered state snapshots; delivery
  snapshots the observer list to permit reentrant dismissal. `observeState`
  does not emit an initial value, so render `stateProvider(address)` immediately
  after creating a detail view. Existing render/attach paths perform refresh;
  the details window needs no polling or new refresh scheduler.
- `hook/SamsungTouchControlDetails.kt`: uses the anchor context/window token,
  a focusable PopupWindow with scrollable pages, and
  `miuix.slidingwidget.widget.SlidingButton(Context)` resolved using the host
  classloader, falling back to Android Switch. Subscribe while open, unsubscribe
  on both explicit dismissal and `OnDismissListener`, and dismiss on invalid
  feature/disconnection. Back navigation and page rebuilding are already local
  to the detail object. Colors are explicitly chosen by night mode in the helper,
  not proof of exact ROM theme/style parity.
- `hook/NativeAncToggleMiLinkCardAdapter.kt`: `MiLinkToggleDetailsSpec`,
  `MiLinkToggleDetails` and `ToggleController` supply the existing ownership
  precedent. A details reference belongs to its binding; reopening dismisses
  the old one; unbind clears listeners and dismisses it. Toggle requests restore
  the accepted value before sending. Reuse the lifetime/interface pattern;
  Apple need not inherit a three-mode toggle adapter or sacrifice its fourth mode.
- `hook/MiLinkServiceHook.kt:1376`: `sendControl` rejects system-owned targets,
  requires an active ProcessStateStore snapshot and current session token, then
  serializes through `ModuleContract.control`. A CardAdapter must only call
  `environment.controlSender`; it must not duplicate this Bundle/broadcast logic
  or call ROM provider commands directly.

The Samsung detail implementation is a lifecycle precedent, not an Apple policy
implementation to copy unchanged. Its UI enablement predominantly checks
`sessionActive && connected`; Apple must retain its stricter private-channel,
handshake and ownership checks and re-read current state at every gesture. Do not
authorize writes from a cached `lastState` captured before ownership changed.

## Why current navigation does not supply native Settings controls

`hook/MiLinkHeadsetSettingsNavigationBridge.kt` hooks the semantic
`com.miui.circulate.api.protocol.headset.HeadsetServiceController`
`switchToHeadsetActivity(CirculateServiceInfo): CompletableFuture`. It validates
the exact signature, uses version-scoped known legacy entries or a unique DEX
fingerprint, and suppresses the host call only when `openPreferredSettings`
succeeds. This is a navigation hook, not a settings layout hook; its callback
receives an address, not a View anchor.

`MiLinkServiceHook.openPreferredHeadsetSettings` currently delegates to
`bridge/HeadsetSettingsNavigator.kt`. SYSTEM_SETTINGS calls
`BluetoothDeviceSettings.open`, which sends the device-detail Settings Intent
with `device_address`, then falls back to generic Bluetooth Settings. Other
explicit destinations open the vendor app or HyperEars MainActivity. None injects
advanced Apple controls. Do not present this existing launch as completion, and
do not route the new Apple advanced entry to those independent app destinations.

For the minimal slice, add the advanced entry within Apple's bound card so it has
an actual attached anchor. Keep navigation policy separate. Reusing the global
more-settings hook to show a sheet would first require an address-to-live-root
handoff, absent from its current contract; inventing a hidden Activity or guessed
View lookup is unnecessary when the CardAdapter already owns the root.

## Actual captured ROM evidence

`R` below is
`D:/Backup/Desktop/HyperEars/.analysis/andpods-4.3.8/`.
These are extracted snapshots, not a refreshed device inventory.

- `R/milink/sources/com/miui/circulate/api/protocol/headset/HeadsetServiceController.java:662`
  contains the exact stable navigation method used by the existing bridge.
  `HeadsetControlAncItemView`, `HeadsetSelectCardView` and
  `HeadsetSelectItemView` also exist under the actual MiLink UI package. Their
  presence establishes source carriers, not universal host compatibility.
- The extracted Settings implementation is plugin-owned:
  `R/settings-plugin/sources/plugin/settings/java/JavaActivity.java` and
  `airpods/MiuiAirpodsFragment.java`. Do not assume the upstream example's
  `com.android.settings.bluetooth.MiuiHeadsetFragment` is the class loaded by
  this ROM. `JavaActivity.loadFragment` selects MiuiAirpodsFragment using
  `mIsAirPods` at lines 862-903; service binding occurs at 930-938 and unbinding
  at 684-692. Entry routing and plugin classloader availability require discovery.
- `MiuiAirpodsFragment.java:312-348` gets the device from JavaActivity, loads
  `R.xml.airpodslayout` and obtains preference groups. `onCreateView` at 504
  attaches the preferences to the host layout; lines 521-523 register the
  AirPods state callback and request current state. `onDestroyView` is at 547;
  callback cleanup is in `onDestroy` at 629-643. Controllers are created only
  after their support checks: AncController at 572 and WearDetectionController
  at 579. This is a genuine native preference carrier with real lifecycle.
- `airpods/DbHelper.java` defines ContentResolver.call authority
  `com.android.bluetooth.ble.app.headsetdata.provider`, method
  `airpodsRepository`, and arguments `check_feature_support`, `get_state`,
  `send_command`, `set_state`. Payloads carry a BluetoothDevice plus
  `extra_key` and `extra_value`; results include `extra_feature_support`,
  `extra_value`, or `extra_command_state`. This is an existing ROM schema, not
  permission to invent keys for every Apple typed setting.
- `airpods/AncController.java:212-237` sends through DbHelper after host checks;
  its native UI has three controls. `isSupport` at 348 uses the provider feature
  query. `WearDetectionController.java:188-211` uses EarDetection support and
  writes **PhoneState.LOCAL_EAR_DETECTION** with `set_state`, updating phone-side
  state. This is not automatically the same operation as AACP wearing
  configuration `0x0A`; binding that preference straight to the new protocol
  control would conflate phone behavior with earbud configuration.

## Smallest bridge to the real Settings fragment

The first concrete, source-verified UI seam is **after**
`plugin.settings.java.airpods.MiuiAirpodsFragment.onCreate(Bundle)`: at this point
the original code has loaded `airpodslayout` and a real PreferenceScreen exists.
Resolve the exact signature with the plugin's actual classloader, obtain its
Activity, require `JavaActivity.getDevice(): BluetoothDevice`, and match that
address to a current accepted module-owned Apple session before inserting a
namespaced native preference category. Construct host `miuix`/AndroidX preference
classes with the host Context and classloader; no module Activity is necessary.
Only insert typed settings lacking a stock equivalent; leave original native
device behavior untouched. This is a proposed hook based on inspected source,
not an installed or runtime-tested hook.

Entry routing is a prerequisite, not solved by that hook. In captured source,
`JavaActivity.onCreate:175-203` decides `mIsAirPods` through
`AirPodsHeadsetStateContext.checkIsAirPods(Intent):285`. That helper reads
`MIUI_HEADSET_SUPPORT`, splits on `|`, requires `AIRPODS_PARAM_LENGTH`, and tests
the first component for `airpods`. Type is component 1 (`:376`); wear type is
component 2 only when the helper's longer-length condition holds (`:392`). The
address comes from `BluetoothDevice.EXTRA_DEVICE` in
`JavaActivity.getAddressFronIntent:1787`; the public `getDevice` is at 562.
Do not guess a support string or borrow an unrelated device type. The outer
Settings Activity/plugin loader and valid component/export contract were not
established from this plugin alone. The existing generic device-detail Intent
does not prove this fragment will be selected for module-owned fallback devices.

View/state injection itself does not require persistent native identity spoofing:
it can use the real device address and typed module state in an already-open
host. Reaching this specialized page for an unsupported device may require an
explicit **presentation-only** entry bridge; a verified outer entry and minimum
host initialization contract must come first. Do not persist `AirpodsModel`,
grant native admission, or pretend native protocol support merely to select the
fragment. Opening this full fragment is not automatically side-effect-free:
`MiuiAirpodsFragment:404` and `:602` call the official service's `connect(device)`;
JavaActivity binds official services. Before using that route for MODULE-owned
devices, trace the captured service implementation and prove these calls do not
start a competing private connection, or intercept only the verified private
operation for that exact module-owned address. Blindly suppressing all host
service connections could break ordinary profiles and native page initialization.

Cross-process plumbing is also concrete missing work:

- `HookEntry.kt:30-49` has Bluetooth, Xiaomi Bluetooth and MiLink branches, but no
  Settings host branch. Scope alone would therefore not install a Settings hook.
- `ModuleContract.stateConsumerPackages:91-96` includes module, MiLink and native
  headset packages, but not Settings; `readReplyPackage` rejects other packages.
  Add the verified Settings host as a deliberate state consumer, reuse
  `StateBroadcaster` and `ProcessStateStore` acceptance/revision/token rules,
  and request accepted state when the bound page starts. A Settings-local cache
  is not a protocol session owner.
- Reuse the state sender check from `MiLinkServiceHook:714`:
  `BroadcastIdentity.accepts` requires both expected sender package and the UID's
  package membership. Never trust a package-name Intent extra. State must come
  from the authenticated Bluetooth process, and UI gestures must use its accepted
  session token with existing `ModuleContract.control` encoding.
- `EarbudSessionService:136-148` currently routes ACTION_CONTROL by decoded typed
  request/address/session token to `EarbudConnectionManager.execute`; it does not
  use the MiLink-only sender check in the separate system-ownership branch.
  Do not misdescribe that MiLink ownership-claim restriction as a general control
  sender allowlist or broaden native-admission authority to Settings. Reuse the
  token/active-session enforcement and Adapter request contract; explicitly test
  stale tokens and ownership handoff for the new consumer.
- Page binding should subscribe at a real view lifecycle boundary and release
  at `onDestroyView`, remove its own preference listeners/category, and recreate
  on re-entry. Also handle the fragment's `onDestroy` cleanup and activity/device
  replacement. The ROM's own AirPods callback registration at onCreateView and
  cleanup at onDestroy is evidence to account for, not permission to leak a new
  subscription until process death.

This minimal host bridge keeps currentAdapter and CardAdapter responsible for
their existing device/card roles. A small Apple native preference binding can
project the same typed features and sender; no universal feature architecture
is needed. Native UI reuse and native protocol ownership are separate decisions:
MODULE-owned fallback can render in native UI while all writes remain with its
existing ProtocolSession, and SYSTEM-owned devices retain official controls.

## Limits of the captured Settings surface

No complete provider-to-native-widget mapping was established here for microphone,
conversation awareness, personalized volume, timing/swipe or adaptive strength.
No matching extracted modern `BluetoothDeviceDetailsFragment` implementation was
established in this Settings plugin snapshot. The newer configurable-provider
chain described by OppoPods-Leaf's source audit is a lead, not proof that this
ROM loads that API or accepts a new provider endpoint.

`system-module/src/main/resources/META-INF/xposed/scope.list` currently excludes
`com.android.settings`; the inspected project has no equivalent Settings plugin
presentation binding. Existing native-settings audit recommendations describe a
future target, not an already implemented shared native Settings host.

## Actionable next slice and verification gaps

1. Extend Apple's current binding with one advanced entry and one owned details
   object. Resolve/construct before mutating the native row, preserve originals,
   and restore the entry and listeners in `unbind`. Handle settings evidence
   arriving before listening evidence explicitly: the current presentation uses
   an ANC carrier, so advanced-only sessions need a proven carrier or must remain
   unexposed. Do not fabricate listening support merely to display the entry.
2. Render only the typed settings confirmed in the current session by the
   separately implemented Apple protocol slice. Use switches, enum choices and
   an adaptive-strength control appropriate to the typed domains. Preserve
   unknown values and multi-byte call-mapping semantics from
   [the protocol audit](apple-settings-followup.md). No raw byte/key editor.
3. For each gesture, obtain fresh state, validate the specific feature and domain,
   active session/private channel/handshake/MODULE ownership, suppress no-op
   writes, and call the existing typed control sender. Display accepted state
   until readback; an IPC send or socket write is not confirmation. Make pending
   intent visually distinct if the typed state exposes it.
4. Dismiss on detach, rebind, system takeover, lost feature/session and failed
   window show. Clear observers idempotently. Disable or dismiss on external-app
   ownership and prevent stale gestures from dispatching. Follow main-thread
   rendering; no timer, service, persistent window or global address singleton.
5. Verify pure projection/request gates, then actual Android rollback and
   lifecycle: repeated open/close, Back, disconnect, ownership handoff, rotated
   or detached host, other device/address, missing host widget and failed show.
   Verify four-mode row survival and advanced-only capability ordering. Check
   day/night rendering, small screens, accessibility and accepted readback on
   device. Unit tests and signed builds do not replace those observations.
6. To reach **full native Settings**, first capture the exact Settings entry and
   plugin-loading contract for the target ROM, discover the preference host and
   address accessor by verified shape, and establish its accepted-state and
   typed-command transport lifecycle. Reuse the existing device/session owner;
   add only the missing host adapter and necessary Settings scope. Prove original
   native-device routing is preserved, no synthetic device identity opens a
   second private socket, and plugin destroy/recreation releases all observers.
   Native preference insertion must cover settings with no stock ROM equivalent;
   the current navigation hook alone cannot supply this. Keep this work separate
   from claiming the MiLink sheet is a native Settings page.

The immediate gap is presentation and lifecycle wiring in Apple's existing card;
the full Settings gap is a host process/plugin contract. Neither warrants a new
general device architecture or a parallel protocol owner.

## Follow-up: official service connection has private transport effects

Further tracing resolves the earlier `mService.connect` uncertainty at source
level. Under `R/xiaomi-bluetooth/sources/`:

1. `com/android/bluetooth/ble/app/headset/BluetoothHeadsetService.java:1803`
   implements binder `connect(BluetoothDevice)`, retains its signature permission
   check, and delegates to `BluetoothPluginHelperService.connectDevice` when the
   plugin service is available. The legacy branch updates a device reference
   count and queues handler message 103, rather than merely returning UI state.
2. `com/android/bluetooth/ble/app/broadcast/BluetoothPluginHelperService.java:113`
   loads `com.android.bluetooth.ble.app.headset.plugin.BluetoothHeadsetServicePlugin`
   with its plugin classloader and invokes the exact `connect(BluetoothDevice)`
   method on its `mBluetoothHeadsetService` singleton.

Under `R/xiaomi-feature/sources/`:

1. `com/android/bluetooth/ble/app/headset/plugin/BluetoothHeadsetServicePlugin.java:3204`
   increments the device reference count, resolves the MMA device, requires the
   connected-device predicate and worker, updates LE connection parameters and
   queues message 103 with arguments 65535/65535.
2. `.../headset/plugin/FragmentHandler.java:1691` dispatches message 103 to
   `mConnectManager.checkAndConnect(device, arg1, arg2)`.
3. `.../headset/control/plugin/ConnectManager.java:393` registers MMA role 1 via
   the existing MiuiMMAService and local callback, initializing its engine if
   needed. `com/xiaomi/bluetooth/mma/plugin/MiuiMMAService.java:578` forwards
   that local-callback overload to MiuiMMARegisterManager.
4. `com/xiaomi/bluetooth/mma/plugin/MiuiMMARegisterManager.java:207-242` keeps
   registration state, returns early if its own engine already knows the device,
   otherwise selects BLE or configured MMA channel and calls `mEngineImpl.connect`.
5. `com/xiaomi/aivsbluetoothsdkplugin/impl/BluetoothEngineImpl.java:120-163`
   creates/reuses engine device state, starts a connection timeout and dispatches
   to `BluetoothSpp.connectSppDevice` or `BluetoothBle.connectBleDevice` according
   to device/channel shape.

This proves a possible additional **MMA SPP/BLE** connection path, not specifically
a second Apple AACP/L2CAP socket. It still rules out treating native fragment
entry as presentation-only. Existing native-engine ownership checks do not know
the HyperEars protocol session. Before module-owned entry, suppress or avoid the
verified unnecessary MMA operation only for the address/session owned by the
module, preserving original permission checks, native-device behavior, profile
connections and host initialization. A hook on every Bluetooth connect method
or blanket service-binding suppression would exceed the established evidence.

## Current Settings APK: entry resolved and smaller native route established

After fresh ADB enumeration, the selected device was checked as `nezha`, model
`25128PNA1C`, build `OS4.0.5.0.XPACNXM`. Read-only `pm path` returned one Settings
base APK. The extracted `/system_ext/priv-app/Settings/Settings.apk` is version
code 37 / version name 17, 120865714 bytes, SHA-256
`2ad1f138457c5f60b045cf41e18babc7e8bcc36c123526f7ddc6ca15b906d655`.
No Activity was launched. Device network endpoints are deliberately omitted.

Ignored local evidence is under
`D:/Backup/Desktop/HyperEars/.analysis/settings-native-2026-10-28/`:
`manifest-targets.json`, `entry-bytecode.json`, `host-bytecode.json` and the two
targeted Androguard inspection scripts. All methods below were read from actual
`classes2.dex`; method names are descriptors from that APK, not inferred hooks.

### Specialized plugin entry now resolved

The manifest declares exported
`com.android.settings.bluetooth.MiuiHeadsetActivityPlugin`, action
`miui.bluetooth.action.HEADSET_SETTINGS_PLUGIN`, distinct from legacy
`MiuiHeadsetActivity` / `miui.bluetooth.action.HEADSET_SETTINGS`.

`MiuiHeadsetActivityPlugin.onCreate(Bundle)` reads real BluetoothDevice,
`COME_FROM`, `bluetoothaddress`, `MIUI_HEADSET_SUPPORT` and virtual-device extras.
It optionally parses the comma-form support string into `mDeviceID`, then always
calls `startQigsawInstaller("java")`; the comma parsing is not the plugin's
separate AirPods pipe-form classifier. Do not infer that comma parsing proves
native device support or rewrite the pipe-form classifier to fit it.

`startQigsawInstaller(String)` checks
`SplitInstallManagerFactory.create(this).getInstalledModules()`. If `java` is
present it directly calls `onSuccessfullyLoad`; otherwise it registers a
QigsawInstaller callback and calls `initInstall` for that module. Consequently,
opening the wrapper can start plugin installation when absent; this audit did
not invoke it. The installed callback also reaches `onSuccessfullyLoad`.

`onSuccessfullyLoad():void` creates an Intent, forwards device, support,
`COME_FROM`, virtual-device fields, address and `DEVICE_ID`, calls
`setClassName(getPackageName(), "plugin.settings.java.JavaActivity")` and
`startActivityForResult`. The hosting package is Settings, despite the extracted
plugin APK's own package metadata. The wrapper finishes through its result/
delayed cleanup and unregisters its install callback in `onDestroy`.

This resolves the outer entry and loading handshake; it does not prove that a
particular runtime ClassLoader contains the plugin before installation. A future
plugin hook must resolve from the actual loaded Activity/host classloader after
successful module loading and verify exact class/method shapes. No broad
ClassLoader interception or guessed obfuscated loader is justified. Specialized
AirPods entry still carries the official MMA side effects traced above.

### Prefer the real generic native device-detail host for advanced settings

The current manifest maps existing action
`com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS` to exported
`com.android.settings.Settings$BluetoothDeviceDetailActivity`, guarded by
`android.permission.BLUETOOTH_CONNECT`. Its fragment metadata names
`com.android.settings.bluetooth.BluetoothDeviceDetailsFragment`.
This is exactly the destination already used by `BluetoothDeviceSettings.open`.
It avoids introducing the AirPods plugin support string and its MMA entry path.

Actual class inheritance is:

```text
BluetoothDeviceDetailsFragment
  -> BluetoothDetailsConfigurableFragment
  -> RestrictedDashboardFragment
```

Verified host contract in `host-bytecode.json`:

- `BluetoothDetailsConfigurableFragment.onAttach(Context)` reads Fragment
  arguments `device_address`, calls `setDeviceAddress`, resolves the actual
  cached device, and initializes its BluetoothDeviceDetailsViewModel. Missing
  address/device follows the original finish/fallback path. Its
  `getCachedDevice(String)` only adds an uncached device when bond state is 12.
- `getDeviceAddress():String` and
  `getCachedDevice():com.android.settingslib.bluetooth.CachedBluetoothDevice`
  are actual no-argument getters; no private address-field guessing is needed.
  Resolve getters by exact return/parameter shape and verify the address against
  accepted module state before binding.
- `BluetoothDeviceDetailsFragment.onCreateView(LayoutInflater, ViewGroup,
  Bundle):View` invokes the Settings preference view implementation.
  `onViewCreated(View, Bundle):void` invokes the configurable superclass and
  adjusts its divider. An after-hook here has the real native PreferenceScreen
  and host Context; it can attach a namespaced typed category with host preference
  constructors. This is native Settings UI, not a popup or standalone app.
- Crucial layout ownership: superclass `onViewCreated` snapshots all existing
  preference keys as CommonBuiltinItem objects into `originalDisplayOrder` and
  `displayOrder`. Its private semantic method
  `constructLayout(DeviceSettingLayout):void` rebuilds from a new layout, tracks
  UI jobs and moves leftover non-generated preferences into
  `invisiblePrefCategory`. A one-time insertion after page creation can therefore
  vanish on a later layout update.
- Minimal robust implementation requires idempotent reconciliation after both
  `onViewCreated` and the verified `constructLayout` boundary. If the owned
  category was moved, detach it from its actual PreferenceGroup parent before
  reattaching to the screen; retain only one instance, preserve every stock item,
  and never mutate `displayOrder` or invent a provider endpoint. Reconcile only
  the current module-owned target and remove the category on lost ownership.
  This is event-driven host adaptation, not hierarchy polling.
- Release state observers, preference callbacks and owned category on view
  destruction; use the actual view lifecycle owner or a shape-checked inherited
  `onDestroyView` seam filtered to this host. The captured concrete fragment's
  own `onDetach` unregisters its Bluetooth callbacks; leave those intact. The
  base does not declare its own onDestroyView in this snapshot, so do not resolve
  it with declared-method-only lookup and silently omit cleanup.

The smallest final-target implementation is thus a Settings host binding plus
the already identified Settings entry/scope/state-consumer additions, using
current session state and existing typed requests. No new destination, provider
service, Apple identity claim or protocol owner is required. CardAdapter remains
responsible for MiLink; the native Settings binding consumes the same typed
features without changing its four-mode row. To support multiple vendors, resolve
the existing Adapter's feature types at this host boundary rather than creating
parallel vendor services.

Remaining acceptance gaps are concrete: current plugin loading was not executed;
native preference constructors/style and native choice dialogs need shape checks;
the new binding is not implemented by this research; actual runtime injection,
screen navigation, layout refresh, teardown, stale-token rejection, ownership
handoff and device readback remain untested. The inspected generic page does not
directly invoke the specialized plugin's MMA path, but this is not a blanket
claim that every unrelated native controller has zero side effects.

## Execution order after clarification

The user clarified that asking whether HyperEars already uses native UI was a question about
existing behavior, not an instruction to stop or change the overall goal. HyperEars already
uses native MiLink cards and in-host details extensions. The resumed implementation therefore
first adds Apple advanced controls through those established CardAdapter/host-details paths,
including an advanced-only carrier patterned after the existing FitClip native slot reuse.
The verified Settings process/plugin contracts above remain research for a concrete future
presentation gap; this audit does not itself authorize describing a new Settings hook as
implemented, necessary for every feature, or a replacement for existing native MiLink behavior.
