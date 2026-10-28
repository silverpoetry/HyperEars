# HyperPods-X native / spatial source audit

Snapshot: `018765be94f24a49bf28be26fbb5924a3652018f` in `D:/Backup/Desktop/HyperEars/.analysis/upstreams/HyperPods-X`. Audit 2026-10-28, read-only production; no build/device/remote actions. Source paths below relative to that checkout. Compared HyperEars-full current ownership and previously extracted Xiaomi ROM Java sources. This is source-path evidence, not new hardware acceptance.

## Main result

HyperPods-X supplies genuine Xiaomi native popup and MiLink projection plus a real Android spatial head-sensor submission path. It does NOT supply native-first ownership selection, complete native Settings advanced pages, multi-model semantic API discovery, or fully reusable lifecycle management. Its control backend is an app-owned singleton AACP connection for a bound device; A3441 gates native control projection. Use it as source-backed reference inside existing HyperEars Adapter/ProtocolSession/typed-state/host boundaries, not a replacement app/service architecture.

## Actual native surfaces

- `hook/.../HyperPodsModule.java:31-84`: MiLink main/core/ui and Xiaomi Bluetooth main/ui process gates; real lifecycle hook on MiuiApplication.onCreate; native dialog hooks installed at Activity pre-create. Not general Android/HyperOS discovery.
- `NativePopupHost.java:30-80`: checks request age and PendingIntent creator; on desktop/preview launches actual MiuiFastConnectActivity via FAST_CONNECT_DEVICE_AIRPODS, carrying real battery fields. Else invokes StatusBarController / statusbar.setStatus for native strong-toast island. Native submission success is not proof the popup actually rendered.
- `NativeAirPodsDialogAdapter.java:28-125`: reuses ROM AirPods controller, builds battery/status, updates native labels/connection caption/icons, sets internal animation/connect fields. These are real host views with module artwork, not an independent imitation popup. Exact private fields/methods and template parameters are ROM-specific; build shape/transactional checks around them rather than transplanting blindly.
- `NativeControlHost.java:22-84,106-166`: hooks OfflineFindProvider.isAppleHeadset/getAirPodsState and MiuiBluetoothContentProvider.call; projects app-confirmed battery/ANC through airpodsRepository; retains original provider authorization by proceeding through original get_state before handling owned commands. Uses presentation-only template `1B20`; explicitly avoids persisting AirpodsModel to prevent the platform opening a second AACP socket. check_feature_support grants only confirmed NoiseControl; ConnectL2cap stays false for the app-owned target.
- `NativeControlPanelAdapter.java:64-79,160-223`: creates fourth adaptive item through actual HeadsetSelectCardView add-item API, preserving internal item list/index. Original three choices remain; selection is driven by acknowledged AACP mode, pending writes do not optimistically change it. Real native HeadsetSelectItemView, but API discovery is hardcoded obfuscated `W`, `Z`, `a`, `b`, not semantic/capability discovery. Also calls host height method explicitly.
- Partial-install cleanup is weaker than current HyperEars A4 rollback: after adding the fourth native item, missing tools_text hides it without removing native list entry; on loss of target only sets fourth item GONE, not complete restore of title/list/host visibility. Do not regress HyperEars transactional install/unbind contract.
- `NativeSpatialControlAdapter.java:28-34,70-100`: hooks ProfileContext spatial read/write and callback notifications, routing a native spatial toggle to app-owned renderer. This is principally binary on/off projection, not proof of a native three-way off/fixed/tracking selector.

## Native Settings claim must be bounded

`NativeSettingsAdapter.java:12-49` intercepts MiLink ProfileContext.switchToHeadsetActivity for fresh bound A3441 and opens `com.hyper.podsrx.DeviceSettingsActivity`. `HyperPodsModule.java:92-142` hooks DeviceProfilesSettings/BluetoothDeviceDetailsFragment.onResume and inserts an AndroidX Preference pointing to the same module Activity. `app/.../ui/IosSettingsPages.kt` supplies app UI.

Therefore: actual native Settings entry row / navigation interception exists; complete advanced controls remain separate app pages. This fails the user's final no-per-brand-independent-page objective if adopted literally. Reuse verified service/provider/state semantic boundaries and native host components, while implementing full feature projection in HyperEars' shared native Settings host.

## Ownership and fresh-connection requirement

`PodsService.kt:225-249` opens ClassicTransport immediately after adapter/bound-address checks; no preceding Xiaomi native-support decision. `ClassicTransport.kt:17-40` selects hidden BluetoothSocket constructor signatures for BR/EDR L2CAP/AACP; this is API shape fallback, not native capability preference.

`NativeControlHost.java:106-114`, NativeControlPanelAdapter fresh predicate and NativeSpatialControlAdapter match bound address + connected + heartbeat<=30s + model A3441. These show app ownership and freshness, not that Xiaomi first tried and failed. Fixed model/heartbeat do not establish original platform capability. Merely ensuring ConnectL2cap false in intercepted provider checks is not sufficient proof an already-running native private session has released its socket.

HyperEars-full currently has PlatformReservedHeadsetPolicy plus MiLinkDeviceOwnershipRegistry: original native-admission positive claims sticky SYSTEM, and can promote module owner when platform later recognizes device. That is safer existing structure but its process-level stickiness should be evaluated against the new requirement to make a fresh decision per actual connection; do not add an entirely separate HyperPods-X backend.

Required adaptation: on each genuine new connection, use original Xiaomi device/capability result and existing identity/session boundaries; native usable -> preserve original path; unsupported -> module protocol ownership and native presentation adapter. Evaluate again on next new connection. NO live platform-update monitor or polling for new firmware/plugin/cloud support is called for. Ordinary session-state events/pose watchdogs are distinct from update monitoring. Do not persist counterfeit system identities to force fallback/native admission.

## Spatial path is executable, not merely a fake UI switch

`app/.../device/SpatialAudio.kt:196-232,264-311,342-375` starts explicitly, discovers motion services, sends RtBuddy setup and subscriptions, optionally requests AACP control6 ownership, parses real quaternion samples, calibrates three axes, computes neutral-relative rotation vector and sends via conflated queue to privileged Binder. Requires saved per-address/model/firmware calibration before head tracking; ignores stale samples and limits diagnostic collection. This is substantially more than toggling a displayed boolean.

`hook/.../SpatialHost.java:70-139`:

1. Validates bound address, actual A2DP active route, normal audio mode and compatible audio device.
2. Calls Android Spatializer addCompatibleAudioDevice and desired head tracking mode.
3. Uses Xiaomi `com.android.bluetooth.ble.app.headset.Spatialcontroller.createspatialhid`, then sends orientation through `sendspatialdata`.
4. Sets per-device head-tracker enable + desired mode1 and destroys HID/restores previous tracker preference/mode on stop.
5. Validates caller UID/interface token, pose sequence, <=250ms age, vector finite/shape/norm; Binder death and <=800ms pose timeout stop the tracker.

Local ROM corroboration: `.analysis/andpods-4.3.8/xiaomi-bluetooth/sources/com/android/bluetooth/ble/app/headset/Spatialcontroller.java:5-18` really loads libspatial and declares create/destroy/send native functions; BluetoothPluginHelperService.java:127,142,409 and FragmentHandler.java:1291,2185,2235 use these. This confirms callable platform substrate in the captured ROM. Source comment says create JNI false=success and rotation-vector reorder/scaling were verified in libspatial, but this audit did NOT independently disassemble that native binary; do not promote comment into cross-ROM certainty.

Evidence limitation: nativeActive is set after host allocation/START, mode derives from compatibility + nativeActive. It is not measured audio rendering success. Self-test sends stationary artificial zero poses and explicitly says sensor discovery is not real earbud tracking acceptance (`SpatialAudio.kt` selfTest near end). AudioTrack 5.1 route-checked test audio exists, but subjective/physical tracking, latency/calibration handedness, dynamic sensor attachment and Android actual headtracking mode still require separate real-device verification. No universal all-media spatial renderer implemented in app; Android Spatializer remains renderer.

## Lifecycle / startup findings

Positive guards:

- No head tracker created at boot: service calls spatial.start for query/queue machinery; create/subscribe begins through explicit motion/headtracking/selfTest operation.
- Host START records prior tracker/mode, uses linked owner Binder; watchdog stops on pose timeout, route change or call. STOP idempotence uses empty activeAddress guard.
- stopMotion cancels jobs, joins potentially in-flight START before STOP, drains pose queue, stops subscription and restores AACP ownership or reconnects when prior state unknown.
- Service onDestroy closes native bridge/spatial/conversation/ear detection, socket and scan; marks battery not current and publishes disconnected snapshot (`PodsService.kt:435-445`).

Adaptation risks:

- SpatialAudio.start runs refresh every1500ms for the entire service (`:108`); not a headtracking-only workload. Replace with existing state subscriptions/bounded host queries where supported. User forbids live update monitor; this loop is runtime spatial status polling, not update detection, but should not become such a monitor.
- SpatialHost watchdog every250ms is justified only during active realtime pose session; no sensor/service creation should persist into idle reconnection.
- Native module listeners/receivers and A2DP profile proxy are process-lifetime with no explicit uninstall; Java hook registration can be partially installed before later resolution/install failure. Existing HyperEars scope lifecycle/transactional hook handling should own cleanup.
- SpatialAudio.close launches separate bounded STOP after canceling scope, while host death/pose lease is fallback. Verify stop during pending START before adopting: in this close path it does not explicitly join pending operation like stopMotion does.
- Fixed mode changes compatible-device membership. STOP restores tracker enabled and desired mode only, not prior compatibility membership. Treat user-requested fixed preference and temporary diagnostic mutations separately; do not claim full previous-spatial-state rollback.
- App-owned singleton remote prefs has current address, flags, heartbeat, but lacks HyperEars producer/session/connection revision token. Keep existing typed IPC/state acceptance to prevent stale cross-session commands rather than copy map/PendingIntent protocol.
- PodsService control readback path can close/reconnect the private socket1200ms after unconfirmed writes (`:148-158`). Avoid importing this into generic shared lifecycle without per-command evidence; especially avoid disrupting active spatial samples.

## Recommended reuse within current architecture

Keep adapter/protocol state and the existing single private connection owner; treat NativeControlHost provider projection as a host adapter, not a second device service. Reuse native fourth-item API idea with semantic resolver and A4 transaction/restore; retain native identity separate from artwork/template. Use native Xiaomi-supported path first per new connection, and add private protocol fallback only when unsupported. Project Settings into shared native pages rather than redirect to DeviceSettingsActivity. Implement head motion as independently gated sensor feature + scoped host bridge, with explicit activation, actual route/renderer telemetry and deterministic teardown. No live update monitoring or runtime permanent probes added.
