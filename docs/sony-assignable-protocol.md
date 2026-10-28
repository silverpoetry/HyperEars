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
