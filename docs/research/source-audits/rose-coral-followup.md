# ROSE Coral follow-up audit

Checked 2026-10-28. Read-only device/package inspection and vendor-app static research;
no production adapter changes, Bluetooth command writes, app launches/stops, UI taps,
settings changes or restarts. User opened ROSELINK and navigated its page.

## Current HyperEars and public-reference status

- Current local HyperEars adapter registry has no exact Coral profile. The generic
  ROSE fallback is not proof of Coral private battery or ANC support. The existing
  `RoseBudsFeelProtocolFamilyAdapter` also accepts its observed UUID regardless
  of retail name; that is a candidate path, not exact Coral support evidence.
- Current audited HyperRose checkout `4e212f79bb1929892e4ac8ae22ed5c2d351e6944`
  has four explicit products: EarFeel i5, EarFeel i7, BudsFeel MK2 and Ceramics U.
  Coral is not in that registry. See `apple-rose-nicehck-followup.md`.
- Existing local source and PR-audit corpus searches found no Coral/珊瑚 integration.
  This is a bounded corpus result, not proof that no public PR exists.
- GitHub API searches failed at the network layer. Browser attempts to open issue
  queries in HyperEars and HyperRose also failed. Search-index results for the
  HyperEars PR list were stale, still listing maintenance PRs that subsequently
  merged. Current issue/PR absence therefore remains unverified.

Primary public reference locations:

- <https://github.com/silverpoetry/HyperEars>
- <https://github.com/DOHEX/HyperRose>
- <https://play.google.com/store/apps/details?id=cn.ikaile.ruoshui.client>

## Verified device and vendor package

Fresh ADB enumeration and properties confirmed Xiaomi `nezha`, model `25128PNA1C`,
Android 17, build `OS4.0.5.0.XPACNXM`. Installed package
`cn.ikaile.ruoshui.client` reports `versionName=3.8.2`, `versionCode=5409`.
The installed base APK was pulled once, read-only, into the original workspace's
ignored `.analysis/roselink-coral/roselink-3.8.2.apk`.

- Size: 50,499,180 bytes.
- SHA-256: `815FA535C2A518479FBD4DD52E8B95FD288692EC8A3299250BA77838630C6075`.
- No account databases, credentials, keys, private preferences or user logs were
  extracted. Raw APK/native artifacts and UI XML remain ignored local evidence.

## Actual Coral page and connection observations

Passive UI hierarchy inspection observed the connected `Coral` device page.
Initial page showed left/right/case battery 93/93/100 percent, `降噪模式`
with description `减少外界噪声干扰`, `等响低音增强`, game mode and dual-device
connection. A later page, after user navigation, showed left/right/case
93/92/100, AAC/SBC, touch customization, laboratory features, find-device,
firmware `050`, and personalized battery notification/connection-popup settings.

These are observed vendor UI entries and state values, not evidence that every
write works or that every hidden laboratory feature is available on Coral.
The hierarchy did not expose the individual ANC choices or their wire values.
No toggle was changed by this audit. ANC capability must not be rejected on the
basis that this is an earclip; the user identification and current vendor UI
positively identify this as the Coral with an ANC control entry.

A filtered `dumpsys bluetooth_manager` inspection independently observed the
Bluetooth name `ROSE Coral`, dual-mode device type, current BR/EDR ACL active and
LE ACL inactive. Personal addresses are omitted. The audio connection alone
is not proof of the control transport, RFCOMM UUID or channel. A separate
read-only diagnostic audit subsequently correlated Coral SDP and socket records:
private RFCOMM UUID `0cf12d31-fac3-4553-bd80-d6832e7b3931`, channel 6. See
[connection investigation](rose-coral-connection-diagnosis.md). That is live
transport evidence, independent of static string co-occurrence. Repeated private
socket reconnects occurred while audio remained connected; per-attempt caller
attribution and root cause are not established.

## Static implementation findings

ROSELINK 3.8.2 is a Flutter application with ARM64 AOT business logic in
`lib/arm64-v8a/libapp.so` (15,991,728 bytes). Both DEX files contain no Coral,
珊瑚 or shanhu string matches; a DEX-only analysis would incorrectly miss support.
The following ASCII strings are directly present in the native binary; offsets
are decimal byte offsets within the extracted `libapp.so`, not function addresses:

- 374976: `CoralDeviceConfig`.
- 551110: `package:roselink/rose_bluetooth/config/rs_common_v2/coral_device_config.dart`.
- 928003: `CORAL`.
- 1350468: `Coral`.
- 1680767: `CoralDeviceConfig.`.
- 1106192: `package:roselink/rose_bluetooth/config/rs_common_v2/rs_common_v2_device_config.dart`.
- 468544: `package:roselink/rose_bluetooth/receive_handler/rs_common_v2/rs_common_v2_receive_handler.dart`.
- 1036305: `package:roselink_bluetooth/commander/receiver/rs_common_v2_receiver.dart`.
- 1220637: `package:roselink_bluetooth/commander/sender/rs_common_v2_sender.dart`.
- 431120: `0cf12d31-fac3-4553-bd80-d6832e7b3931`.

This establishes a dedicated Coral config within the vendor's `rs_common_v2`
source namespace. It supplies a strong, specific next reverse-engineering entry
point. At this initial string-only stage it did not prove inheritance, dispatch wiring,
per-model constants or UUID attribution. The completed AOT section below resolves
those Coral dispatch and endpoint gaps. Several other protocols and
OTA SDKs coexist in the APK, so library/UUID presence is insufficient attribution.
The bundled `assets/ble_analyze.js` actually contains music-service integration;
its filename is not evidence of a Coral Bluetooth parser.

Local reproducible extraction scripts and results are under the original
workspace `.analysis/roselink-coral/`: `inspect_coral.py`, `coral-bytecode.json`
(empty by design after DEX scanning), class/string inventories and
`transport-strings.json`. Do not publish the proprietary APK, native binary or
vendor assets with the interoperability implementation.

## Independent adapter implementation path and remaining gaps

1. Resolve Flutter AOT snapshot metadata for `CoralDeviceConfig`, its constructor
   and parent config. Follow actual calls into the rs_common_v2 sender, receiver
   and connection factory. Determine candidate identity, transport, endpoint,
   initialization, query framing, response lengths and checksums.
2. Establish Coral-specific battery and ANC response fields, valid ranges and
   individual supported ANC values. Keep advertised controls, actual device
   responses and confirmed writable capabilities as separate evidence levels.
   Existing Rose codecs are comparison references, not assumed compatibility.
3. Implement an exact Coral candidate profile and independent protocol session
   only when those facts are established. Private battery and ANC start disabled
   until a valid current-session response confirms each capability. Keep standard
   fallback when identification, transport or parsing fails.
4. Represent game mode, dual-device connection and bass enhancement separately
   with typed feature states and control requests if their protocol is recovered.
   Do not flatten them into ANC or promote generic vendor-lab options to Coral.
5. Retain ROSELINK control ownership/yield behavior. Verify release and reacquisition,
   disconnect/reconnect, stale-state invalidation and bounded query behavior.
6. Build sanitized parser fixtures and adapter-selection tests from actual frames.
   Device control/readback and final native card behavior still need later
   end-to-end revalidation; the current read-only audit does not provide it.

The initial string pass did not establish command bytes or ANC mappings. The
completed AOT section below supersedes those initial gaps; device write/readback
validation and final adapter support remain outstanding.

## Expanded vendor inventory and AOT analysis status

The native Flutter engine identifies Dart `3.11.4` stable, Android ARM64.
A static snapshot inspector was built successfully against that exact runtime using
Blutter `4a60ac648bf448c5a7596437243bcd0b9376fdf0`; no runtime injection is used.
Primary tool reference: <https://github.com/worawit/blutter>.

The first source-string scan found 54 paths; the completed source-file inventory
contains 53 files and 48 family configs (including base configs and firmware
variants) across `f8`, `zt`,
`rs_common`, `rs_common_v2` and shared support files. It is saved as ignored
`model-config-inventory.json`. This count includes base/support configs and must
not be advertised as a retail-product count. Exact model identities, per-config
feature declarations, actual dispatch and sender/receiver semantics remain to
be mapped; Coral has priority within that full vendor coverage audit.

## Completed AOT tracing: exact Coral stack and binary ANC control

Matching Dart 3.11.4 snapshot extraction succeeded; snapshot hash
`78da37fed6bf1489361a312568249f3f`, ARM64 compressed pointers. Local evidence:
`aot-output/asm`, `pp.txt`, `objs.txt`, `only-noise-intent-disasm.txt`.
No generated Frida helper was loaded or executed. Addresses below are native
virtual addresses in this exact vendor build.

- Constructor `0x6d09d8` directly constructs `RsCommonV2Sender`,
  `RsCommonV2Receiver` and `RsCommonV2ReceiveHandler`. CoralDeviceConfig extends
  BaseDeviceConfig, not RsCommonV2DeviceConfig.
- `customConfigEarObject` at `0x7b4a50` sets Coral, `rs-common-v2`, and both
  endpoint fields to `0cf12d31-fac3-4553-bd80-d6832e7b3931`. This establishes
  exact static attribution, corroborated by live SDP/socket records.
- Constructor `0x6d0d80` installs `RDeviceComponentOnlyNoiseBarWidgetModel`,
  together with bass enhancement, game, multipoint, touch, find-device and
  prompt-volume configuration. Its laboratory panel uses
  `DeviceDingLevelPanelItemWidgetModel`; generic shared laboratory functionality
  is not automatically a Coral feature.
- `RDeviceComponentOnlyNoiseBar::build` at `0xcde374` creates a Boolean switch.
  True/false callbacks call the mixin `setOnlyNoiseBar` at `0x782b78`, which
  was independently disassembled because the synthetic mixin is omitted from
  Blutter's named source dump. True calls RCommander.setNoiseModeAnc at
  `0x782a58`; false calls setNoiseModeNormal at `0x771af8`.
- Sender ANC getter `0x8690c8` returns payload `09 01`; normal/off getter
  `0x868338` returns `09 02`. Dart Smi array constants require division by two:
  raw immediate 18 represents integer byte 9.
- Sender transform `0xefc634` creates `FF sequence payloadLength payload checksum AA`.
  Sequence increments and wraps after 255. CommandUtil.checkSum `0x7ee96c`
  sums preceding bytes and retains the low byte. Sequence-zero static
  reconstructions: ANC on `FF 00 02 09 01 0B AA`, off `FF 00 02 09 02 0C AA`.
  These were not sent to a device during this audit.
- Receiver LTV type `09` dispatches to `_handleANCData` at `0xf0cfb0`. Wire
  `01` maps to app state 3 (ANC), `02` to state 0 (off), and unknown values
  are rejected. The Coral switch checks state 3. Shared wire values 03..07
  are not proof of Coral-selectable transparency, wind or adaptive modes.

Additional recovered sender facts, still requiring Coral-specific response/UI gating:

- Game: `0E 00` / `0E 01` at `0xc4a130` / `0xc4a2f8`.
- Multipoint: `32 00` / `32 01` at `0xc3b994` / `0xeddf9c`.
- Bass enhancement: `2A 05 level` at `0xc7c5dc`; sender accepts 0..4, while
  the Coral widget has its own presentation parameter. Exposed range remains
  to be traced separately.
- Prompt volume: `2E level` at `0x931374`; valid UI range still to trace.
- Find stop: `2F 04` at `0x7f69b4`; left/right/both values still to trace.

Existing HyperEars framing and ANC values 1/2 agree with these facts. Family-wide
transparency/wind exposure must not carry into an exact Coral profile merely
because the shared codec recognizes them. Private capability still requires
valid current-session device responses.

The vendor fallback getInfo payload at `0xeef59c` is exactly the same 30 bytes
as HyperEars STATUS_QUERY_PAYLOAD, beginning FA01 and ending 454649. HyperEars'
STATUS_COMMAND 0x1E is the actual payload length, not a separate command opcode.
Coral uses a different vendor initialization policy: BaseDeviceConfig.commander
at 0x79f450 builds [FA] plus featureQueryCommands, and RCommander.getInfo at
0x931fb8 prefers that override over the generic sender list. The shared frame
constructor is reusable; an exact Coral query can select the independently
identified feature types instead of requesting every generic feature.

## Refreshed public query status

Initial direct GitHub access failed. A per-command use of the existing local HTTP
proxy then allowed fresh API searches in the parent audit. HyperEars Coral and
珊瑚, plus HyperRose Coral, including title/body/comments, each returned zero.
This establishes those exact query results, not absence under other names or
unindexed content; it supersedes the earlier blocked-query status above.

## Coral query reconstruction and current codec comparison

BaseDeviceConfig.featureQueryCommands (`0x79f75c`) inserts firmware 0D first,
flattens main and secondary widget declarations, and deduplicates in insertion
order. Exact Coral declarations yield 0C battery, 09 ANC, 2A bass, 0E game,
32 multipoint (default mode), 2B audio, 01 touch mappings, 07 touch enable,
2F find, 0D firmware again, and 2E prompt volume. Coral touch configuration
field 0x23 is true at `0x6d0f54`, so the conditional removal of 07 does not apply.
The resulting static query payload is `FA 0D 0C 09 2A 0E 32 2B 01 07 2F 2E`.
It is framed with payload length 0C. This is reconstructed from code, not a
captured device request. Individual [FA, feature] queries use the same builder.

The current HyperEars RoseBudsFeelMk2WireCodec 30-byte status payload exactly
matches the vendor generic fallback, but Coral's official component override is
narrower. Its FF/SUM8/AA sender and DD response stream grammar are reusable.
STATUS_COMMAND 1E and SET_COMMAND 02 encode lengths in the present helpers;
new variable-size payloads should derive length rather than treat them as opcodes.

RsCommonV2Receiver.process (`0xeff724`) strips DD plus sequence and final checksum
plus AA, preserving LTV lengths before `_splitLtvs`. Single ANC is LTV `02 09 vv`;
single battery is `04 0C left right case`. Multiple LTVs are supported. The vendor
also has malformed-length recovery paths; they are not blanket permission to
accept arbitrary recursive data. Existing HyperEars recursive LTV and extended
checksum recovery are legacy behaviors, not a claim of strict equivalence.

Battery handler `0xf0cb14` reads exactly the first three value bytes as left,
right and case. Each byte's low seven bits are percentage; bit 7 is charging.
The existing codec's low-seven-bit percentage extraction and 0..100 guard agree
with those percentages. Current State.Battery omits the charge bits and
RoseBudsFeelProtocolSession hardcodes all charging flags false; this is missing
information, not evidence the device is not charging. Preserve unknown versus
false correctly when extending typed battery state.

The current session grants every NoiseMode entry after any legal noise response.
An exact Coral profile must instead allow only ANC/OFF, reject unsupported mode
writes, and not remap its earclip shape to absence of ANC. Current-session valid
battery/noise evidence still gates each capability independently.

Coral bass is now traced to three offered values, **0..2**. Constructor stores
level count 3 (`0x6d0af4`); widget passes that count to the level selector
(`0x7ad4bc` / `0x7ad4ec`) and offset -1 (`0x7ad510`). The selector enumerates
positions 1..3 (`0xbcb330` / `0xbcb364`), adds offset on click (`0xbcbcb4`), and
DeviceEqIntent.setEqBass (`0x77b000`) forwards the resulting integer unchanged.
This is narrower than the shared sender's accepted 0..4. The static callback
trace is retained in `bass-intent-disasm.txt`.

Game and multipoint receivers strictly accept one-byte 00/01, rejecting other
payloads (`0xf0c1d0`, `0xf0c018`). Prompt-volume sender accepts 0..5, but its
final Coral UI lower bound still needs confirmation. EQ response parsing accepts
one or two value bytes; the exact bass variant mapping must be retained rather
than flattening every EQ response into a level.

## Additional Coral controls and response constraints

- Prompt volume: Coral offers **1..5**. The sender allows 0..5, but Coral sets
  the prompt-widget support-close flag false; `DeviceDingLevelPanelItem.build`
  at `0xcd9ed8` skips the close capability and `_DingLevelMessageBoxContent`
  offers a five-position level selector with offset zero (`0xd648e0`,
  `0xd648f8`). Thus do not expose mute/zero based on the shared sender alone.
- Find: payloads `2F 01` left, `2F 02` right, `2F 03` both, `2F 04` stop.
  Sender entry addresses: `0x80dc34`, `0x80c124`, `0x7f6b0c`, `0x7f69b4`.
  No audible command was issued.
- Bass response: type 2A with two value bytes `05 level` is the actual bass
  subtype. RsCommonV2ReceiveHandler.eqDataReceived (`0xef85a4`) updates bass
  only for mode 5 with at least two value bytes, reading the second byte.
  A conservative Coral parser requires that shape and level 0..2, rather than
  interpreting unrelated EQ presets as bass or manufacturing a default level.
- Touch mapping: Coral selects `typeFull` and `rsCommonV2TouchList` explicitly.
  Left gesture slots 01/02/03/04 and right 11/12/13/14 mean single/double/triple/
  hold. Write payload is `01 slot action`, from `_setTouchKey` at `0xc33898`.
  Action constants from object pool 8738: 0 none, 1 play/pause, 2 previous,
  3 next, 4 volume up, 5 volume down, 6 game, 7 assistant, 8 ANC cycle.
  These are concrete config declarations, not proof of every action on firmware 050.
- Global touch enable is `07 00` / `07 01`. Touch response type 01 contains
  slot/action pairs, not a flat eight-element action vector. `_handleKeyData`
  at `0xf0c9bc` calls `_keyValuesFromPairs` at `0xf0aa9c`, requiring pair alignment
  and all eight expected IDs. A missing expected ID raises `key id not found`
  in closure `0xf0ad04` and the outer handler drops the report. Independently
  reconstructed parsing should validate all slots and actions atomically,
  reject ambiguous duplicates and preserve the last accepted state on rejection.

The Coral adapter still needs current-session read responses and device
revalidation. No static extraction result is presented as a successful control
operation, a charging-state observation, or acceptance of a maintenance build.

## Firmware and find-response semantics

Firmware handler `_handleFirmwareVersion` (`0xf0c388`) requires at least three
value bytes, reads the first three as unsigned numbers, and concatenates their
decimal representations without dots or padding (`0xf0c5dc` through `0xf0c608`).
For example 00 05 00 would render 050. This is a static example; the observed UI
050 alone is not a capture of those bytes. Store version components separately
and avoid parsing the value as ASCII or silently treating it as semantic version.

Find response type 2F is incremental: 01 sets left finding true; 02 sets right
true; 03 sets both true; 04 clears both; 05 clears left; 06 clears right.
`RsCommonV2ReceiveHandler.findMeReceived` (`0xea7330`) leaves the other side
untouched for individual events. A single exclusive mode enum would lose these
semantics. The senders accept only 01..04; response values 05/06 must not become
new writes. Individual readback can request FA2F through the same query grammar.

Audio mode type 2B uses shared `_handleConnectionMode` (`0xefd9d4`), which parses
the hex-character value as a decimal integer and dispatches it. The UI enum
index 0 is AAC/SBC; global enums include LDAC, LHDC, LC3 and other modes.
Coral's config contains a shared four-option list and an additional false flag;
this does not alone establish four usable codec writes. The live connected page
showed AAC/SBC. Exact control gating and dispatch still need tracing before
exposing codec selection; a strictly validated current-response display is
separate from granting write capability. OTA, vendor cloud/AI entry and
personalized notification settings remain discovered app features, not automatic
maintenance actions or assumed headset protocol controls.

## Resolved Coral audio selection and remaining feature inventory

The earlier audio-control uncertainty is superseded by tracing the actual
Coral widget callbacks. Its constructor uses the four-item list at pool 0x8710:
AAC/SBC, LDAC, LHDC and LC3. `DeviceAudioPanelItem.build`'s dialog builder
`0xcd6f2c` tests membership in this list before creating each selectable entry;
LDAC's membership test is at `0xcd70e8`, callback `0xcd90bc`, confirmation callback
`0xcd91ac`. Selection calls `showAudioChange` (`0xcd7ce4`) and then
`DeviceAudioProtocolIntent.setAudioProtocol` (`0xcd867c`).

The previously ambiguous false flag is not a codec availability flag. At
`0xcd88e0` the intent reads widget field_f, and only when true calls
`ConnectManager.disconnect` (`0x779618`) after sending. Coral supplies false and
therefore skips that app-requested disconnect. The method's initial call
`0x77cac4` is `_trackAudioProtocol`, not a capability test. The vendor optimistically
updates controller field67 after sending; HyperEars should retain its actual
response/readback state policy instead of copying that optimistic UI behavior.

Exact sender payloads: AAC/SBC `2B00` (`0xc3b738`), LDAC `2B01` (`0xc3af58`),
LHDC `2B02` (`0xc3ad8c`), LC3 `2B03` (`0xc3ac50`). These use the established FF
frame wrapper. Readback is `FA2B`; response type2B uses the shared connection-mode
numeric parser. Strict Coral decoding can require exactly one value byte in
0..3. Do not expose the other five global audio enum values. The local raw intent
trace is `.analysis/roselink-coral/audio-intent-disasm.txt` in the original
workspace. All bytes here are statically reconstructed, not captured packets.

The exact Coral constructor's headset feature inventory is battery/charging,
ANC/off, bass, game, multipoint, audio selection, eight touch actions plus touch
enable, prompt volume, find and firmware/OTA. It also declares a More navigation
entry and a Starburst business entry. More opens the secondary feature list
(which contains prompt volume); it is not a new wire command. Starburst extends
`_NoAiIntentMixin` and is an app business entry, not evidence of another headset
control. No Coral constructor declarations were found for wear detection,
transparency, spatial audio, head gestures, hearing test or arbitrary custom EQ.

At the checked backend snapshot, `integration/.../RoseCoralAdapter.kt` already
encodes and reads back game, multipoint, bass, prompt, touch, find and ANC;
`RoseCoralFeatures.kt` contains typed settings/touch/find state, and the codec
handles battery and firmware components. Audio is the next proven control
addition. Firmware updating is a separate still-unimplemented feature: firmware
version display does not cover it. This inventory is a working source snapshot;
concurrent implementation may add the missing entries after this audit.

## Coral OTA pipeline and explicit implementation gap

Coral's constructor `0x6d0a04` selects `DeviceOtaType.ab` (enum index0, object
0xe15e01). `normalBleOtaServiceConfig` (`0x7b3fd4`) returns immediately for index0,
so the BLE service overrides used by other OTA families must not be assigned to
Coral. Its existing endpoint configuration and `ConnectManager.writeOta`
determine the OTA route; this is separate from ordinary FF control setters.

The declared OTA widget leads to `OtaEntry.openOtaPage` (`0xd6d898`),
`DeviceOtaController.prepareOtaEntry`, and only opens the page after entry-ready
checks. `BaseOta.canStartOta` (`0xb4ac30`) checks active preparation ownership,
known current firmware version, loaded server information and available server
OTA information. Server metadata parsing in `_loadOtaInfo` (`0xb45a2c`) includes
`version`, `versionDescribe`, `otaContent` and `encodeData`. Firmware bytes come
through `DeviceService.getOtaContentBytes` / `ApiDeviceService`; no live firmware
request was made during this research.

`AbOta.checkAndStart` (`0xb52f58`) owns the foreground session and obtains
nonempty firmware bytes. For ordinary Coral self/TWS, its normal branch sends
`91 seq 01` and awaits update parameters. `sendOtaIdentification` (`0xb550c0`)
and GUID forwarding belong to separate dongle/microphone/non-self routes and
must not be assigned to Coral merely because they share this class. The
implementation has block acknowledgement, timeout/retry/resync, serial block
writes and reconnect ownership. These constitute a dedicated
updater state machine, not another settings command. Exact AB packet framing,
block size/offset rules, ACK validation and recovery still need complete
reconstruction before an independent updater is implemented.

Do not describe `encodeData` as an established cryptographic signature check:
`compareOtaDataWithEncodeData` (`0xb52d34`, size0x38) returns Future(true) without
comparing anything in this build. `_otaFirmwareMd5Prefix4` computes the first
four MD5 digest bytes for a protocol field; that alone is not signature
verification either. Any device-side authenticity check remains unproven by
these app functions. Firmware provenance, exact model/image targeting and
transfer/recovery logic therefore remain explicit updater requirements. No
firmware download, update request, connection reset or device setting change
was executed.

Further AB framing evidence: `constructData` (`0xb53bd8`) constructs
`opcode, sequence, payload`, advancing sequence modulo256. `sendGetAllInfo`
(`0xb54a1c`) uses opcode92 and payload `01 00 03 00 04 00 06 00`.
`processTLVData` (`0xeb8318`) strips the two-byte opcode/sequence header, then
reads TYPE, LENGTH, VALUE records where LENGTH counts only VALUE bytes.
This layout must not be parsed as the normal control protocol's length-first
records. Parameter record type2 needs at least eleven bytes: offset at0 (32-bit
little endian), block size at4 (32-bit little endian), packet size at8 (16-bit
little endian), and allowed-update at10 (exactly1 means allowed). The compiled
loads are signed for offset/block/packet size; independent validation must
reject impossible values and settle the 0xFFFFFFFF sentinel handling before
implementing transfer arithmetic. In status90 processing, 00 continues blocks,
FF completes, 80 terminates on TWS disconnection; 01/02/03/0B/41 terminate with
errors and40 enters retry/resync. These are static state-machine findings, not
an implemented or exercised updater.
