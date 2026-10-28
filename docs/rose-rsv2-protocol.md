# ROSELINK RS Common v2 model profiles

2026-10-28 source reconstruction from ROSELINK 3.8.2 (5409), Dart 3.11.4 ARM64 AOT.
This extends twenty-four exact configurations independently of Coral. No vendor code, assets,
APK, firmware or private keys are bundled. The existing 53-file configuration inventory
is the index; this document records the additional identity and daily-control contract.

## Automatic identity and transport

Constructor advertisement-prefix lists are four bytes including the manufacturer company
ID in little-endian order. They are not Bluetooth names or the RS/BH product codes.
`DeviceConfig` folds each configuration list into a lookup at0x7a753c;
`identifyDevice` at0x7a7240 prefers12/8/4 hex-character prefixes.
`RBleScanner._advertisementDataToHexString` at0xa88e6c reconstructs manufacturer data.
`NormalEventHandler.scanDeviceToEarObject` at0xa88420 passes this raw evidence into
`buildEarObject`. All twenty-four configs explicitly declare RFCOMM UUID
`0cf12d31-fac3-4553-bd80-d6832e7b3931` and allocate RsCommonV2Sender / Receiver.

| Config | Advertisement prefix | Config offset | Ordinary EQ labels | Noise / other distinctions |
|---|---|---|---|---|
| ceramics-mk2 |0b0e0a0c|0x7b4894|POP,HIFI,ROCK|ANC,off,transparency,wind; audio and touch |
| ceramics-mk2-5736 |45d7b286|0x7b476c|POP,HIFI,ROCK|Same explicit noise list; no audio widget |
| ceramics-mk2-8912f |0b0e208b|0x7b4800|POP,HIFI,ROCK|Explicit noise list; audio and touch |
| openfeel_mk2 |0b0eb657|0x7b525c|Fixed bass-loudness level3|No ANC; four audio modes; touch without ANC action |
| openfeel-l |45d7cbed|0x7b51c8|BASS,BALANCE|No ANC; AAC/SBC and LDAC only |
| sportfeel-8912f |5d2f61f0|0x7b5420|POP,HIFI,ROCK,LIGHT|No noise widget; source touch list includes ANC-cycle action |
| speakfree |0b0f0a0b|0x7b53b4|POP,HIFI,ROCK|Headphone; no touch widget; AAC/SBC,LDAC,aptX,aptX HD,aptX Adaptive |
| speakfeel2 |0b0e3ce6|0x7b52f0|Bass+ levels0/1|Headphone; touch mapping without master switch; AAC/SBC,LDAC,LHDC |
| astrox |0b0e0a0b|0x7b44ec|BASS,BALANCE,BASS+|No noise widget; full touch without ANC action |

HyperEars starts at most one eight-second low-power address-filtered scan per physical
connection for an unknown ROSE name/shared-service candidate. A name or UUID only permits
this probe. Admission requires a bonded, connected, module-owned device, the exact same
classic Bluetooth address, a result newer than scan start and at most15seconds old, plus
an exact manufacturer prefix. Nearby/RPA/companion addresses are never guessed or matched
by name/RSSI. No advertisement is a private capability until the exact profile receives
valid RFCOMM status. Capture cancellation covers timeout, disconnect, native ownership,
vendor-app presence, pause and close. Confirmed evidence is copied and retained for that
physical connection across repeated platform identity observations and voluntary vendor
handoff; disconnect/native ownership ends the identity epoch. A fresh BLE address that
differs from classic MAC remains unqualified. This is source/test evidence, not hardware
proof of all nine products' address behavior.

## Report-qualified daily controls

TX FF sequence payloadLength payload sum8 AA; RX DD sequence top-level LTV sum8 AA.
The strict bounded decoder shares Coral's proven grammar, but owns separate field filters
and per-model queries. Startup reads FA0D0C plus only declared daily feature types:
09 noise,2A EQ,0E game,32 multipoint,2B audio,01 touch,07 master,2F find,2E prompt.
No generic30-byte feature sweep, automatic setting replay or speculative capability flags.

Noise list evidence is explicit NoiseType objects in each Ceramics MK2 constructor:
ANC1/off2/transparency3/wind4. Other profiles never expose ANC based on shared sender.
EQ UI semantic0(Pop/Bass) writes2A01;1(Hifi/Balance) writes2A00;2(Rock/Astro Bass+)
writes2A02;3(Light) writes2A03. The receiver `_handleEQData`0xf0d1c4 swaps one-byte
wire0/1 back to semantic1/0. Astro's Bass+ label is preset2, not Coral's selector5.
Source `DeviceEQTypeModel` objects and `DeviceCustomEqSession` dispatch determine these
mappings; enum position is not substituted for wire semantics. OpenFeel MK2's explicit
bass-loudness selection writes2A0503; SPEAKFEEL2's two-choice Bass+ writes2A05[0/1].

Audio defaults are the four choices established by the Coral audit only where config
leaves its source default list intact. OpenFeel L explicitly narrows to0/1, SPEAKFEEL2
to0/1/2; SpeakFeel uses0/1/4/5/6 and its dedicated receive handler identity mapping.
RsCommonV2Sender aptX/Hd/Adaptive getters at0xc3ab84/0xc3ab2c/0xc374f8 write2B04/05/06.
UI requires inline confirmation before a codec change and waits for actual mode readback.

Touch fields come from each RTouchInfoModel constructor. All eight touch-capable profiles
use full left/right single/double/triple/hold slots. SPEAKFEEL2 field0x23 is false at
0x6cddf8, so07 is neither queried nor enabled there; SpeakFeel has no touch widget at all.
WithoutAnc lists contain0..7, full lists0..8. Prompt levels1..5, game0Ebool, multipoint32bool,
touch01slot/action and07bool use independent valid reports and explicit readback queries.
Find supports manual1left/2right/3both/4stop only;5/6 are received per-side stop reports,
not exposed writes. The details panel owns a60-second lease and stops on close only while
its session/address/ownership still match. No find or codec action occurs at initialization.

Unported separate contracts remain explicit: custom EQ curves, additional gain/hearing profiles,
spatial/ear-adaptive/wear/tap controls, independent mic mode, language and ANC-line tuning.
They are not enabled by shared sender/config inheritance. No OTA, recording or business
feature is added. Tests cover source identity, model-specific enum/readback constraints,
malformed framing, protocol reset, actual scanner callbacks and manager refinement/cache,
nearby/stale/unbond cancellation, UI codec confirmation and bounded find ownership.

## Six further exact configurations and gain/protection (current slice)

All offsets refer to the same archived ROSELINK 3.8.2 AOT evidence, not device captures.
The new constructor prefixes are Mirror `0b0a0a13`, Swift2 `5999064c`, Luminous Agumon
`0b0a34fd`, Gabumon `0b0a2aed`, Patamon `0b0ae916`, Tailmon `0b0a941f`.
Their files are `config/rs_common_v2/{mirror,swift2,luminous_agumon,luminous_gabumon,
luminous_patamon,luminous_tailmon}_device_config.dart` beneath the existing AOT asm root.
Mirror has POP/HIFI/ROCK EQ (constructor pool `0x8108`, semantic indices 0/1/2), game,
multipoint, touch/master, find and prompts. The other five have the latter controls
but no EQ/audio/noise widget. All six are explicitly excluded from noise, codec,
gain/protection parsing and writes. These are candidate profiles until valid reports.
Swift2 declares `head` and a single battery; Luminous declares two bud batteries, not
a case; Mirror declares three components. Projection preserves these differences.

Four existing profiles explicitly allocate gain and hearing-protection widgets:
OpenFeel L, OpenFeel MK2, SpeakFeel2 and Astro X. No other profile inherits them.
In `roselink_bluetooth/commander/sender/rs_common_v2_sender.dart`:

- `setGainLevel` at `0x92f6b0` accepts integer 0..2 and emits `[45,value]`.
- `getGainLevel` at `0x92fdd0` emits `[FA,45]`.
- `setHearingProtection` at `0x896f98` accepts integer 0..6 and emits `[46,value]`.

These constants account for Dart Smi tagging: stored literals 138/140 are wire
0x45/0x46, not 0x8a/0x8c. `widget_model.dart` query getters `0x79ef18` and `0x79ef24`
load pools `0x1e1b8` / `0x1e200`; `pp.txt` resolves them to enums `e14111` / `e140f1`,
and `objs.txt` names those GAIN_LEVEL/HEARING_PROTECTION with strings "45"/"46".
Thus initial bounded widget queries and readbacks include FA45/FA46. Receiver
`_handleGainLevel` (`0xf0a140`) and `_handleHearingProtection` (`0xf09f60`) each require
one byte represented as exactly two hex characters and validate 0..2 / 0..6.
The existing top-level LTV/checksum decoder remains unchanged in structure.

`DeviceHearingProtectionSlider.build` at `0xd64dac` uses
`[null,75,80,85,90,95,100]`; null uses translated "关闭". Native rows and details use
these exact source labels. They denote the earphone setting, not a measured acoustic
exposure or medical guarantee. Gain uses the source's generic three-level selection;
no unsupported low/high mapping or dB figure is invented.

New serialized gain/protection requests carry `expectedModel` and are validated both
by adapter and protocol session against the current reported profile. Missing report,
wrong profile, invalid value or protocol reset rejects writes. Sends never update the
reported selection. Popup and native preference tests check stale-profile rejection
and actual source labels; protocol tests exercise invalid lengths, ranges, all stream
splits, query separation and reset. Six identities are covered by the existing full
catalog identity/admission tests. Test results belong to the coordinated build report;
no phone operations or physical validation were performed in this slice.

## Nine remaining named configurations: bounded ordinary subset

This extends the existing sender/receiver with nine constructor-qualified profiles;
AURAMIC and the generic rs_common_v2 base remain excluded. Source paths are
`asm/roselink/rose_bluetooth/config/rs_common_v2/<config>_device_config.dart` in the
archived ROSELINK 3.8.2 AOT tree. All nine allocate RsCommonV2Sender,
RsCommonV2Receiver and RsCommonV2ReceiveHandler; primary field_33 is the existing
31 service UUID. HSC-2710's field_37/extra 6666/7777 services are not silently
substituted as ordinary transports.

| Config | Prefix | Ported differences |
|---|---|---|
| ceramics_u_hsc_2710 | 0b137957 | Four base noise modes; EQ 3/0/1/2; gain only, no hearing protection; audio 0/1/2; touch/find |
| first_angel | 0b0e64be | Head/aggregate; ordinary EQ 1/0; no audio/noise; touch/find |
| hyacinth | 0b120a0b | EQ 0/1/2; audio 0/1; touch/find; no noise |
| jasmine | 0b0a0a11 | Two bud batteries, no case; no EQ/audio/noise; touch/find |
| ky01 | 0a0b0a0d | EQ 0/1/2; no audio/noise; touch/find |
| magician | 0b0e0a0d | Head/aggregate; audio 0/1/4/5; no EQ/noise/touch; find |
| moka | 68f5b8eb | Head/aggregate; EQ 0/1/2; audio 0/1; no noise/touch/find |
| opal | 0b0a0a12 | ANC/off/transparency only, never wind; EQ 0/1/2; touch/find; no audio |
| zircon | 0b110a0b | Four base noise modes; EQ 0/1/2; audio 0/1/2/3; touch/find |

All have declared game, multipoint and prompt widgets; each still needs its own valid
report. Query construction excludes absent fields and parsing rejects unsolicited
unsupported fields. Opal's noise option list is pool 0x8878 (ANC/normal/trans); HSC uses
0x86e0 and Zircon 0x88f0 (four modes). Audio pools are HSC 0x86a0, Hyacinth/Moka 0x8120,
Magician 0x89a8 and Zircon 0x8710. HSC EQ pool0x8cc0 orders semantic values3/0/1/2;
zh_cn maps eq_custom_LIGHT_01 to "经典调音". First Angel's ordinary list0x8618 orders
HIFI/POP, semantic1/0. UI labels follow semantic values rather than list positions.

### Aggregate battery correction

Previous aggregate projection incorrectly used the first (left) wire component.
The source single-headphone widget selects BatteryType.charge (pool0x80a8).
Receiver `_handleBatteryData` stores the third decoded component into RBatteryData
field_17 at0xf0ce84. The common handler loads that field at0xefa230 and its non-TWS
branch0xefa4cc updates controller charge-display field_27. Speakfree's override
independently reads field_17 at0xef9ed8. Thus Swift2, SpeakFeel, SpeakFeel2 and new
First Angel/Magician/Moka now expose that third component as aggregate; they do not
expose it as a separate charging case or duplicate it into fictional earbuds.
Distinct70/60/50 fixtures, charging and invalid third-byte regressions cover this fix.

### Exact remaining gaps and old-path differences

- HSC-2710 and Zircon advertise extra deep/limit ANC, ANC-line controls, firmware/
  business workflows; their special fields are not inferred from the ordinary 09
  response. HSC gain45 is ported independently of hearing46.
- First Angel's separate custom/game EQ entry0xA0 is declared but its special
  request/readback is not established by the ordinary preset codec; it remains
  excluded, rather than treated as ordinary EQ.
- Language widgets on Hyacinth/Jasmine/KY01/Magician/Zircon, Zircon wearing and
  recording, custom EQ curves and remaining level/calibration workflows remain
  explicit follow-up contracts. Existing vendor handoff is available; no static
  capability is granted for any of these widgets.
- Existing BudsFeel MK2 and EarFeel i7 retain their distinct RFCOMM paths and game
  subsets; their source config declarations are not duplicate new adapters.
  Ceramics U retains its existing six-mode/EQ/depth/tone profile, separate from
  exact HSC-2710 manufacturer identity. Furina keeps its existing exact-name
  BudsFeel path; its RoseLink additional EQ/multipoint/audio/touch/etc. remain a
  difference ledger, not silently inherited from another configuration.

No shared registry change is needed: exact model entries feed the existing
manufacturer-qualified registry. Tests cover model discovery without static controls,
profile query/parse isolation, Opal wind rejection, Moka absent find/touch, HSC gain vs
hearing separation, aggregate correction, and actual popup/native EQ/gain presentation.

## Report-qualified language, wearing and noise depth

Ordinary additions use existing typed state, native Settings choices and the same details
panel. Queries never grant a control, reset removes its report, and every new request
carries the expected exact model. No extra polling or private transport is introduced.

- **Prompt language31:** Chinese0 / English1, read FA31, write31/value. Actual
  RDeviceLangItemWidgetModel allocations occur in ceramics_mk2, ceramics_mk2_8912f,
  hyacinth, jasmine, ky01, magician, speakfree and zircon. The 5736 variant does not
  inherit its siblings' language widget. Sender setEarLanguageEnglish0x7e778c,
  setEarLanguageChinese0x7e7950 and getEarLanguage0x7ee330 establish exact bytes;
  receiver dispatch0xeffdfc and `_handleEarLang0xf0b570` establish report31.
- **Wearing08:** only Astro X and Zircon among these24 profiles allocate
  DeviceDetectionSwitchPanelItemWidgetModel. Sender setEarDetectionOn0x95c108
  writes08/00 and Off0x934590 writes08/01. Receiver dispatch0xeffb7c and
  `_handleInEarDetection0xf0c654` explicitly accept00/01, with00 meaning enabled.
  UI presents ordinary enabled/disabled semantics while retaining this inversion.
- **ANC2C / transparency2D depth:** widget queryCommands0x79ed88 reads enable flags
  field13/17 before requesting these reports. Ceramics MK2/5736/8912F, HSC-2710
  and Zircon enable both. Opal and BudsFeel MK2 explicitly disable both, despite
  sharing default NoiseLevelType lists, so no depth is inferred for them.
  The actual native dialog in widgets/device_widgets/device_anc.dart stores
  LOW/MEDIUM/HIGH values at0xbd4258/0xbd43d8/0xbd4558 and
  0xbd47c8/0xbd494c/0xbd4ad0 as Dart Smi10/6/2: wire5/3/1. This is not enum ordinal
  order. HSC transparency list pool0x8cb8 contains LOW/HIGH only, hence5/1.
  Direct ELF disassembly at0x7825f4 confirms the intent passes these integers to
  RCommander.setAncLevel without remapping. Sender setAncLevel0x816104 and
  setTransLevel0x80e568 use2C/2D and bound1..5. Receiver dispatch0xeffc70/0xeffca4
  delegates to the F8 scalar handlers; the common handler updates anc/trans fields
  independently. This port exposes only each configuration's native options.

Depth writes additionally require the current device-confirmed ANC or transparency
mode, at adapter, session and UI layers. Off/other-mode rows remain disabled; changing
depth does not implicitly change mode. Stale model, mode, address, session and owner
cannot authorize writes. Reports for language, wearing and either depth are independent;
a depth report alone does not advertise noise capability.

The earlier remaining-scope lists are historical gap snapshots: language/wearing/depth
are now implemented only for the exact profiles above. Existing EarFeel i7 and Furina
source configs also declare depth/wearing (and Furina language), but their separate
legacy adapter/session paths still require their own proven format extension. Existing
Ceramics U retains its already ported depth. Special EQ, adaptive/deep ANC, ANC-line,
recording, ear-fit and calibration contracts remain separate; generic and AURAMIC
configs do not grant ordinary headphone controls.

## Four existing adapter paths: manufacturer-qualified source dialect

BudsFeel MK2, EarFeel i7, Ceramics U and Furina remain the same existing adapter
classes and IDs. Name-only candidates retain their previous codec, status query,
settings semantics and initial-unavailable behavior. Exact immutable manufacturer
identity now selects the already reconstructed RS v2 session for that same class:

| Existing adapter ID | Source configuration/prefix | Additional ordinary source profile |
|---|---|---|
| rose-budsfeel-mk2 | budsfeel_mk2 / 0b150a0b | Game, multipoint, AAC/SBC+LDAC+LC3, prompt, touch/find; four base noise modes; no depth/language/wear |
| rose-earfeel-i7 | earfeel_i7 / b8e5290f | Game/multipoint, audio0..3, ordinary EQ0..3, gain/protection, wearing, depth5/3/1, prompt/touch/find |
| rose-ceramics-u | ceramics_u / 0b0d0a0b | Game/multipoint, audio0/1/2, EQ3/0/1/2, gain, language31, ANC5/3/1 + transparency5/1, prompt/touch/find |
| furina-endless-budsfeel | furina / 0b110a0c | Game/multipoint, audio0..3, EQ0/1/2, language31, wearing, both depth5/3/1, prompt/touch/find |

These prefixes occur in config constructors at0x6d0844/0x6cde88/0x6dc9bc/0x6d3970.
Each allocates RsCommonV2Sender/Receiver and the existing31 RFCOMM service. The source
profile is selected only after the global all-family identity parser yields exactly
that existing ID; renamed devices work, conflicting/invalid manufacturer maps do not
fall back to a name-qualified source dialect. A bounded per-physical-connection scan
may refine an unqualified old candidate; refinement retires the old session and token
before opening the new source session, even though the adapter ID remains unchanged.
No generic RS v2 registration duplicates these four classes or IDs.

This isolation is necessary, not merely cosmetic: the existing HyperRose Ceramics U
profile treats07 as prompt language, whereas RoseLink RS v2 explicitly uses07 for touch
master and31 for language. The source-qualified feature contract accepts only the RS v2
typed settings; old RFComm setting requests cannot reach the source session. Both paths
keep report admission and explicit readback. Legacy unqualified captures and behavior
are covered by existing tests rather than being silently reinterpreted.

Source details: MK2 audio pool0x12718 contains enum0/1/3 (AAC/SBC, LDAC, LC3), not LHDC.
i7 EQ pool0x86f8 includes ordinary0..3 plus firmware-qualified EAP6. The zh_cn labels
for0..3 are 弱水经典/原声再现/古典乐器/日系甜美. EAP6 carries EarVersionCheck0x130
and remains excluded until its full version qualification is implemented; custom EQ
also remains separate. Ceramics U's EQ pool0x8cc0 and trans pool0x8cb8 match the
previously audited classic tuning and two-level transparency. Furina constructs all
four audio enum values at0x6d3b98..0x6d3bbc. All four declare full touch actions0..8.

The MK2 BassPlus widget's extra subtype/firmware contract, i7 EAP6, custom EQ curves,
adaptive/extreme ANC, ANC-line and personalization workflows remain finite separate
items. No capability is inferred merely from those declarations. Source-qualified
MK2 still does not receive depth controls: its actual noise widget disables both
level flags. Existing name-only Ceramics U six-mode controls are preserved; source
ordinary four-mode profile does not invent source deep/adaptive control admission.

Regression coverage includes same class/ID qualification, renamed and disabled
adapters, conflicting identities, no capability from metadata, report/reset gates,
07/31 collision isolation, MK2 no-depth/audio-range limits, i7 source labels and actual
native Settings composition using the qualified existing Ceramics U class.
