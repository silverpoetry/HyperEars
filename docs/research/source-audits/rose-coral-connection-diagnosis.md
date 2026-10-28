# ROSE Coral connection investigation, 2026-10-28

Read-only observation on nezha / 25128PNA1C / Android 17. Device timestamps below
are UTC+08:00; the workstation timezone is UTC+09:00. No installation, restart,
process termination, settings/scope change, UI interaction or private command was
performed. The ongoing Apple UI work is not installed and is not implicated.

## Established facts

- Fresh ADB enumeration and product/model properties identify the target. At
  14:01:09 its clock reported 2026-10-28, Android 17, OS4.0.
- Installed HyperEars is `dev.hyperears` 3.1.0 / 30100, updated 2026-10-27
  20:15:03. This is installed-package evidence, not an exact loaded-code hash.
- RoseLink `cn.ikaile.ruoshui.client` PID 5767 was running; its MainActivity was
  top-resumed during collection.
- A local copy of LSPosed `modules_config.db` plus WAL/SHM reports HyperEars
  enabled for user 0. Its six scopes are `com.android.bluetooth`,
  `com.milink.service`, `com.sony.songpal.mdr`,
  `com.moondroplab.moondrop.moondrop_app`, `cn.ikaile.ruoshui.client`, and
  `com.xiaomi.bluetooth`. Scope membership alone does not establish injection.
- RemotePreferences records and the module's local XML mirror both say
  `yield_to_vendor_control_app=false`, `module_paused=false`, and
  `diagnostic_logging=false`. The local mirror says migration complete and no
  remote write pending. The remote Boolean serialization ends in value byte 00.
- Bluetooth dumps identify ROSE Coral as the sole active A2DP and HFP device.
  A2DP is connected and `mIsPlaying=true`. Its latest retained A2DP connection
  event is 11:36:08.755, followed by active-device selection at 11:36:09.092.
  There is no retained A2DP disconnection around the reported RoseLink opening.
- Coral advertises private RFCOMM UUID
  `0cf12d31-fac3-4553-bd80-d6832e7b3931` and uses channel 6. The socket ring
  records real reconnects while the audio profile remains connected:

  - 13:57:23.181 connected; 13:57:24.563 another connecting event;
    13:57:24.976 and 13:57:25.095 disconnected; 13:57:26.268 connected.
  - 13:57:34.981 connecting; 13:57:35.452 and 13:57:35.544 disconnected;
    13:57:36.589 connected.
  - 13:58:35.456 connecting; 13:58:35.876 and 13:58:35.960 disconnected;
    13:58:37.104 connected.

- Bluetooth API counters include RoseLink `connectSocket=5` to Coral and
  `com.xiaomi.bluetooth connectSocket=15`. These counters are cumulative and
  do not identify the caller of each socket event. The latest-50 API ring had
  already been displaced by unrelated Garmin GATT traffic when inspected.

## Source path and interpretation

`ControlAppCatalog.roseLink` names the correct package. `ControlAppProcessHook`
announces a process-lifetime Binder token from Application.attach; this observes
process survival, not Activity foreground status. `ControlAppPresenceRegistry`
authenticates the package/UID, tracks multiple processes, and removes a token on
Binder death. Merely leaving the app UI is not equivalent to process death.

`EarbudSessionService` passes `settings.yieldToVendorControlApp` into the
connection manager. `EarbudDeviceSession.reconcileExternalControlOwner` only
selects an external owner when that setting is true. Yield cancels connection
work, closes the private channel, and marks external ownership. Owner death can
resume module control. Connection retry otherwise uses bounded recovery via
`ChannelRecoveryPolicy`; socket repetition alone does not identify that loop.

Both current source and the pulled installed 3.1.0 APK's disassembled
`RoseBudsFeelProtocolFamilyAdapter.matches` accept the observed
private UUID regardless of the Bluetooth name, then tries the same RFCOMM UUID.
Consequently Coral is a plausible module private-protocol candidate even though
its retail name is not BudsFeel. The installed DEX method at 0x2bbe44 reads
`serviceUuids`, compares the shared UUID and returns true independently of the
name branch (unless native-system-earbud is set). This is installed-binary path
evidence, not proof that the running process selected that adapter or completed
its handshake. Shared service UUID establishes an endpoint candidate only;
it does not establish Coral's frame grammar, model semantics or compatibility
with BudsFeel feature commands. Coral protocol coverage requires its own
RoseLink configuration/sender/receiver evidence.

The established failure is private RFCOMM connection instability. The claim
that the whole audio Bluetooth link repeatedly disconnected is not supported by
this snapshot. HyperEars/RoseLink competition is a strong hypothesis because
both can use the same endpoint and automatic yielding is explicitly disabled.
It remains a hypothesis: there is no per-attempt caller trace or module session
log tying a HyperEars connect/close to the recorded disconnects.

## Missing evidence and minimum next step

Module diagnostic logging is disabled. A bounded read of current LSPosed module
and verbose logs found no HyperEars presence registration, ownership transition,
or session timeline. Absence of these suppressed logs does not establish a
missing hook. There was no successful injection marker found in the inspected
log tails. Package update time does not prove already-running framework
processes loaded exactly that APK revision.

The smallest prospective test is a separately authorized single-variable
comparison using the existing yield setting, with the same Coral/RoseLink
workflow and simultaneous audio-profile/socket observations. Enabling diagnostic
logging would be an additional setting change and requires its own agreed
capture plan. No such change was made here. Do not add a Coral protocol patch,
change scope, restart framework processes, or claim a fix from this evidence.
If a future capture shows verified controller presence with yielding enabled
but continued module connect attempts, inspect that ownership race. If presence
is missing, inspect hook registration/authentication before altering protocols.

## Local evidence

Ignored local evidence is in `.analysis/rose-connection-20261028/`: the copied
DB family, `module-filtered.log`, `injection-filtered.log`,
`bluetooth-filtered.log`, `bluetooth-target-context.log`, and `api-filtered.log`.
The installed APK and `installed-rose-family-dexdump.txt` preserve binary-path
evidence separately from the uninstalled development branch.
Bluetooth addresses in saved text are replaced by `<MAC>`; no private packet
payloads, credentials, account tokens or keys were collected. Database files
are local diagnostic material and must not be committed or published. The DB
family was streamed through read-only `exec-out cat`; no remote snapshot script
or archive was written. Files were copied sequentially, so live-DB snapshot
atomicity is not guaranteed; agreement with the module XML corroborates the
specific settings reported here.

## User decision

The user explicitly chose to keep the phone unchanged and continue read-only analysis instead
of the proposed temporary yield-setting comparison. Preserve that boundary: no settings,
logging, scope or restart changes were authorized. Static APK/protocol research continues,
but the connection-contention cause remains unconfirmed without additional passive evidence.
