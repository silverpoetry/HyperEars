# ROSELINK RS Common v2 model profiles

2026-10-28 source reconstruction from ROSELINK 3.8.2 (5409), Dart 3.11.4 ARM64 AOT.
This extends nine exact configurations independently of Coral. No vendor code, assets,
APK, firmware or private keys are bundled. The existing 53-file configuration inventory
is the index; this document records the additional identity and daily-control contract.

## Automatic identity and transport

Constructor advertisement-prefix lists are four bytes including the manufacturer company
ID in little-endian order. They are not Bluetooth names or the RS/BH product codes.
`DeviceConfig` folds each configuration list into a lookup at0x7a753c;
`identifyDevice` at0x7a7240 prefers12/8/4 hex-character prefixes.
`RBleScanner._advertisementDataToHexString` at0xa88e6c reconstructs manufacturer data.
`NormalEventHandler.scanDeviceToEarObject` at0xa88420 passes this raw evidence into
`buildEarObject`. All nine configs explicitly declare RFCOMM UUID
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
