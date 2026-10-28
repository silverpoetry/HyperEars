# ROSELINK configuration-to-port reconciliation

Baseline: `3029c98ead3676f6b5fcf21012cb4d49609c512f`, 2026-10-29.
Machine-readable result: [roselink-port-coverage.json](../inventories/roselink-port-coverage.json).
Input: the existing 48-row [source inventory](../inventories/roselink-model-feature-inventory.json).
Production code was read only; concurrent work after this checkpoint is not counted.

All **45 named headset configurations have an existing adapter route**. The remaining
three rows explicitly exclude the generic RS Common config, generic RS Common v2 config,
and AURAMIC microphone/transmitter config. There are no silently dropped source rows.
The 45 routes comprise 24 manufacturer-qualified RSv2 entries, 12 Legacy/F8 entries,
two ZT entries and seven retained model-specific paths. This is identity/entry coverage,
not a claim that all source widgets, commands or real devices are fully supported.

Each JSON row records its source file/class, adapter ID and entry file, matching kind,
manufacturer prefixes when applicable, implemented bounded features, source widget
candidates still not fully ported, protocol document, test files and hardware caveats.
`source_widget_candidates` describe static UI declarations; they are not executable
protocol proof. Coupled audio/multipoint controls are explicitly marked. Custom EQ,
calibration, ANC curves and a simple preset/level selector are not conflated.

The existing Ceramics X companion transport and Furina battery/noise path retain their
published device-validation scope. Newly added daily controls do not inherit those
results. i5/i7/U/MK2 remain their existing separate paths, and Coral remains its dedicated
implementation. In particular, U already has its six-mode selector and depth controls;
additional source ANC widgets do not erase that existing coverage. Ultra remains an
existing supported product outside these 48 configuration rows and is not counted twice.

## Finite next priorities

1. Complete the already assigned reconciliation of the four old ROSE daily paths
   (MK2, i7, U and Furina) before opening another broad port. Their checkpoint coverage
   intentionally remains the existing subset. Audio/multipoint, language, prompt and
   other additions require each path's own confirmed response grammar.
2. Reconstruct touch mapping once per wire family, then apply exact model/slot/action
   domains. Twenty named rows still declare mapping widgets without a complete mapping
   port. Existing master switches are not counted as mappings. F8, old RS Common and ZT
   must stay separate; preserve per-slot snapshots and stale-definition checks.
3. Finish bounded find/ring contracts for the source configurations that declare them
   but lack a port (12 rows at this baseline). Reuse report-gated start/stop and explicit
   local ownership expiry; do not infer a stop command from generic sender membership.
   This queue overlaps the four old paths and should shrink as their work lands.
4. Investigate the smaller ZT daily gap: HeadFeel prompt volume and i5 game/EQ/audio/
   multipoint/wearing, with sender-specific readback evidence. Existing i5 battery/noise
   code must remain independently validated. i3 hide-search is a source-specific extra,
   not ordinary multipoint control and not a reason to expand the shared transport.

The counts above measure missing source-declared candidates, not ready-to-write features.
Current RSv2 language/wear/depth additions are included already; they are not repeated
as new backlog. Six F8 private-battery candidates remain unproven or explicitly absent
in the selected source handler and should not be promoted merely because a battery
widget exists. AGATE raw2 EQ aliases canonical raw1; that is confirmed state normalization,
not a third writable preset.

OTA, adaptive calibration, ANC curves/extended vendor workflows, recording and account
business functions remain vendor-app handoff candidates unless a separate bounded
workflow is justified. Existing simple ANC modes and source-backed levels remain usable.

## Verification boundary

Static assertions checked all 48 unique input paths, 45 non-null unique adapter IDs,
three explicit exclusions, and entry/document/test-file existence. Production routing
and enum membership were read from the committed checkpoint, rather than mutable work
in progress. Parent reported 1307 passing tests for that checkpoint; this task did not
run Gradle, use a phone or claim additional hardware acceptance. After pending source
ports merge, regenerate the affected rows instead of treating this dated snapshot as
permanent feature truth.
