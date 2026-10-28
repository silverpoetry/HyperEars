# Generic native Settings headset page bridge (B3)

## Captured host contract

Evidence: original checkout `.analysis/settings-native-2026-10-28/Settings.apk`,
`manifest-targets.json`, `entry-bytecode.json`, `host-bytecode.json` and
`widget-contracts.json`; the detailed disassembly inventory is in
`apple-native-settings-followup.md` (generic entry and public preference APIs).
The generic `com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS` route enters
`Settings$BluetoothDeviceDetailActivity` in the Settings package and uses
`com.android.settings.bluetooth.BluetoothDeviceDetailsFragment`, extending
`BluetoothDetailsConfigurableFragment`. This is not the MiLink `:ui` process.
The specialized `MiuiHeadsetActivityPlugin`/Qigsaw `java` route is not used.

The hook resolves exact `onViewCreated(View, Bundle)` and superclass
`constructLayout(DeviceSettingLayout)` signatures before transactionally installing.
Inherited `onDestroyView` and `onDetach` release the binding without suppressing the
host implementation. `getDeviceAddress()` must agree with
`getCachedDevice().getDevice().getAddress()`. The original navigation already sends
`device_address`; no alternate device intent, activity or private service is created.

Native `androidx.preference.PreferenceCategory`, `Preference`, and `ListPreference`
come from the host classloader and host context. All values are nonpersistent and
change callbacks return false until a later accepted report supplies the selection.
After host layout reconstruction, only the added category is detached from its actual
parent and rebuilt; stock categories, display order and listeners are not changed.

## Ownership and state

Settings receives shared-identity broadcasts from the Bluetooth package only. A live
Binder producer, physical-connection metadata, accepted session token/revision,
explicit `HYPEREARS` admission and current `MODULE` ownership are all required.
`SYSTEM` remains sticky within the physical connection. Old scopes, retired sessions,
external-app ownership, producer death, page address changes, pause and view teardown
remove or disable controls. A view-bound receiver and module-settings subscription are
released with the last page. Settings opens no transport and never grants admission.

Consequently, a page opened before the existing native admission handshake completes
has no module group yet; a later authoritative state adds it. This includes Apple
UNKNOWN decisions. The bridge does not turn mere page navigation into native ownership.
It reuses the existing typed control broadcast with the current address/session token.

## Presentation scope

Battery rows show only reported values (aggregate remains aggregate). Standard noise
choices come from current adapter modes, including supported wind mode. Apple uses its
four-value typed listening feature, preserving Adaptive. Honor ANC depth is native and
editable only while actual ANC plus a valid current depth is reported.

Advanced access reuses the existing Apple, Sony, OPPO, Bose, Pudding, QCY, vivo, Huawei,
Samsung, NiceHCK, ROSE Coral/RFCOMM/RS V2 and Technics policy/editor pairs. Their existing
feature-specific state validation, expected definitions, readback and cleanup remain
in force. These editors remain host-themed popup components: B3 native-page foundation
is implemented; B5 conversion of every advanced row to inline native preferences is
separate work. Standalone MiLink-only common game/sound toggles are not newly remapped
by this foundation. No new capability or wire command is inferred from a device name.

`com.android.settings` is an optional declared scope and state consumer; no installed
phone scope, module configuration, service, process or APK was changed by this work.

## Verification scope

`SettingsHeadsetBindingTest` uses real AndroidX Preference 1.2.1 objects under Robolectric
for confirmed-only controls, stale clicks, Apple Adaptive, zero battery, original host
preservation and category reparenting. `SettingsHeadsetStateTest` covers explicit
admission, physical scopes, retired tokens, revisions, native/external owners, producer
death and Settings reply eligibility. Build/test results are reported by the coordinated
root verification batch. These fixtures do not claim a live ROM/device page acceptance.
