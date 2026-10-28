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

## Additional F8 manufacturer profiles

All references below are under the same archived `config/f8` and receiver/sender tree.
AGATE constructor `0x6dbe88` declares `18840505`; AGATE X `0x6dba80` declares
`18840506`; Ceramics `0x6df348` declares `18840100` / `18840103`. Their config
routines set sppSupport false, and F8 enum ordinal1 selects control FDB3/FF16/FF17
in BaseDeviceConfig.normalBleServiceConfig `0x79d0dc`. The existing two GATT write
candidates prefer WITHOUT_RESPONSE then WITH_RESPONSE, without copying fixed attribute
handles from the separately validated Ceramics X companion endpoint.

Ceramics X 7034 constructor `0x6de820` declares `18840112`. Its config routine
`0x7b3f38` uses control RFCOMM UUID `0cf12d31-fac3-4553-bd80-d6832e7b3931` and
sppSupport true. `00007034...` is field37/OTA, not a GATT control service. Its retail
display CERAMICS-X is shared with the existing `18840109` configuration, so only its
actual manufacturer prefix selects the new profile. Existing validated companion
identity and transport are retained separately; names and a common F8 sender do not
merge the models.

AGATE and AGATE X use model-specific two-choice EQ labels. Config pools `0x8c98`
and `0x8c68` resolve through `objs.txt` to semantic Rock0/HiFi1 and Rock0/POP1.
The ordinary EQ dispatcher maps semantic0 to F8 raw1 and semantic1 to raw0.
Therefore the native raw0/raw1 labels are HiFi/Rock for AGATE, Pop/Rock for AGATE X.
AgateReceiveHandler `0xef8044` maps raw0 to semantic1 and raw1/raw2 to semantic0.
Raw2 consequently lacks an independently confirmed distinct selection and is normalized
to canonical raw1 when reported, replacing any older raw0 state. Ceramics
also uses this handler despite declaring three ordinary EQ choices. This bounded
port excludes raw2 as a distinct write choice instead of presenting an unproven
separate Rock selection;
Ceramics retains raw0 HiFi/raw1 Pop. Independent EQ reports still gate all writes.

AGATE and Ceramics declare a game widget and can query/report field08; AGATE X does
not, and rejects that field even if unsolicited. None of these three models queries
or accepts noise, audio, multipoint, language, wear or touch-master state. Touch
configuration widgets do not prove the separate master-switch workflow or a complete
mapping schema. Their battery handler is also not inherited: AgateReceiveHandler's
base battery method throws RFunctionLostException; standard Android battery remains.

7034 explicitly selects CeramicsXReceiveHandler and supports the proven F8 EQ/game/
audio/language/touch fields. Its noise field12 uses 0 wind,1 ANC,2 transparency,3 off,
unlike Explorer's off0. Independent depth fields11/12 (hex) map 0/1/2 to high/medium/low;
commands30/31 require the corresponding currently reported mode. Prompt/wear and
private battery are not inferred from the broader shared sender. All new controls use
the existing typed feature/request and native card/Settings details, reset lifecycle,
per-field readbacks and audio confirmation. These four profiles remain source-qualified,
without new hardware validation; OTA and incomplete gesture mappings use vendor handoff.

## RS Common six-gesture mapping

The seven RS Common configurations now expose report-qualified left/right single,
double and triple tap editing. F8 remains separate: its different receiver offsets
and model-specific handler overrides do not establish the same contract.

ROSELINK 3.8.2 `rs_common_receiver.dart`, `_handleKeyData` at `0xefe094`,
extracts six decimal low-nibble characters at hex-string offsets11/15/19/31/35/39.
These correspond to complete-frame byte offsets5/7/9/15/17/19, or direct type01
data offsets1/3/5/11/13/15 (data starts at complete-frame byte4). The decoder admits checksum-valid direct type01 frames
only when all six positions exist. Remaining bytes are retained as opaque evidence;
no slot tags, padding constants or aggregate LTV structure are invented. Source
allows a variable-length body; tests use constructed source-layout fixtures, not
claimed hardware captures. Only the selected low nibble is interpreted, exactly like the source one-character
substring; upper nibbles remain unchanged in the expected raw snapshot. Each selected
low nibble must represent a known decimal action.

The seven configs use `RTouchInfoModel.typeOTT` / pool8a80 (one/two/three).
Their RS sender methods at0xc37000/0xc34ba0/0xc348dc and
0xc341ec/0xc34020/0xc33a60 write `01 slot action` with source slots01/02/03 and
11/12/13. Editing sends only that slot, preserving all others on the device, then
uses the existing query containing FA01 for actual readback. Hold setters exist but
have no corresponding six-slot state evidence and are not exposed.

Pool8a88 supplies actions0=None,1=play/pause,2=previous,3=next,4=volume up,
5=volume down. BudsFree pool8b28 instead uses5=volume up,6=volume down.
OpenFeel's second source list1d708 omits None; without a firmware discriminator,
write choices use the intersection1..5 while reported0 remains valid state.

Requests bind the expected model and the entire direct report data snapshot,
including opaque siblings. Session and adapter both reject stale snapshots; writes
never optimistically mutate state. Mapping-only reports enable the existing native
settings entry, with six finite choices and current owner/address/session checks.
Reset clears the mapping. Source-derived tests cover truncated/invalid reports,
F8 isolation, variant action domains, sibling changes and stale native callbacks.
Device validation remains pending.
