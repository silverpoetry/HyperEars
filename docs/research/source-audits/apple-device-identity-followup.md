# Apple DeviceInfo and adaptive capability follow-up

Read-only source audit, 2026-10-28. Preparation for the next identity/capability
slice; no protocol implementation, native admission change, build or device test.
Source declarations below are not HyperEars hardware validation.

## Sources and scope

Local evidence root is `D:/Backup/Desktop/HyperEars/.analysis/`:

- **A**: `andpods-4.3.8/decompiled/sources/cn/xiaolongonly/andpodsop/`.
  Primary behavioral reference: `util/liberated/AacpProtocol.java`,
  `AacpDeviceInformation.java`, `AacpFrameDecoder.java` and `AacpProtocolKt.java`.
  Decompiled proprietary application: independently describe protocol facts;
  do not transplant application code or publish private payloads.
- **H**: `upstreams/HyperPods-X/`, pinned
  `018765be94f24a49bf28be26fbb5924a3652018f`.
  `core/protocol/src/main/kotlin/com/hyper/podsrx/protocol/Aacp.kt` is abbreviated
  **H/Aacp** below; its lines 1-4 declare GPL-3.0-or-later and LibrePods ancestry.
- **L**: `upstreams/LibrePods/`, local HEAD
  `89356c08cea3db0b6b54792d9329d09a87cc85fa`.
  `android/app/src/main/java/me/kavishdevar/librepods/` is **L/android** below.
  GPL-3.0-or-later. Secondary schema/model corroboration, not firmware guarantees.
- **Current**: `D:/Backup/Desktop/HyperEars-full/` at audit time.
  Existing `CONTRIBUTING.md` identity/capability separation remains controlling.

## DeviceInfo 0x001d: actual schema and uncertainty

**A/util/liberated/AacpProtocol.java:367-373,390-395** validates the common AACP
prefix and reads the full little-endian opcode at offset 4. Prefix is
`04 00 04 00` (**A/util/liberated/AacpProtocolKt.java:11**).
`parseDeviceInformation`, **AacpProtocol.java:139-200**, establishes:

1. Minimum parser header size is 11 bytes.
2. Bytes 7-8 contain unsigned LE16 length `L`; complete frame size is `9 + L`.
   Consequently `L` includes bytes 9-10 and all following fields, not just the
   text starting at byte 11. It is a header length field, not a trailing length.
3. Offset 6 and offsets 9-10 are skipped. These sources neither name nor validate
   their semantics. Do not label them a confirmed version or enforce invented
   constants. The published LibrePods Pro 2 USB-C example starts with
   `04 00 04 00 1d 00 02 d5 00 04 00`
   (**L/docs/AAP Definitions.md:160-186**); that is one documented example,
   not a general version specification. Its private remainder is not reproduced.
4. Beginning at offset 11, read exactly eleven NUL-terminated strings, including
   empty strings. Constructor order, **AacpDeviceInformation.java:25-56**, is:
   name; modelIdentifier; manufacturer; systemSerialNumber;
   firmwareVersionActive; firmwareVersionPending; hardwareVersion; eaProtocolName;
   leftBudSerialNumber; rightBudSerialNumber; marketingVersion.
5. Then consume exactly 17 bytes for leftIdentifier and 17 for rightIdentifier.
   These are opaque binary segments: embedded zeroes or AACP prefix bytes must
   not terminate/split them. Names are from the data class, not evidence that
   either segment is a key, UUID or ciphertext with a known format.
6. Finally read leftFirstPairingTimestamp and rightFirstPairingTimestamp as two
   NUL-terminated strings; require cursor equal to complete frame size.
   No extra trailer is accepted by this schema. The two tail strings have
   variable length; empty values remain structurally meaningful.

With all strings empty the structural minimum is 58 bytes: 11 header + 13 NULs
plus 34 opaque bytes. This is arithmetic from the parser, not a captured frame.
Missing terminators, truncated identifiers and extra trailing bytes fail its
structural checks. Semantic constraints on timestamps and opaque bytes remain
unknown. `L/docs/device-info.md:13-26` corroborates the first eleven fields but
calls the rest unknown; it does not supersede the stronger AndPods structure.

Decompilation caveat: **AacpProtocol.java:166-169** displays an assignment of the
constructed object followed by unconditional assignment to null. This is a
control-flow reconstruction ambiguity; the field order/checks are visible, but
successful runtime return must be confirmed from bytecode or an actual response
before describing the decompiled Java as executable proof. No such verification
was performed here.

## Framing and typed projection

**A/util/liberated/AacpFrameDecoder.java:36-40,56-59,62-102** waits for nine bytes,
computes `9 + LE16@7`, retains incomplete input, emits exactly that many bytes
and leaves subsequent frames pending. A future HyperEars decoder should implement
that explicit length rule with bounded pending storage/length validation. Do not
search for the next AACP prefix inside a known DeviceInfo body. Keep explicit
fragmented, concatenated, malformed-length and binary-prefix tests on the next
slice's validation list; none were implemented or run in this audit.

**H/Aacp:74-80** instead splits all bytes after offset 6 on NUL, drops blank
fields and picks the first `A[0-9]{4}` match. Therefore a user-controlled name
`A3441` preceding a different true model can become the model. Its firmware
search/fallback is also positional only relative to that guessed match.
**H/Aacp:142-152** has no 0x1d framing branch; it uses next-prefix/current-buffer
fallback, unsuitable as a complete fragmented DeviceInfo framing rule.
**L/android/bluetooth/AACPManager.kt:1282-1314** likewise skips zeroes and removes
one collected string; use it only as corroboration of field names, not a strict
parser. Empty fields and opaque binary bodies invalidate general NUL scanning.

Recommended typed projection: validated `DeviceInformation(name, modelIdentifier,
firmwareActive)` with optional pending/hardware/marketing versions only if needed
by a defined feature. Read/discard serials, opaque identifiers and pairing
timestamps to preserve cursor correctness; do not propagate raw packets, serials,
identifier bytes or proximity keys into events, logs, IPC or UI. Name is editable
display data and must never determine model/capability/native admission. Limit
display length and reject malformed encoding without dumping rejected contents.
Unknown well-formed model strings remain unknown identities; retain generic
confirmed protocol features rather than force a known model.

## Model evidence and adaptive eligibility

**L/android/data/AirPods.kt** supplies these explicit model-to-ADAPTIVE_AUDIO
declarations (candidate capability evidence only):

- AirPods 4 ANC: A3056, A3055, A3057; lines 96-111.
- AirPods 5: A3531, A3532, A3533; lines 114-129.
- AirPods 5 with Wireless Charging Case: A3439, A3440, **A3441**; lines 132-148.
- AirPods Pro 2 Lightning: A2931, A2699, A2698; lines 164-183.
- AirPods Pro 2 USB-C: A3047, A3048, A3049; lines 186-205.
- AirPods Pro 3: A3063, A3064, A3065; lines 208-229.

Models explicitly lacking that declaration in this registry are AirPods 1
(A1523/A1722, lines 50-58), AirPods 2 (A2032/A2031, 60-68), AirPods 3
(A2565/A2564, 70-80), AirPods 4 without ANC (A3053/A3050/A3054, 82-94),
and Pro 1 (A2084/A2083, 151-162). Adaptive volume is a different capability;
its presence on non-ANC AirPods 4 does not establish adaptive listening mode.

AndPods **A/util/AacpListeningModeSupport.java:18-23** allows adaptive for every
non-Beats model, even null identity. This is a broad UI permission rule, not a
model-specific support matrix; do not adopt it as evidence for unknown devices.

The saved installed-application inventory
`andpods-4.3.8/model-inventory.json:1-224` corroborates the eleven AirPods family
entries in LibrePods but additionally retains AirPods Max, Max USB-C, Max 2,
Powerbeats Pro 2, Beats Studio Buds/Plus, BEATS360, Solo Buds, Powerbeats Pro,
BeatsX, Powerbeats 3/4, Solo 3/4/Pro, Studio 3, Fit Pro, Powerbeats Fit, Flex,
Studio Pro, and six HQB entries (AirPods/3/4 and Pro/Pro2/Pro3).
These are application enum/modelIndex records, not observed connected hardware,
nor A#### mappings. No exact DeviceInfo-to-adaptive mapping for these additional
entries was established here. Preserve them as missing/uncertain candidates;
do not erase them or classify all as generic Pro. LibrePods' registry ends at
eleven entries (**AirPods.kt:244-261**) and therefore cannot settle these gaps.

Next-slice policy recommendation, distinct from current behavior:

1. Accept exact model only from structurally validated modelIdentifier field.
2. A source-mapped adaptive model plus a valid same-session listening-mode control
   report can establish the selectable adaptive mode without requiring the user
   to select Adaptive in another app first. A valid OFF/ANC/TRANSPARENCY report
   supplies live control evidence; model declaration supplies candidate range.
3. Continue accepting an actual valid Adaptive value as direct capability
   evidence for otherwise unknown models. Do not infer Adaptive from the name,
   firmware regex, app installation, battery alone or static match alone.
4. Evaluate model/report evidence in either arrival order and reset session
   evidence on disconnect. Firmware-specific failures still require final
   maintenance-version device revalidation.

Current `integration/src/main/java/dev/hyperears/integration/AppleAirPodsAdapter.kt`
lines 100,146-150,171 only latches adaptive after an actual Adaptive report.
The proposed model+report conjunction is intentionally a future change, with
source-backed limits, not a claim that the current implementation already does it.
No native system ownership rule is broadened by this recommendation.

## Handshake and feature flags

AndPods **A/util/liberated/AacpProtocol.java:293-295** emits little-endian words
`0,4,1,2,0,0,0,0`: exactly the current HyperEars 16-byte 1.2 handshake at
`protocol/src/main/java/dev/hyperears/protocol/apple/AppleAapWireCodec.kt:27`.
**H/Aacp:32-33** instead emits 1.3 and attributes it to LibrePods alpha.3/AirPods 5.
That comment is upstream rationale, not a demonstrated necessity for this device;
there is no source-based reason here to silently change HyperEars to 1.3.

AndPods **AacpProtocol.java:414-415**, **H/Aacp:35**, and
**L/android/bluetooth/AACPManager.kt:634-637** emit opcode 0x004d with eight-byte
payload `d7 00 00 00 00 00 00 00`. Current HyperEars uses `ff` in the first byte
(**AppleAapWireCodec.kt:28-29**). The exact delta clears bits 3 and 5 (`0x28`);
bit meanings and model/firmware effects are not established by these sources.
The older **L/docs/AAP Definitions.md:12-28** explicitly documents `ff`, captured
on Pro 2 USB-C/7A305, and associates the packet with conversation awareness and
adaptive transparency. Thus `ff` is not fabricated, while `d7` has three current
implementation references. Aligning to `d7` is a plausible narrowly scoped later
experiment, not a justified claim of universal correctness or an identity-parser
prerequisite. Keep handshake and flag changes separate from this preparation and
require readbacks/revalidation for any future behavioral delta.

## Parent follow-up: decompiler ambiguity resolved with DEX

The extracted AndPods 4.3.8 APK was inspected directly using androguard. In classes.dex,
`AacpProtocol.parseDeviceInformation(byte[])` has 102 instructions. Its successful path constructs
`AacpDeviceInformation` at byte offsets 290/296, passes it through Kotlin Result's constructor at
302, then branches to the common failure check at 334. When that check is false, offset 348 moves
the successful object into the return register, followed by cast/return at 350/354. There is no
unconditional null overwrite on the successful bytecode path; the suspicious Java is a JADX
control-flow rendering artifact.

The DEX also directly confirms minimum length 11, LE16 length at offset 7 plus 9 matching the full
array, and the exact constructor with eleven strings, two byte arrays and two final strings.
This confirms the source parser's successful structural path, not validation against a newly
captured headphone response. Raw instruction evidence and a reproducible inspection script remain
in the original checkout's ignored `.analysis/verification/native-dex/` directory.

A read-only package refresh on the revalidated nezha target confirms the installed AndPods is
still versionName 4.3.8 / versionCode 277. This keeps the analyzed application version aligned
with the user's installed reference; it does not establish hardware packet behavior.
