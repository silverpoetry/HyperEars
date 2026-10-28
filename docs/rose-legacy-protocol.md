# ROSELINK Explorer and RS Common finite controls

Source: ROSELINK 3.8.2/code5409 Dart3.11.4 AOT, original local
`.analysis/roselink-coral/aot-output`. Independently reconstructed wire facts only;
no vendor assets, firmware or decompiled implementation are included.

## Exact identities and transport

`RoseLegacyIdentityCodec` records Explorer `18840107`; BudsFree i `12cc3e4c`;
Cambrian `0b0c0a0c`; OpenFeel `0b0a0a0b/0c/0d`; OpenFun `0b0a0a0e/10`;
OpenLink `0b0a5b9d`; SportFeel `0b0a0a0f`; SWIFT `0b0b0a0b`.
These are manufacturer prefixes, not inferred retail names. Generic RS-Common
`0a0b0a0b` is excluded. The shared bounded same-connected-MAC observation lane
supplies identity; multiple matching model families remain ambiguous.

Explorer customConfigEarObject `0x7b41a4` sets RFCOMM UUID
`0cf12d31-fac3-4553-bd80-d6832e7b3931` and sppSupport true. The separate7034 value
is otaUuid (EarObject.toMap `0x6c7904/0x6c793c`), not a control GATT service.
Cambrian/OpenFun/OpenLink/SportFeel also explicitly select this SPP service.
BudsFree i/OpenFeel/SWIFT set sppSupport false. Their RS Common enum object
`e14631` has ordinal3 (`objs.txt:39681`); BaseDeviceConfig.normalBleServiceConfig
`0x79d130–0x79d188` selects control service00FE/write00F1/read00F2. Cambrian's
FF12/FF15/FF14 slots are OTA fields, not overrides of these control fields.
BleConnector.send `0xefb800–0xefb838` passes characteristic.properties.field_f;
FlutterCharacteristicProperties.toString `0xcb8234–0xcb8240` names it
writeWithoutResponse. Existing transport candidates therefore try WITHOUT_RESPONSE
then WITH_RESPONSE, with distinct IDs and normal property qualification/cleanup.

## Model-qualified controls

Explorer uses ExplorerSender and ExplorerReceiveHandler over F8. Its OFF write
is raw0 (ExplorerSender `0x868228` delegates to F8 wind setter); reports0/1/2 mean
OFF/ANC/TRANSPARENCY. Raw3 is not accepted. It does not inherit Ceramics X wind.
F8 EQ uses field4 width2 bank0 and preset0 HiFi/1 Pop/2 Rock. Game8, audio11,
touch22, language10, wearing9 and prompt19 are scalar query fields. Writes use
20/25/2B/33/29/26/32 respectively. Audio0=multi,1=LDAC,2=AAC/SBC is one selector.
The Explorer config's query list explicitly includes EQ/game/audio/ear detection/
prompt, with inherited Ceramics X handler domains for shared fields.

RS Common uses FF request/DD response checksum frames, distinct from F8.
RsCommonSender.getInfo `0xeef4c0` requests the15-byte payload
`FA 01 07 08 09 0C 0D 0E 12 2A 2B 2C 2D 2E 33`; this is not the longer BudsFeel
MK2 status query. Field0E is game;2A EQ;2B combined audio/multipoint;07 touch;
2E prompt;33 spatial. Writes contain type,value and checksum. Touch is inverted:
0 enabled /1 disabled (source setTouchOn/setTouchOff). Noise9 uses1 ANC/2 OFF/
3 transparency/4 wind, qualified only for BudsFree i and Cambrian configs.
EQ is available only on BudsFree i/Cambrian (0/1/2) and OpenFeel (0 balance/1 bass).
Audio choices are source bounded: BudsFree i0/1; Cambrian0/1/4; OpenFeel0..4;
OpenFun/OpenLink/SportFeel0/4 only; SWIFT none. Prompt is limited to configs with
the prompt widget; spatial to SportFeel. Every field still needs its own report.
RS battery0C carries three bytes; headset profiles use the third reading as overall,
while TWS profiles retain components. Invalid values grant no battery capability.

## Lifecycle and remaining scope

Model identity permits probes only. Reports populate independent typed fields;
requests carry expectedModel, and session reset clears all reports. Audio changes
require explicit confirmation and fresh session/owner/model/source-value checks.
Native Settings and the native card/details use the same policy. No background
polling or automatic replay is added. Generic RS Common, OTA, touch-action maps,
find/ring, and unqualified depth extensions remain outside this finite slice.
Explorer's private battery shape is not inferred from the RS parser; Android
battery fallback remains available. Source and unit-test work is not physical-device
revalidation, particularly for distinct BLE/classic addresses.

RS Common accepts only source-proven direct field reports, never an inferred aggregate LTV
layout. A checksum-valid payload `02 0E 01` is unknown and cannot grant game control.
The first two battery bytes use seven-bit percentages and high-bit charging; the third
is a plain percentage with charging false. Swift and Cambrian project that third component
to overall battery (`0xef9848`, `0xef962c`, receiver `0xeff3c8`–`0xeff3e0`).
