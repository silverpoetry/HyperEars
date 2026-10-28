# ROSELINK RS Common v2 model profiles

2026-10-28 source reconstruction from ROSELINK 3.8.2 (5409), Dart 3.11.4 ARM64 AOT.
This extends fifteen exact configurations independently of Coral. No vendor code, assets,
APK, firmware or private keys are bundled. The existing 53-file configuration inventory
is the index; this document records the additional identity and daily-control contract.

## Automatic identity and transport

Constructor advertisement-prefix lists are four bytes including the manufacturer company
ID in little-endian order. They are not Bluetooth names or the RS/BH product codes.
`DeviceConfig` folds each configuration list into a lookup at0x7a753c;
`identifyDevice` at0x7a7240 prefers12/8/4 hex-character prefixes.
`RBleScanner._advertisementDataToHexString` at0xa88e6c reconstructs manufacturer data.
`NormalEventHandler.scanDeviceToEarObject` at0xa88420 passes this raw evidence into
`buildEarObject`. All fifteen configs explicitly declare RFCOMM UUID
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

Unported separate contracts remain explicit: custom EQ curves, gain/hearing protection,
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
