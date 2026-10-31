# Sony assignable controls

## V1 Table1 preset assignments

SonyPods pinned `3f701ff8`, `SonyTandemV1Table1Protocol.kt:287–314,525–650`
provides the preset-only contract. V1 function F6 advertises inquiry06; it is not
V2 functionF3/FE and not V2 inquiry03/0E. V1 inquiry03 is control-by-wearing.
The capability body is06/count followed by ordered key/type/default/preset-count
blocks; each preset contains action-count and action/function pairs. V1 functions
02/11/12 mean NC optimizer/volume-up/volume-down, so V2 enum interpretation is
not used. Unknown or truncated nested structures grant no new controls.

Capability F1 plus param F7/F9 provides the current preset vector. Status F3/F5
provides per-key availability (00 enabled,01 disabled). Writes F8 preserve all
other reported slots and follow capability key order. Requests pin the complete
capability definition and current vector; a stale sibling setting or replacement
capability rejects the old request. Valid empty capability/function removal revokes
controls. A parameter outside the key's advertised choices revokes current controls;
malformed packets preserve previously valid evidence. No optimistic feature update
occurs; writes enqueue F2/F6 readbacks on the existing bounded ACK transaction queue.

Native Settings exposes one report-qualified choice per key. The existing Sony
native card/details provides the same preset choices, preserving its owner, address,
session and cleanup policy. V2 extended per-action assignments, wearing behavior,
and auto-power-off are distinct source APIs and are not inferred from this V1 slice.
No new transport, connection loop, polling timer, or per-model name heuristic is added.

## V2 Table1 preset and action assignments

SonyPods `SonyTandemV2Table1Protocol.kt:710–759,1790–1905,2001–2099` and
`SonyCapabilityProbe.kt:1107` provide the separate V2 contract. Support-function
pairs F3/FE select inquiries03/0E. When both are advertised the limitation-aware0E
variant is selected, and all reports from the other inquiry are ignored. The0E
capability includes a limitation byte before key count; that byte is retained in the
complete expected definition. Runtime per-key availability remains mandatory.

A V2 preset capability contains fixed-action count and selectable-action count.
Fixed actions contain action/function pairs; selectable actions contain
operation/default/function-count/function-list. These are not parsed as the V1
single action-count grammar. V2 volume codes23/24 and optimizer11 keep their V2
meaning. The parser rejects truncation, duplicates, unknown enum members and trailing
bytes rather than shifting subsequent key positions.

F7/F9 report presets, F3/F5 report availability, and FB/FD report complete mapping
vectors. Extended mappings contain preset/action-count/action-function pairs indexed
by preset, not physical key order. Inactive presets and report order are preserved.
Action editing resolves each active key by its reported preset and requires its actions
to match the current capability; partial valid reports
revoke previous action evidence. Capability defaults never substitute for reports.
A preset change clears old mapping evidence and queries FA again.

FC action writes preserve all other keys/actions and pin the full capability, current
preset vector and full mapping snapshot. Their readback includes F2/F6/FA. Preset
writes remain F8 and now use the selected inquiry. V1 inquiry06 stays preset-only;
no FC or FA path is enabled for V1. Both versions retain existing Table1 routing,
ACK busy rejection, session reset and native owner/address/session guards.

Native Settings and native details expose selectable action functions only after the
complete report above. Fixed actions are preserved in writes but are not presented
as editable controls. No timer, background refresh loop, transport or name-based
capability selection is added. Wearing and power behavior remain separate APIs.

The 2026-10-30 SonyPods repository comparison corrected the earlier key-index assumption.
The mapping count is bounded by the known preset set, not the six physical keys.
Duplicate preset identities are rejected. Shared active presets use the intersection of
advertised action choices and require availability for every affected key. Tests cover
seven reordered presets, preservation of inactive entries, shared-preset restrictions,
native selection rendering, stale requests and existing V1/V2 regressions.
Hardware verification on WH-1000XM6 remains pending.
