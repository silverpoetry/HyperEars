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
