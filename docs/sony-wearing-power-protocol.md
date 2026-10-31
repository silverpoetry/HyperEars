# Sony wearing playback and automatic power-off audit

## Implemented V1 wearing playback

SonyPods `3f701ff8`, SonyCapabilityProbe.kt1084–1093 explicitly distinguishes
V1 CONTROL_BY_WEARING functionF3/inquiry03 from V2 wearing telemetry. It identifies
bare CommonStatus responses and settingType/value parameters. Gadgetbridge
`86703c8006fc701a81505a28c48e399d1428e0c1`, SonyProtocolImplV1.java458–480 and
1059–1077 independently supplies executable get/set/read handling. Only protocol
facts are independently implemented here; no AGPL implementation is copied.

Function discovery permits F203/F603 queries. F3/F5 status03/value00 means
available (01 unavailable); F7/F9 param03/settingType00/value reports the setting.
F80300/value writes use01 enabled and00 disabled, followed by F603/F203 reads.
Unknown setting types, echoes, malformed shapes, V2 and other outer tables do not
grant this control. Availability and the parameter report are both required.
Typed requests pin version/inquiry and the previous setting; function removal,
reset and stale settings revoke old requests. Native Settings and the Sony details
show a report-authoritative toggle with existing owner/session/address checks.
The behavior is pause when removed, not a live worn/not-worn sensor indicator.

## Remaining automatic power-off and V2 evidence gaps

SonyPods declares V2 wearing-playback functionF1 but has no executable builder or
parser for that setting. Gadgetbridge V2 lines495–517 queries2601 while writing
F801/value; its receiver1116 consumes the SYSTEM F7/F9 subtype01 and uses reversed
polarity. The query crosses a different POWER domain and is not silently rewritten
to F601 without an authoritative query path. Its explicit comment also questions
the reversed polarity. V2 wearing telemetry remains a separate inquiry.

Gadgetbridge V1 automatic-power getters/setters330–353 useF604 andF80401/mode/time,
with a five-byte response at986. V2 equivalents285–307/895 use2605/2805 and a
four-byte response2705/mode/time. SonyPods instead declares PowerInquiredType
AUTO_POWER_OFF04 and contains no corresponding executable automatic-power parser
or setter, so this distinction remains unresolved rather than conflating04 and05.

Gadgetbridge AutomaticPowerOff enum records pairs11/00 off,00/00 five minutes,
01/01 thirty minutes,02/02 one hour,03/03 three hours,10/00 when removed. Its native
settings chooses timed versus removal options using static coordinator capabilities;
that list is not proof that every Sony device with one valid current-mode report
supports every choice. The available source lacks a complete dynamic allowed-mode
and availability capability grammar for these paths. No automatic-power control,
immediate power-off command or name-derived capability has been added by this slice.
