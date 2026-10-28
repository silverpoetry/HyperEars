# ROSE Ceramics X F8 daily settings

The existing `rose-luli-x-gatt` identity, companion association and GATT endpoint remain
unchanged. `CERAMICS-X-7034` is a separate source configuration and is not included.
Existing four-mode noise controls retain their captured wire semantics. New daily
controls are source-reconstructed and await physical-device revalidation.

## Behavioral references

ROSELINK 3.8.2/code 5409 ARM64 AOT, Dart 3.11.4, supplied for local research:

- `roselink_bluetooth/commander/sender/f8_sender.dart` defines exact reads/writes.
- `roselink_bluetooth/commander/receiver/f8_receiver.dart::process` at `0xefd078`
  dispatches query `27` and notification `28` reports.
- `roselink/rose_bluetooth/receive_handler/f8/ceramics_x_receive_handler.dart`
  constrains the generic F8 sender to this model's implemented domains.
- `roselink/rose_bluetooth/config/f8/ceramics_x_device_config.dart` at `0x6deda0`
  selects F8Sender/F8Receiver and CeramicsXReceiveHandler.

Only independently reconstructed protocol facts and original Kotlin code are included;
no APK, decompiled implementation, firmware or vendor assets are distributed. Existing
ROSELINK application research attribution in THIRD_PARTY_NOTICES applies.

## Exact finite operations

Read: `00 27 01 00 01 FIELD`. Scalar report: `00 27/28 02 00 03 FIELD 01 VALUE`.
Write: `00 COMMAND 01 00 01 VALUE`. Commands below are hexadecimal.

- Game: field `08`, write `25`, values 0 off / 1 on.
- Language: field `0A`, write `29`, values 0 Chinese / 1 English.
- Audio: field `0B`, write `2B`, values 0 multipoint / 1 LDAC / 2 AAC-SBC.
  `getConnectMul` delegates to `getAudioLdac`; multipoint off delegates to AAC-SBC.
  There is one selector and one report, not independently writable codec/multipoint
  toggles. Generic sender LHDC value 3 is not accepted by CeramicsXReceiveHandler.
- Touch master: field `16`, write `33`, values 0 off / 1 on.
- ANC depth: field `11`, write `30`; transparency depth: field `12`, write `31`.
  Values 0 high / 1 medium / 2 low map to the app's 5/3/1 levels, not linear 0–5.
  Writes require both a depth report and a current corresponding noise-mode report;
  changing a level does not change the operating mode.
- EQ: field `04`, report `00 27/28 02 00 04 04 02 00 VALUE`,
  write `00 20 01 00 02 00 VALUE`. Values 0 HiFi / 1 Pop / 2 Rock.
  The receiver's separate `0404` nibble-oriented variant is not generalized from
  incomplete shape evidence; only the normal two-byte bank-zero preset is accepted.

Every response must have exact header, payload length, field width and model domain.
Write echoes and malformed/trailing bytes grant no capability. Independent reports merge
only their own field. Queries never enable controls. Reset clears all setting evidence.
Finite initial probes and per-write readback reuse the existing session; no timers or
background polling were introduced. Audio changes require explicit confirmation and
recheck address/session/ownership and the original audio value before submission.

## Native surfaces and remaining scope

Confirmed daily settings can open a settings-only native card without claiming ANC.
A confirmed noise report retains the four-mode card with an advanced-settings entry.
Both MiLink details and Settings rows use the same typed requests and policy. Unknown
fields remain hidden, unavailable depth controls are hidden outside their current mode,
and ownership or session loss dismisses details without sending cleanup controls.

Touch remapping is not included: the model-specific key payload, slot/action definitions
and query shapes require their own complete reconstruction. Generic F8 find, wearing,
prompt-level or LHDC methods are not automatically model-qualified by their presence.
