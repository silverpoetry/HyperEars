# Optional connection media-volume preset

Root technical review approved this bounded D1 slice under the existing full integration goal.
Default is OFF. The existing native popup/super-island modes, battery notification, desktop widget,
Apple wear playback and conversation shaping are already implemented; this adds only the missing
connection-volume preset. Battery speech remains a separate follow-up.

## Evidence and behavior

AndPods4.3.8 local decompilation `service/BackgroundService.java:534–564,1222–1230`
invokes a delayed connection volume action. `util/VolumeUtil.java:61–65,96–97` applies the configured
music-volume percentage using STREAM_MUSIC/max-volume conversion; `activity/AutoAudioSettingActivity.java:479`
displays its setting. This implementation uses original code and the established native runtime.
It deliberately does not restore an old stream volume on disconnect, which could overwrite later
manual changes. It does not reproduce the source's unqualified fixed delay or background service.

The Material and Miuix module settings both expose “连接媒体音量”: OFF, mute and10–100% steps.
RemotePreferences is the existing single source of truth. Only a fresh physical connection may
create one opportunity; replays establish a consumed baseline. Mid-connection settings changes
cancel pending work and never arm a new action. A deadline10s after the physical connectedAt time
bounds route availability and native-admission waiting. No poll/retry loop exists.

The non-UI Xiaomi Bluetooth host receives already-authenticated state in NativePresentationRuntime.
A small coordinator checks producer/physical connection/session, module owner, enabled setting,
HYPEREARS admission and connected state. SYSTEM/external ownership, disconnect/unpair, pause,
producer death/replacement, conversation start, calls and expiry cancel. Logical session replacement
cannot re-arm a consumed physical connection. UNKNOWN may wait for an authoritative admission within
the same original deadline. UI-process copies never apply volume.

The host reuses the existing exact AudioManager.getAudioDevicesForAttributes(USAGE_MEDIA) shape:
nonempty List of AudioDeviceInfo, all A2DP/BLE_HEADSET and exact MAC address (case normalization only).
Immediately before writing it rechecks MODE_NORMAL, fixed-volume restriction, current setting,
owner/session and route. It remembers the original stream index while pending; volume changes
cancel the action. Device/playback/mode/volume events exist only while pending and are unregistered
at completion or cancellation. One deadline runnable bounds them. No focus request, playback,
route change, private command, service or permission addition.

## Files and verification

- Settings enum/store and both existing module settings screens.
- ConnectionVolumeRuntime and AndroidConnectionVolumeHost, wired only into NativePresentationRuntime.
- Coordinator tests: once per connection, replay, settings mid-connection, scope/session replacement,
  delayed admission/route, exact deadline, manual volume change, call/conversation/ownership cancellation,
  no restoration and host failures.
- Host/setting fixtures: route output shape/type/address, index rounding/clamping, preference roundtrip.
- Root exclusively runs Gradle/lint/release after source freeze. No phone operation or hardware claim.

## Verification checkpoint

Combined verification passes 1,424 tests, Release lint/assembly and protocol-test Debug assembly.
Independent review covered source provenance, exact route checks, physical-connection dedup and
cancellation. Device acceptance remains pending; no phone settings were changed.
