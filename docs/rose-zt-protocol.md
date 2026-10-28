# ROSE ZT219 i3 and ZT231 HeadFeel

These are independent source-qualified candidates, not aliases of the existing i5
adapter. Ordinary controls require their own valid reports. No physical validation is
claimed; existing i5 behavior and captures remain unchanged.

## Identity and transport

ROSELINK 3.8.2/code 5409 local ARM64 Dart AOT references:

- `config/zt/earfree_i3_device_config.dart` constructor `0x6d9fa4` declares manufacturer
  prefixes `f10203` / `010203`; its exact display is `EarFree-i3`. It selects Zt219Sender
  and Zt219ReceiveHandler. Default service `011bf5da-0000-1000-8000-00805f9b34fb`, write
  `00007777-0000-1000-8000-00805f9b34fb`, notify `00008888-0000-1000-8000-00805f9b34fb`
  come from `config/base_device_config.dart` at `0x79d108`. Initial GATT candidates
  prefer WITHOUT_RESPONSE, then WITH_RESPONSE on the same UUIDs with distinct IDs.
  `BleConnector.send` (`0xefb800`/`0xefb838`) passes characteristic field_f to write;
  `FlutterCharacteristicProperties.toString` (`0xcb8234`/`0xcb8240`) identifies it as
  writeWithoutResponse. Existing initial property selection handles fallback; no
  runtime write-mode switching or new transport architecture is introduced.
- `config/zt/headfree_device_config.dart` constructor `0x6da5d4` declares prefix
  `525300020002`; exact display is `HeadFeel`, form `head`. It selects Zt231Sender and
  HeadfreeReceiveHandler. Configuration `0x7b56dc` declares classic transport service
  `0cf12d31-fac3-4553-bd80-d6832e7b3931`; alternate GATT 6666/7777 UUIDs are not opened
  by this bounded port.

Manufacturer matching reconstructs the little-endian company ID plus payload, validates
all bytes and rejects conflicting recognized model prefixes. Names only select candidates;
no generic EARFREE/HEAD name matching or capability inheritance is added.

## Framing and controls

`commander/sender/zt_sender.dart` and `commander/receiver/zt_receiver.dart` establish
`08 EE 00 00 00 GROUP COMMAND LENGTH 00 PAYLOAD CHECKSUM` requests. Length includes
all bytes; checksum is unsigned sum of preceding bytes modulo 256. Responses use `09 FF`,
with group/command at bytes 5/6 and payload at byte 9. A bounded 255-byte streaming
buffer validates checksum/length, handles fragmentation and resynchronizes malformed
frames. Exact payload sizes and model ranges gate semantic reports; write echoes do not.

- EQ: read `02/01`, write `02/81`; 0 Pop, 1 HiFi, 2 Rock.
- Game: read `06/03`, write `06/83`; **0 on, 1 off**, preserving source polarity.
- Audio: read `08/01`, write `08/81`. i3 has 0 AAC/SBC with multipoint, 1 LDAC.
  Its multipoint widget delegates to audio mode; no independent multipoint state exists.
  HeadFeel has 0 AAC/SBC, 1 LDAC, 2 LHDC, 3 combined LDAC/LHDC.
- HeadFeel multipoint: separate read `08/02`, write `08/82`, 0 off / 1 on.
- HeadFeel gain: read `02/03`, write `02/83`, device values 1..3. Sender
  `setGainLevel` (`0x92f744`) maps app indices 0..2 to values 1..3; receiver
  `_handleGainLevel` (`0xf13620`) validates those values. UI labels avoid unproven dB.
- Noise: read `06/02`. The common receiver recognizes four one-hot bytes in order
  ANC/OFF/WIND/TRANSPARENCY. i3 accepts only ANC/OFF/TRANSPARENCY, and its sender writes
  **three** one-hot bytes ANC/TRANSPARENCY/OFF with `06/82`. HeadFeel writes four bytes.
  This deliberate request/response difference follows the model sender and common
  receiver; no guessed three-byte response is accepted.
- i3 battery: read `01/01`, seven-byte component payload, retaining the existing source
  offsets and valid percentages. HeadFeel: read `01/03`, one overall percentage;
  no fabricated left/right/case battery.

Each accepted setting remains independent. Queries do not grant controls; settings-only
reports do not grant noise capability. Writes read back exactly the corresponding field,
never optimistically update reported state, and reset clears all evidence. Typed setting
requests carry the expected model and reject cross-profile, absent-field and out-of-range
writes in both adapter and session. Audio/multipoint native controls require confirmation,
with fresh session, ownership, model and source-value checks before submission.

## Remaining scope

Touch remapping, i3 hide-search behavior, HeadFeel prompt level, adaptive ANC and
firmware-qualified custom EQ require separate full shape/workflow reconstruction.
OTA and calibration stay in the vendor application. Their presence in the shared sender
or widget configuration does not enable controls here. The new files contain original
Kotlin reconstruction of protocol facts; no vendor implementation or assets are shipped.
See the existing ROSELINK application research entry in THIRD_PARTY_NOTICES.

## HeadFeel prompts and qualified existing i5 daily controls

HeadFeel's Zt231Sender.setDingLevel0x931590 writes **0E03/value1..5**, while inherited
getDingLevel0x933768 queries **0E06**. Receiver dispatch0xf11ee4 and
_handlePromptToneLevelHeadfree0xf12a3c return the direct level. 0E03 is not accepted
as a HeadFeel report merely because it resembles the write; i3 does not acquire this
control from the shared sender.

The exact `earfree_i5_device_config.dart` constructor0x6dabd8 has manufacturer prefix
525300010001 and allocates Zt229Sender/ZTReceiver. Its existing
`roseselsa-earfree-i5` adapter class, ID and GATT route are retained. Only the global
unique manufacturer identity selects an immutable source profile; name-only i5 and
the generic EARFREE family preserve their existing battery/noise session. Same-ID
refinement uses the existing connection-bound retirement/new-token lifecycle.
No duplicate generic ZT adapter is registered for this profile.

Its bounded source-qualified daily subset:

- EQ0201/0281 values0..3. Pool0x8bd8 defines source labels 弱水经典、日系柔情、乐器大师、清新空灵.
- Game0603/0683 retains0=enabled,1=disabled.
- Audio0801/0881 values0 AAC/SBC with multipoint,1 LDAC. Zt229 inherits coupled
  setConnectMul/getConnectMul; no independent0802 multipoint control is invented.
- Wearing0C01/0C81:0 enabled,1 disabled. getEarDetection0x95d2fc,
  setEarDetectionOn0x95c15c and Off0x9345e8 establish these exact bytes. Only i5's
  actual DeviceDetectionSwitchPanelItemWidgetModel enables this setting.
- Prompt volume differs from HeadFeel: Zt229 getDingLevel0x9336e0 queries0E03;
  setDingLevel0x9314cc writes0E83 with level minus1. Receiver
  _handlePromptToneLevel0xf12b4c adds1. Wire0..4 is normalized to UI1..5, without
  widening the accepted range. Independent command-specific readback is preserved.
- Battery uses0101's existing seven-component-field layout, not HeadFeel0103.
  Source four-mode noise reports/writes remain separate from i3's three-byte writes.

All fields require their own exact legal report. Writes carry expected source model,
keep selection unchanged until readback, reject cross-model/stale session/owner/address,
and reset removes every setting. Existing native ZT Settings rows and details display
source labels and prompt levels; audio changes continue to require confirmation.

### Bounded find remains a separate contract

ZT source has explicit find/stop setters and0E02 receive handling, including distinct
HeadFeel0E02 versus Zt2290E82 writes. However, the inspected ZtSender query surface
contains no independent read-only find query/getter establishing an initial state.
The receiver passes the find body to model handlers; it is not sufficient to invent
an empty0E02 read or start ringing to establish capability. This slice sends no find
or stop at initialization and does not introduce speculative ring controls. Touch
remapping, ANC tuning and firmware-specific custom EQ likewise remain separate.
