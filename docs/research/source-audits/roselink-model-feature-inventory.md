# ROSELINK model and feature configuration inventory

Audit date: 2026-10-28. Static configuration evidence is not working HyperEars
support or verified hardware behavior.

## Evidence and counting rules

Source: installed ROSELINK 3.8.2 (version code 5409), matching Dart 3.11.4 ARM64
AOT analysis. Paths below are relative to `asm/roselink/rose_bluetooth/config/`.
Addresses are virtual code offsets, not APK file offsets. This document contains
independently summarized facts, not vendor implementation, application binaries,
firmware, credentials or private keys.

The extracted directory contains **53 Dart files**, including **48 family config
files**: F8 6, RS Common 8, RS Common v2 31, ZT 3. The other five are
`base_device_config.dart`, `unknown_device_config.dart`, `widget_model.dart`,
`ai_intent_handler.dart`, and `async_command_guard.dart`. Earlier estimates of
54 config paths are not reproduced by this filesystem enumeration. These are
not 48 retail products: generic configs, platform variants and microphones are
included.

The [structured inventory](../inventories/roselink-model-feature-inventory.json)
retains config IDs, display labels, sender allocations, explicit UUID literals
and first direct allocation offsets for every widget class. Entire files were
inspected, including lazy getters; constructor-only inspection misses BudsFeel
MK2, OpenFree and Astro X UI declarations.

`customConfigEarObject` distinguishes internal ID (`field_1b`), display label
(`field_23`), protocol (object offset `0x17`) and form (`field_3f`). Display labels
are not automatically Bluetooth advertising aliases. AURAMIC delegates protocol
configuration to its parent and applies microphone identity at `0x79cdf0`.

A direct widget allocation is a **candidate UI capability**, not proof that it
is reachable for every firmware, that its command is supported, or that an
operation succeeded. Conditional branches and generic configuration code are
not expanded into universal capabilities. Missing direct allocations do not
prove absent features: inheritance and runtime choices can add widgets. A shared
sender or RFCOMM UUID is not model-specific capability evidence.

## HyperEars baseline and gaps

Comparison baseline: `34e90c65c3c7bec0c101124ac5e4b55b3ed8444f`,
[RoseEarbudAdapter](../../../integration/src/main/java/dev/hyperears/integration/RoseEarbudAdapter.kt)
and [compatibility documentation](../../compatibility.md). This records the state
before this round's Coral implementation and is not a live compatibility matrix.

The baseline has explicit adapters for EARFREE i5, Furina Endless Solo of
Solitude, Ceramics X, Ceramics Ultra and BudsFeel MK2, plus EARFREE/EARFEEL and
BudsFeel family probes and standard fallback. ROSE sessions encode refresh and
standard noise controls, and publish battery/noise state. Ceramics X retains
system aggregate battery. These sessions do not encode EQ, Bass+, game mode,
multipoint, audio options, touch mappings, find-device, language, prompt level,
gain, hearing protection, wear detection, fit tests, spatial controls, recording
or microphone controls. These are definite integration gaps where a specific
configuration below declares the corresponding candidate UI. Wire semantics
and hardware acceptance remain separate evidence requirements.

Coral is the priority gap: ID `coral`, display `Coral`, RS Common v2,
constructor `0x6d09d8`, config routine `0x7b4a50`, sender allocation `0x6d0a30`.
Its direct UI includes component display, Bass+, game, multipoint, audio, touch,
find, prompt level and **OnlyNoiseBar**. Do not infer transparency, wind or
adaptive ANC from the shared sender or baseline family's `NoiseMode.entries`
publication. Wire details belong in the [Coral audit](rose-coral-followup.md).

Further classification and feature gaps:

- EARFREE i3 uses `Zt219Sender`, i5 uses `Zt229Sender`, HeadFeel uses
  `Zt231Sender`. EARFEEL i7 explicitly selects RS Common v2. Product-line names
  do not establish a single protocol family.
- F8 includes a separate `ExplorerSender`. Ceramics X and Ceramics X 7034 have
  separate config IDs; neither shared sender nor UUID proves equivalence to the
  already validated Ceramics X endpoint.
- Ceramics MK2 has ordinary, 5736 and 8912F configs with one display label;
  Ceramics Ultra has ordinary and HSC-2710 configs. Audio, language and automatic
  ANC declarations differ; preserve per-variant constraints.
- SportFeel occurs as RS Common `sportfree` and RS Common v2 `sportfeel-8912f`.
  OpenFeel is the RS Common `OpenFree` display label; MK2 and L are separate v2
  configs. SpeakFeel and SPEAKFEEL use `speakfree` and `speakfeel2` respectively.
- Agumon, Gabumon, Patamon and Tailmon have distinct Luminous IDs. Shared widgets
  do not establish equal firmware or Bluetooth aliases. The FURINA label does
  not establish that every edition is validated Endless Solo of Solitude.
- Astro X declares spatial audio, ear-adaptive and tap controls. EarFeel i7
  declares gain, hearing protection and dynamic automatic ANC. Speakfeel2 has
  independent microphone mode; Zircon has recording. These need distinct typed
  state/control contracts rather than additional standard noise modes.
- Generic RS Common v2 includes microphone status, gain, channel, timecode,
  storage, TX recording/noise/tone and remote controls, plus local recording,
  head control and ear-tip fit UI. These generic/dynamic branches are research
  leads, not capabilities to assign to all RS Common v2 headphones.
- AURAMIC is a `mic` identity with inherited RS Common v2 widgets, USB-HID and
  dedicated OTA paths. It is not a TWS model. Firmware updates, recording,
  storage and cloud/business entries need separate scope and lifecycle/data
  handling; UI presence does not establish an ordinary local headset control.

Implementation order: establish model/variant evidence, select bounded read-only
protocol probes, decode legitimate per-feature state, then expose confirmed
controls through typed requests and feature state. Device revalidation remains
distinct from static inspection, tests and signed builds. This inventory performs
no device operations and changes no production code.

## Full configuration inventory

Each entry records exact class names and first widget allocation offsets.
OptionPanel and More are containers/navigation; OTA and business entries are
not equivalent to locally implemented hardware controls. UUID literals remain
in JSON because their roles depend on store/call context. Generic configurations
are retained as generic configurations, not promoted to retail models.

### f8

#### agate

- Source: `f8/agate_device_config.dart`;
  `AgateDeviceConfig` extends `BaseDeviceConfig`.
- Display: `AGATE`;
  protocol: `f8`; form: `tws`.
- Config routine: `0x7b3e54`.
- Sender allocations: `F8Sender` at `0x6dbed4`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6dbfdc`.
  - `DeviceEqPanelItemWidgetModel` at `0x6dc004`.
  - `DeviceOptionPanelWidgetModel` at `0x6dc060`.
  - `DeviceGamePanelItemWidgetModel` at `0x6dc078`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6dc098`.

#### agatex

- Source: `f8/agate_x_device_config.dart`;
  `AgateXDeviceConfig` extends `BaseDeviceConfig`.
- Display: `AGATE X`;
  protocol: `f8`; form: `tws`.
- Config routine: `0x7b3ea0`.
- Sender allocations: `F8Sender` at `0x6dbacc`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6dbbd4`.
  - `DeviceEqPanelItemWidgetModel` at `0x6dbbfc`.
  - `DeviceOptionPanelWidgetModel` at `0x6dbc58`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6dbc70`.

#### ceramics

- Source: `f8/ceramics_device_config.dart`;
  `CeramicsDeviceConfig` extends `BaseDeviceConfig`.
- Display: `CERAMICS`;
  protocol: `f8`; form: `tws`.
- Config routine: `0x7b3eec`.
- Sender allocations: `F8Sender` at `0x6df374`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6df430`.
  - `DeviceEqPanelItemWidgetModel` at `0x6df45c`.
  - `DeviceOptionPanelWidgetModel` at `0x6df4b8`.
  - `DeviceGamePanelItemWidgetModel` at `0x6df4d0`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6df4f0`.

#### CERAMICS-X-7034

- Source: `f8/ceramics_x_7034_device_config.dart`;
  `CeramicsX7034DeviceConfig` extends `BaseDeviceConfig`.
- Display: `CERAMICS-X`;
  protocol: `f8`; form: `tws`.
- Config routine: `0x7b3f38`.
- Sender allocations: `F8Sender` at `0x6de85c`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6de918`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6de944`.
  - `DeviceEqPanelItemWidgetModel` at `0x6de98c`.
  - `DeviceOptionPanelWidgetModel` at `0x6de9e8`.
  - `DeviceGamePanelItemWidgetModel` at `0x6dea00`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6dea20`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6dea5c`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6dea8c`.
  - `RDeviceLangItemWidgetModel` at `0x6deaa4`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0x6deb3c`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6deba8`.

#### CERAMICS-X

- Source: `f8/ceramics_x_device_config.dart`;
  `CeramicsXDeviceConfig` extends `BaseDeviceConfig`.
- Display: `CERAMICS-X`;
  protocol: `f8`; form: `tws`.
- Config routine: `0x7b411c`.
- Sender allocations: `F8Sender` at `0x6dedf8`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6deeb4`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6deee0`.
  - `DeviceEqPanelItemWidgetModel` at `0x6def28`.
  - `DeviceOptionPanelWidgetModel` at `0x6def84`.
  - `DeviceGamePanelItemWidgetModel` at `0x6def9c`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6defbc`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6deffc`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6df02c`.
  - `RDeviceLangItemWidgetModel` at `0x6df044`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0x6df0dc`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6df148`.

#### EXPLORER

- Source: `f8/explorer_device_config.dart`;
  `ExplorerDeviceConfig` extends `BaseDeviceConfig`.
- Display: `EXPLORER`;
  protocol: `f8`; form: `tws`.
- Config routine: `0x7b41a4`.
- Sender allocations: `ExplorerSender` at `0x6db3ac`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6db468`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6db494`.
  - `DeviceEqPanelItemWidgetModel` at `0x6db4e0`.
  - `DeviceOptionPanelWidgetModel` at `0x6db53c`.
  - `DeviceGamePanelItemWidgetModel` at `0x6db554`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6db574`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6db5b4`.
  - `RDeviceFindItemWidgetModel` at `0x6db5e4`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6db5fc`.
  - `RDeviceMoreItemWidgetModel` at `0x6db614`.
  - `RDeviceLangItemWidgetModel` at `0x6db62c`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6db6d4`.
  - `DeviceDetectionSwitchPanelItemWidgetModel` at `0x6db7d0`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6db850`.

### rs_common

#### budsfree

- Source: `rs_common/budsfree_device_config.dart`;
  `BudsfreeDeviceConfig` extends `BaseDeviceConfig`.
- Display: `BudsFree i`;
  protocol: `rs-common`; form: `tws`.
- Config routine: `0x7b4228`.
- Sender allocations: `RsCommonSender` at `0x6d9bd8`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d9c84`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6d9cb0`.
  - `DeviceEqPanelItemWidgetModel` at `0x6d9cf8`.
  - `DeviceOptionPanelWidgetModel` at `0x6d9d54`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d9d6c`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6d9d8c`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d9dc0`.

#### cambrian

- Source: `rs_common/cambrian_device_config.dart`;
  `CambrianDeviceConfig` extends `BaseDeviceConfig`.
- Display: `Cambrian`;
  protocol: `rs-common`; form: `head`.
- Config routine: `0x7b4274`.
- Sender allocations: `RsCommonSender` at `0x6d67d0`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d687c`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6d68a8`.
  - `DeviceEqPanelItemWidgetModel` at `0x6d68f4`.
  - `DeviceOptionPanelWidgetModel` at `0x6d6950`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d6968`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d6988`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6d69c4`.
  - `RDeviceFindItemWidgetModel` at `0x6d69f4`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d6a0c`.
  - `RDeviceMoreItemWidgetModel` at `0x6d6a24`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0x6d6abc`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6d6b28`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d6c1c`.

#### OpenFree

- Source: `rs_common/openfree_device_config.dart`;
  `OpenfreeDeviceConfig` extends `BaseDeviceConfig`.
- Display: `OpenFeel`;
  protocol: `rs-common`; form: `tws`.
- Config routine: `0x7b42fc`.
- Sender allocations: `RsCommonSender` at `0x6d521c`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0xc38784`.
  - `DeviceEqPanelItemWidgetModel` at `0xc387b0`.
  - `DeviceOptionPanelWidgetModel` at `0xc3880c`.
  - `DeviceGamePanelItemWidgetModel` at `0xc38824`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0xc38844`.
  - `DeviceAudioPanelItemWidgetModel` at `0xc38888`.
  - `RDeviceTouchSettingItemWidgetModel` at `0xc388b4`.

#### openfun

- Source: `rs_common/openfun_device_config.dart`;
  `OpenfunDeviceConfig` extends `BaseDeviceConfig`.
- Display: `OpenFun`;
  protocol: `rs-common`; form: `tws`.
- Config routine: `0x7b4348`.
- Sender allocations: `RsCommonSender` at `0x6d6364`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d6410`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d643c`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d645c`.
  - `DeviceOptionPanelWidgetModel` at `0x6d64e8`.
  - `RDeviceFindItemWidgetModel` at `0x6d6500`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d6518`.
  - `RDeviceMoreItemWidgetModel` at `0x6d6590`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d6678`.

#### openlink

- Source: `rs_common/openlink_device_config.dart`;
  `OpenlinkDeviceConfig` extends `BaseDeviceConfig`.
- Display: `OpenLink`;
  protocol: `rs-common`; form: `tws`.
- Config routine: `0x7b43a0`.
- Sender allocations: `RsCommonSender` at `0x6d5f04`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d5fb0`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d5fdc`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d5ffc`.
  - `DeviceOptionPanelWidgetModel` at `0x6d6088`.
  - `RDeviceFindItemWidgetModel` at `0x6d60a0`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d60b8`.
  - `RDeviceMoreItemWidgetModel` at `0x6d6130`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d6218`.

#### RS-Common

- Source: `rs_common/rs_common_device_config.dart`;
  `RsCommonDeviceConfig` extends `BaseDeviceConfig`.
- Display: `RS-Common`;
  protocol: `rs-common`; form: `tws`.
- Config routine: `0x7b43f8`.
- Sender allocations: `RsCommonSender` at `0x6df908`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6df9b4`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6df9e0`.
  - `DeviceEqPanelItemWidgetModel` at `0x6dfa2c`.
  - `DeviceOptionPanelWidgetModel` at `0x6dfa88`.
  - `DeviceGamePanelItemWidgetModel` at `0x6dfaa0`.
  - `RDeviceFindItemWidgetModel` at `0x6dfac0`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6dfad8`.
  - `RDeviceMoreItemWidgetModel` at `0x6dfaf0`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6dfb78`.
  - `DeviceDetectionSwitchPanelItemWidgetModel` at `0x6dfc74`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6dfcf4`.
  - `DeviceSpatialSwitchPanelItemWidgetModel` at `0x6dfd78`.

#### sportfree

- Source: `rs_common/sportfree_device_config.dart`;
  `SportfreeDeviceConfig` extends `BaseDeviceConfig`.
- Display: `SportFeel`;
  protocol: `rs-common`; form: `tws`.
- Config routine: `0x7b4448`.
- Sender allocations: `RsCommonSender` at `0x6d596c`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d5a18`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d5a44`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d5a64`.
  - `DeviceOptionPanelWidgetModel` at `0x6d5af0`.
  - `RDeviceFindItemWidgetModel` at `0x6d5b08`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d5b20`.
  - `RDeviceMoreItemWidgetModel` at `0x6d5b98`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d5c80`.
  - `DeviceSpatialSwitchPanelItemWidgetModel` at `0x6d5d04`.

#### swift

- Source: `rs_common/swift_device_config.dart`;
  `SwiftDeviceConfig` extends `BaseDeviceConfig`.
- Display: `SWIFT`;
  protocol: `rs-common`; form: `head`.
- Config routine: `0x7b44a0`.
- Sender allocations: `RsCommonSender` at `0x6d9848`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d98f4`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d9920`.
  - `DeviceOptionPanelWidgetModel` at `0x6d9980`.
  - `RDeviceFindItemWidgetModel` at `0x6d9998`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d99b0`.

### rs_common_v2

#### astrox

- Source: `rs_common_v2/astro_x_device_config.dart`;
  `AstroXDeviceConfig` extends `BaseDeviceConfig`.
- Display: `Astro X`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b44ec`.
- Sender allocations: `RsCommonV2Sender` at `0x6d2048`.
- Direct widget candidates:
  - `DeviceDetectionSwitchPanelItemWidgetModel` at `0x6d20d8`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d20fc`.
  - `DeviceOptionPanelWidgetModel` at `0x6d2170`.
  - `DeviceEarAdaptivePanelItemWidgetModel` at `0x6d2188`.
  - `RDeviceComponentDisplayBarWidgetModel` at `0xc38a0c`.
  - `RDeviceSpatialAudioBarWidgetModel` at `0xc38a54`.
  - `DeviceEqPanelItemWidgetModel` at `0xc38a8c`.
  - `RDeviceGainLevelItemWidgetModel` at `0xc38b2c`.
  - `RDeviceHearingProtectionItemWidgetModel` at `0xc38b6c`.
  - `DeviceGamePanelItemWidgetModel` at `0xc38bb4`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0xc38bd4`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0xc38c74`.
  - `DeviceAudioPanelItemWidgetModel` at `0xc38d70`.
  - `RDeviceTouchSettingItemWidgetModel` at `0xc38da0`.
  - `RDeviceFindItemWidgetModel` at `0xc38db8`.
  - `RDeviceMoreItemWidgetModel` at `0xc38dd0`.
  - `RDeviceTapControlItemWidgetModel` at `0xc38e0c`.
  - `DeviceOtaPanelItemWidgetModel` at `0xc38e94`.

#### AURAMIC

- Source: `rs_common_v2/auramic_device_config.dart`;
  `AuramicDeviceConfig` extends `RsCommonV2DeviceConfig`.
- Display: `AURAMIC1 / AURAMIC1_<address suffix>`;
  protocol: `rs-common-v2 (inherited)`; form: `mic`.
- Config routine: `0x7b4580`.
- Sender allocations: inherited; none directly allocated in this file.
- Direct widget candidates:
  - `DeviceOptionPanelWidgetModel` at `0xc3115c`.
  - `DeviceMicSwitchPanelItemWidgetModel` at `0xc311dc`.
  - `DeviceOptionPanelItemByCommandWidgetModel` at `0xc31248`.
  - `DeviceOtaPanelItemWidgetModel` at `0xc38fe0`.

#### budsfeel_mk2

- Source: `rs_common_v2/budsfeel_mk2_device_config.dart`;
  `BudsfeelMk2DeviceConfig` extends `BaseDeviceConfig`.
- Display: `BudsFeel MK2`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b46cc`.
- Sender allocations: `RsCommonV2Sender` at `0x6d0880`.
- Direct widget candidates:
  - `DeviceDingLevelPanelItemWidgetModel` at `0x9877f0`.
  - `DeviceOptionPanelWidgetModel` at `0x98785c`.
  - `DeviceAncLinePanelItemWidgetModel` at `0x987874`.
  - `RDeviceComponentDisplayBarWidgetModel` at `0xc3a7a8`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0xc3a7d4`.
  - `RDeviceEqBassPlusItemWidgetModel` at `0xc3a81c`.
  - `DeviceGamePanelItemWidgetModel` at `0xc3a83c`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0xc3a85c`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0xc3a8d0`.
  - `DeviceAudioPanelItemWidgetModel` at `0xc3a93c`.
  - `RDeviceTouchSettingItemWidgetModel` at `0xc3a970`.
  - `RDeviceMoreItemWidgetModel` at `0xc3a988`.
  - `RDeviceFindItemWidgetModel` at `0xc3aa08`.
  - `DeviceOtaPanelItemWidgetModel` at `0xc3aa20`.

#### ceramics-mk2-5736

- Source: `rs_common_v2/ceramics_mk2_5736_device_config.dart`;
  `CeramicsMk25736DeviceConfig` extends `BaseDeviceConfig`.
- Display: `CERAMICS-MK2`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b476c`.
- Sender allocations: `RsCommonV2Sender` at `0x6dd1d0`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6dd2ac`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6dd338`.
  - `DeviceEqPanelItemWidgetModel` at `0x6dd380`.
  - `DeviceOptionPanelWidgetModel` at `0x6dd3e8`.
  - `DeviceGamePanelItemWidgetModel` at `0x6dd400`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6dd420`.
  - `RDeviceFindItemWidgetModel` at `0x6dd434`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6dd44c`.
  - `RDeviceMoreItemWidgetModel` at `0x6dd464`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0x6dd4f4`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6dd560`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6dd664`.
  - `DeviceAncLinePanelItemWidgetModel` at `0x6dd6e8`.

#### ceramics-mk2-8912f

- Source: `rs_common_v2/ceramics_mk2_8912f_device_config.dart`;
  `CeramicsMk28912fDeviceConfig` extends `BaseDeviceConfig`.
- Display: `CERAMICS-MK2`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b4800`.
- Sender allocations: `RsCommonV2Sender` at `0x6dd8e0`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6dd9c8`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6dda54`.
  - `DeviceEqPanelItemWidgetModel` at `0x6dda9c`.
  - `DeviceOptionPanelWidgetModel` at `0x6ddb04`.
  - `DeviceGamePanelItemWidgetModel` at `0x6ddb1c`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6ddb3c`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6ddb9c`.
  - `RDeviceFindItemWidgetModel` at `0x6ddbcc`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6ddbe4`.
  - `RDeviceLangItemWidgetModel` at `0x6ddbfc`.
  - `RDeviceMoreItemWidgetModel` at `0x6ddc1c`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0x6ddcbc`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6ddd28`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6dde1c`.
  - `DeviceAncLinePanelItemWidgetModel` at `0x6ddea0`.

#### ceramics-mk2

- Source: `rs_common_v2/ceramics_mk2_device_config.dart`;
  `CeramicsMk2DeviceConfig` extends `BaseDeviceConfig`.
- Display: `CERAMICS-MK2`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b4894`.
- Sender allocations: `RsCommonV2Sender` at `0x6de098`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6de180`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6de20c`.
  - `DeviceEqPanelItemWidgetModel` at `0x6de254`.
  - `DeviceOptionPanelWidgetModel` at `0x6de2c0`.
  - `DeviceGamePanelItemWidgetModel` at `0x6de2d8`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6de2f8`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6de358`.
  - `RDeviceFindItemWidgetModel` at `0x6de388`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6de3a0`.
  - `RDeviceLangItemWidgetModel` at `0x6de3b8`.
  - `RDeviceMoreItemWidgetModel` at `0x6de3d8`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0x6de478`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6de4e4`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6de5d8`.
  - `DeviceAncLinePanelItemWidgetModel` at `0x6de664`.

#### CERAMICS-U

- Source: `rs_common_v2/ceramics_u_device_config.dart`;
  `CeramicsUDeviceConfig` extends `BaseDeviceConfig`.
- Display: `CERAMICS-U`;
  protocol: `not directly declared`; form: `tws`.
- Config routine: `0x7b4928`.
- Sender allocations: `RsCommonV2Sender` at `0x6dc9f8`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6dca88`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6dcab4`.
  - `DeviceEqPanelItemWidgetModel` at `0x6dcb08`.
  - `DeviceOptionPanelWidgetModel` at `0x6dcb74`.
  - `RDeviceGainLevelItemWidgetModel` at `0x6dcb8c`.
  - `DeviceGamePanelItemWidgetModel` at `0x6dcbac`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6dcbcc`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6dcbe0`.
  - `RDeviceFindItemWidgetModel` at `0x6dcc14`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6dcc2c`.
  - `RDeviceLangItemWidgetModel` at `0x6dcc44`.
  - `RDeviceMoreItemWidgetModel` at `0x6dcc64`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0x6dcd04`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6dcd70`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6dce7c`.
  - `DeviceAutoNoiseAncPanelItemWidgetModel` at `0x6dcf08`.
  - `DeviceLimitNoiseAncPanelItemWidgetModel` at `0x6dcf68`.
  - `DeviceAncLinePanelItemWidgetModel` at `0x6dcfc8`.

#### CERAMICS-U-HSC-2710

- Source: `rs_common_v2/ceramics_u_hsc_2710_device_config.dart`;
  `CeramicsUHsc2710DeviceConfig` extends `BaseDeviceConfig`.
- Display: `CERAMICS-U`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b49c0`.
- Sender allocations: `RsCommonV2Sender` at `0x6dc2c0`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6dc350`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6dc37c`.
  - `DeviceEqPanelItemWidgetModel` at `0x6dc3d0`.
  - `DeviceOptionPanelWidgetModel` at `0x6dc438`.
  - `RDeviceGainLevelItemWidgetModel` at `0x6dc450`.
  - `DeviceGamePanelItemWidgetModel` at `0x6dc468`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6dc488`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6dc49c`.
  - `RDeviceFindItemWidgetModel` at `0x6dc4d0`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6dc4e8`.
  - `RDeviceMoreItemWidgetModel` at `0x6dc500`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0x6dc598`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6dc604`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6dc714`.
  - `DeviceLimitNoiseAncPanelItemWidgetModel` at `0x6dc798`.
  - `DeviceAncLinePanelItemWidgetModel` at `0x6dc7f8`.

#### coral

- Source: `rs_common_v2/coral_device_config.dart`;
  `CoralDeviceConfig` extends `BaseDeviceConfig`.
- Display: `Coral`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b4a50`.
- Sender allocations: `RsCommonV2Sender` at `0x6d0a30`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d0ac0`.
  - `RDeviceEqBassPlusItemWidgetModel` at `0x6d0aec`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d0b0c`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d0b2c`.
  - `DeviceOptionPanelWidgetModel` at `0x6d0b88`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0x6d0ba0`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6d0c0c`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d0c40`.
  - `RDeviceMoreItemWidgetModel` at `0x6d0c58`.
  - `RDeviceFindItemWidgetModel` at `0x6d0cd8`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6d0cf0`.
  - `RDeviceComponentOnlyNoiseBarWidgetModel` at `0x6d0d80`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d0dfc`.

#### earfeel-i7

- Source: `rs_common_v2/earfeel_i7_device_config.dart`;
  `EarfeelI7DeviceConfig` extends `BaseDeviceConfig`.
- Display: `EarFeel i7`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b4ae4`.
- Sender allocations: `RsCommonV2Sender` at `0x6cdec4`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6cdf54`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6cdfe8`.
  - `DeviceOptionPanelWidgetModel` at `0x6ce074`.
  - `DeviceEqPanelItemWidgetModel` at `0x6ce0d0`.
  - `RDeviceGainLevelItemWidgetModel` at `0x6ce17c`.
  - `RDeviceHearingProtectionItemWidgetModel` at `0x6ce1bc`.
  - `DeviceGamePanelItemWidgetModel` at `0x6ce204`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6ce224`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0x6ce2d0`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6ce36c`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6ce3a0`.
  - `RDeviceMoreItemWidgetModel` at `0x6ce3b8`.
  - `RDeviceFindItemWidgetModel` at `0x6ce464`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6ce47c`.
  - `DeviceDetectionSwitchPanelItemWidgetModel` at `0x6ce574`.
  - `DeviceAutoNoiseAncPanelItemWidgetModel` at `0x6ce5f4`.
  - `DeviceAutoNoiseAncDynamicPanelItemWidgetModel` at `0x6ce614`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6ce694`.
  - `DeviceAncLinePanelItemWidgetModel` at `0x6ce720`.

#### first-angel

- Source: `rs_common_v2/first_angel_device_config.dart`;
  `FirstAngelDeviceConfig` extends `BaseDeviceConfig`.
- Display: `FIRST ANGEL`;
  protocol: `rs-common-v2`; form: `head`.
- Config routine: `0x7b4b74`.
- Sender allocations: `RsCommonV2Sender` at `0x6ccb88`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6ccc58`.
  - `DeviceEqPanelItemWidgetModel` at `0x6ccc84`.
  - `DeviceOptionPanelWidgetModel` at `0x6ccd00`.
  - `DeviceGamePanelItemWidgetModel` at `0x6ccd18`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6ccd38`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6ccdac`.
  - `RDeviceFindItemWidgetModel` at `0x6ccdc4`.
  - `RDeviceMoreItemWidgetModel` at `0x6ccddc`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6cce5c`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6ccfbc`.

#### furina

- Source: `rs_common_v2/furina_device_config.dart`;
  `FurinaDeviceConfig` extends `BaseDeviceConfig`.
- Display: `FURINA`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b4c38`.
- Sender allocations: `RsCommonV2Sender` at `0x6d39a0`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d3a30`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6d3a5c`.
  - `DeviceEqPanelItemWidgetModel` at `0x6d3aa8`.
  - `DeviceOptionPanelWidgetModel` at `0x6d3b14`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d3b44`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d3b64`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6d3bec`.
  - `RDeviceFindItemWidgetModel` at `0x6d3c1c`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d3c34`.
  - `RDeviceLangItemWidgetModel` at `0x6d3c4c`.
  - `RDeviceMoreItemWidgetModel` at `0x6d3c6c`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6d3d24`.
  - `DeviceDetectionSwitchPanelItemWidgetModel` at `0x6d3e38`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d3ed0`.
  - `DeviceLimitNoiseAncPanelItemWidgetModel` at `0x6d3f6c`.
  - `DeviceAncLinePanelItemWidgetModel` at `0x6d3ff4`.
  - `RDevicePersonalizationItemWidgetModel` at `0x981548`.

#### hyacinth

- Source: `rs_common_v2/hyacinth_device_config.dart`;
  `HyacinthDeviceConfig` extends `BaseDeviceConfig`.
- Display: `Hyacinth`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b4ccc`.
- Sender allocations: `RsCommonV2Sender` at `0x6d2888`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d2918`.
  - `DeviceEqPanelItemWidgetModel` at `0x6d2944`.
  - `DeviceOptionPanelWidgetModel` at `0x6d29a0`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d29b8`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d29d8`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6d2a4c`.
  - `RDeviceFindItemWidgetModel` at `0x6d2a80`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d2a98`.
  - `RDeviceLangItemWidgetModel` at `0x6d2ab0`.
  - `RDeviceMoreItemWidgetModel` at `0x6d2ad0`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d2bd8`.

#### jasmine

- Source: `rs_common_v2/jasmine_device_config.dart`;
  `JasmineDeviceConfig` extends `BaseDeviceConfig`.
- Display: `Jasmine`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b4d34`.
- Sender allocations: `RsCommonV2Sender` at `0x6d2380`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d2410`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d243c`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d245c`.
  - `DeviceOptionPanelWidgetModel` at `0x6d24b8`.
  - `RDeviceFindItemWidgetModel` at `0x6d24d0`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d24e8`.
  - `RDeviceLangItemWidgetModel` at `0x6d2500`.
  - `RDeviceMoreItemWidgetModel` at `0x6d2520`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6d25a8`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d269c`.

#### ky01

- Source: `rs_common_v2/ky01_device_config.dart`;
  `Ky01DeviceConfig` extends `BaseDeviceConfig`.
- Display: `KY01`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b4d9c`.
- Sender allocations: `RsCommonV2Sender` at `0x6d1000`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d1090`.
  - `DeviceEqPanelItemWidgetModel` at `0x6d10bc`.
  - `DeviceOptionPanelWidgetModel` at `0x6d1118`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d1130`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d1150`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d11c4`.
  - `RDeviceLangItemWidgetModel` at `0x6d11dc`.
  - `RDeviceMoreItemWidgetModel` at `0x6d11fc`.
  - `RDeviceFindItemWidgetModel` at `0x6d127c`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6d1294`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d1388`.

#### luminous_agumon

- Source: `rs_common_v2/luminous_agumon_device_config.dart`;
  `LuminousAgumonDeviceConfig` extends `BaseDeviceConfig`.
- Display: `Agumon`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b4e30`.
- Sender allocations: `RsCommonV2Sender` at `0x6cf21c`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6cf2ac`.
  - `DeviceGamePanelItemWidgetModel` at `0x6cf2d8`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6cf2f8`.
  - `DeviceOptionPanelWidgetModel` at `0x6cf354`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6cf36c`.
  - `RDeviceFindItemWidgetModel` at `0x6cf384`.
  - `RDeviceMoreItemWidgetModel` at `0x6cf39c`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6cf48c`.

#### luminous_gabumon

- Source: `rs_common_v2/luminous_gabumon_device_config.dart`;
  `LuminousGabumonDeviceConfig` extends `BaseDeviceConfig`.
- Display: `Gabumon`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b4e98`.
- Sender allocations: `RsCommonV2Sender` at `0x6cf678`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6cf708`.
  - `DeviceGamePanelItemWidgetModel` at `0x6cf734`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6cf754`.
  - `DeviceOptionPanelWidgetModel` at `0x6cf7b0`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6cf7c8`.
  - `RDeviceFindItemWidgetModel` at `0x6cf7e0`.
  - `RDeviceMoreItemWidgetModel` at `0x6cf7f8`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6cf8e8`.

#### luminous_patamon

- Source: `rs_common_v2/luminous_patamon_device_config.dart`;
  `LuminousPatamonDeviceConfig` extends `BaseDeviceConfig`.
- Display: `Patamon`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b4f00`.
- Sender allocations: `RsCommonV2Sender` at `0x6cff30`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6cffc0`.
  - `DeviceGamePanelItemWidgetModel` at `0x6cffec`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d000c`.
  - `DeviceOptionPanelWidgetModel` at `0x6d0068`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d0080`.
  - `RDeviceFindItemWidgetModel` at `0x6d0098`.
  - `RDeviceMoreItemWidgetModel` at `0x6d00b0`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d01a0`.

#### luminous_tailmon

- Source: `rs_common_v2/luminous_tailmon_device_config.dart`;
  `LuminousTailmonDeviceConfig` extends `BaseDeviceConfig`.
- Display: `Tailmon`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b4f68`.
- Sender allocations: `RsCommonV2Sender` at `0x6cfad4`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6cfb64`.
  - `DeviceGamePanelItemWidgetModel` at `0x6cfb90`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6cfbb0`.
  - `DeviceOptionPanelWidgetModel` at `0x6cfc0c`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6cfc24`.
  - `RDeviceFindItemWidgetModel` at `0x6cfc3c`.
  - `RDeviceMoreItemWidgetModel` at `0x6cfc54`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6cfd44`.

#### magician

- Source: `rs_common_v2/magician_device_config.dart`;
  `MagicianDeviceConfig` extends `BaseDeviceConfig`.
- Display: `Magician`;
  protocol: `rs-common-v2`; form: `head`.
- Config routine: `0x7b4fd0`.
- Sender allocations: `RsCommonV2Sender` at `0x6d4214`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d42a4`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d42d0`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d42f0`.
  - `DeviceOptionPanelWidgetModel` at `0x6d434c`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6d4364`.
  - `RDeviceFindItemWidgetModel` at `0x6d4398`.
  - `RDeviceLangItemWidgetModel` at `0x6d43b0`.
  - `RDeviceMoreItemWidgetModel` at `0x6d43d0`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6d4458`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d4548`.

#### mirror

- Source: `rs_common_v2/mirror_device_config.dart`;
  `MirrorDeviceConfig` extends `BaseDeviceConfig`.
- Display: `Mirror`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b5064`.
- Sender allocations: `RsCommonV2Sender` at `0x6d1580`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d1610`.
  - `DeviceEqPanelItemWidgetModel` at `0x6d163c`.
  - `DeviceOptionPanelWidgetModel` at `0x6d1698`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d16b0`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d16d0`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d1744`.
  - `RDeviceMoreItemWidgetModel` at `0x6d175c`.
  - `RDeviceFindItemWidgetModel` at `0x6d17d4`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d18c4`.

#### moka

- Source: `rs_common_v2/moka_device_config.dart`;
  `MokaDeviceConfig` extends `BaseDeviceConfig`.
- Display: `MOKA`;
  protocol: `rs-common-v2`; form: `head`.
- Config routine: `0x7b50cc`.
- Sender allocations: `RsCommonV2Sender` at `0x6c9064`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6c9134`.
  - `DeviceEqPanelItemWidgetModel` at `0x6c9160`.
  - `DeviceOptionPanelWidgetModel` at `0x6c91c8`.
  - `DeviceGamePanelItemWidgetModel` at `0x6c91e0`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6c9200`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6c9274`.
  - `RDeviceMoreItemWidgetModel` at `0x6c92a8`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6c9320`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6c9430`.

#### opal

- Source: `rs_common_v2/opal_device_config.dart`;
  `OpalDeviceConfig` extends `BaseDeviceConfig`.
- Display: `Opal`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b5160`.
- Sender allocations: `RsCommonV2Sender` at `0x6d1ab0`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d1b40`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6d1b6c`.
  - `DeviceEqPanelItemWidgetModel` at `0x6d1bb4`.
  - `DeviceOptionPanelWidgetModel` at `0x6d1c10`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d1c28`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d1c48`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d1cbc`.
  - `RDeviceMoreItemWidgetModel` at `0x6d1cd4`.
  - `RDeviceFindItemWidgetModel` at `0x6d1d4c`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d1e44`.

#### openfeel-l

- Source: `rs_common_v2/openfeel_l_device_config.dart`;
  `OpenfeelLDeviceConfig` extends `BaseDeviceConfig`.
- Display: `OpenFeel L`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b51c8`.
- Sender allocations: `RsCommonV2Sender` at `0x6cd28c`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6cd31c`.
  - `DeviceEqPanelItemWidgetModel` at `0x6cd348`.
  - `DeviceOptionPanelWidgetModel` at `0x6cd3b0`.
  - `RDeviceGainLevelItemWidgetModel` at `0x6cd3c8`.
  - `RDeviceHearingProtectionItemWidgetModel` at `0x6cd3e0`.
  - `DeviceGamePanelItemWidgetModel` at `0x6cd3f8`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6cd418`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6cd48c`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6cd4c0`.
  - `RDeviceFindItemWidgetModel` at `0x6cd4d8`.
  - `RDeviceMoreItemWidgetModel` at `0x6cd4f0`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6cd578`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6cd698`.

#### openfeel_mk2

- Source: `rs_common_v2/openfeel_mk2_device_config.dart`;
  `OpenfeelMk2DeviceConfig` extends `BaseDeviceConfig`.
- Display: `OpenFeel MK2`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b525c`.
- Sender allocations: `RsCommonV2Sender` at `0x6d4bc4`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d4c54`.
  - `DeviceEqPanelItemWidgetModel` at `0x6d4c80`.
  - `DeviceOptionPanelWidgetModel` at `0x6d4cec`.
  - `RDeviceGainLevelItemWidgetModel` at `0x6d4d04`.
  - `RDeviceHearingProtectionItemWidgetModel` at `0x6d4d1c`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d4d34`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d4d54`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0x6d4dc8`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6d4e34`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d4e68`.
  - `RDeviceMoreItemWidgetModel` at `0x6d4e80`.
  - `RDeviceFindItemWidgetModel` at `0x6d4f00`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6d4f18`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d5024`.

#### RS-Common-v2

- Source: `rs_common_v2/rs_common_v2_device_config.dart`;
  `RsCommonV2DeviceConfig` extends `BaseDeviceConfig`.
- Display: `RS-Common-v2`;
  protocol: `rs-common-v2`; form: `not directly declared`.
- Config routine: `0x7b4688`.
- Sender allocations: `RsCommonV2Sender` at `0x6df764`.
- Direct widget candidates:
  - `DeviceDetectionSwitchPanelItemWidgetModel` at `0x987454`.
  - `DeviceOptionPanelWidgetModel` at `0x9874bc`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x9874d4`.
  - `DeviceAutoNoiseAncPanelItemWidgetModel` at `0x987558`.
  - `DeviceLimitNoiseAncPanelItemWidgetModel` at `0x9875b8`.
  - `DeviceSpatialSwitchPanelItemWidgetModel` at `0x987618`.
  - `DeviceEarAdaptivePanelItemWidgetModel` at `0x987678`.
  - `DeviceAncLinePanelItemWidgetModel` at `0x9876ec`.
  - `DeviceOtaPanelItemWidgetModel` at `0xc31850`.
  - `DeviceMicTxRecordPanelItemWidgetModel` at `0xc31a84`.
  - `DeviceMicSwitchPanelItemWidgetModel` at `0xc31aec`.
  - `_MicTxSettingsPanelWidgetModel` at `0xc31c10`.
  - `RDeviceComponentDisplayBarWidgetModel` at `0xc39138`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0xc391e4`.
  - `RDeviceSpatialAudioBarWidgetModel` at `0xc39254`.
  - `DeviceEqPanelItemWidgetModel` at `0xc3928c`.
  - `RDeviceEqBassPlusItemWidgetModel` at `0xc39338`.
  - `RDeviceGainLevelItemWidgetModel` at `0xc39380`.
  - `RDeviceHearingProtectionItemWidgetModel` at `0xc393b8`.
  - `DeviceGamePanelItemWidgetModel` at `0xc393f0`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0xc39410`.
  - `DeviceIndependentMicModePanelItemWidgetModel` at `0xc39440`.
  - `RDeviceRecordItemWidgetModel` at `0xc3950c`.
  - `RDeviceLocalRecordItemWidgetModel` at `0xc39530`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0xc39540`.
  - `DeviceAudioPanelItemWidgetModel` at `0xc3960c`.
  - `RDeviceTapControlItemWidgetModel` at `0xc396f8`.
  - `RDeviceHeadControlItemWidgetModel` at `0xc39770`.
  - `RDeviceTouchSettingItemWidgetModel` at `0xc397a4`.
  - `RDeviceLangItemWidgetModel` at `0xc39830`.
  - `RDeviceMoreItemWidgetModel` at `0xc398c4`.
  - `RDeviceFindItemWidgetModel` at `0xc3998c`.
  - `RDeviceEarTipFitTestItemWidgetModel` at `0xc399a4`.
  - `DeviceMicGainPanelItemWidgetModel` at `0xc39b9c`.
  - `MicRxStatusWidgetModel` at `0xc39bc4`.
  - `DeviceMicRxChannelPanelItemWidgetModel` at `0xc39bf0`.
  - `DeviceMicTimecodePanelItemWidgetModel` at `0xc39cc8`.
  - `MicTxStatusWidgetModel` at `0xc39d5c`.
  - `DeviceMicTfCardStoragePanelItemWidgetModel` at `0xc39d70`.
  - `DeviceMicRemotePanelItemWidgetModel` at `0xc39d84`.
  - `DeviceMicTxNoisePanelItemWidgetModel` at `0xc39ddc`.
  - `DeviceMicTxTonePanelItemWidgetModel` at `0xc39df0`.

#### speakfeel2

- Source: `rs_common_v2/speakfeel2_device_config.dart`;
  `Speakfeel2DeviceConfig` extends `BaseDeviceConfig`.
- Display: `SPEAKFEEL`;
  protocol: `rs-common-v2`; form: `head`.
- Config routine: `0x7b52f0`.
- Sender allocations: `RsCommonV2Sender` at `0x6cd89c`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6cd92c`.
  - `RDeviceEqBassPlusItemWidgetModel` at `0x6cd958`.
  - `RDeviceGainLevelItemWidgetModel` at `0x6cd978`.
  - `RDeviceHearingProtectionItemWidgetModel` at `0x6cd990`.
  - `DeviceGamePanelItemWidgetModel` at `0x6cd9a8`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6cd9c8`.
  - `DeviceIndependentMicModePanelItemWidgetModel` at `0x6cd9f8`.
  - `DeviceOptionPanelWidgetModel` at `0x6cda34`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6cda4c`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6cda80`.
  - `RDeviceFindItemWidgetModel` at `0x6cda98`.
  - `RDeviceMoreItemWidgetModel` at `0x6cdab0`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6cdb38`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6cdca8`.

#### speakfree

- Source: `rs_common_v2/speakfree_device_config.dart`;
  `SpeakfreeDeviceConfig` extends `BaseDeviceConfig`.
- Display: `SpeakFeel`;
  protocol: `rs-common-v2`; form: `head`.
- Config routine: `0x7b53b4`.
- Sender allocations: `RsCommonV2Sender` at `0x6d46a0`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d4730`.
  - `DeviceEqPanelItemWidgetModel` at `0x6d475c`.
  - `DeviceOptionPanelWidgetModel` at `0x6d47b8`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d47d0`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d47f0`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6d4870`.
  - `RDeviceFindItemWidgetModel` at `0x6d48a4`.
  - `RDeviceLangItemWidgetModel` at `0x6d48bc`.
  - `RDeviceMoreItemWidgetModel` at `0x6d48dc`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6d4964`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d4a60`.

#### sportfeel-8912f

- Source: `rs_common_v2/sportfeel_8912f_device_config.dart`;
  `Sportfeel8912fDeviceConfig` extends `BaseDeviceConfig`.
- Display: `SportFeel`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b5420`.
- Sender allocations: `RsCommonV2Sender` at `0x6d5394`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d547c`.
  - `DeviceEqPanelItemWidgetModel` at `0x6d54a4`.
  - `DeviceOptionPanelWidgetModel` at `0x6d5500`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d5518`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d5538`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6d5598`.
  - `RDeviceFindItemWidgetModel` at `0x6d55c8`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d55e0`.
  - `RDeviceMoreItemWidgetModel` at `0x6d55f8`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6d5690`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d5774`.

#### swift2

- Source: `rs_common_v2/swift2_device_config.dart`;
  `Swift2DeviceConfig` extends `BaseDeviceConfig`.
- Display: `SWIFT 2`;
  protocol: `rs-common-v2`; form: `head`.
- Config routine: `0x7b54b4`.
- Sender allocations: `RsCommonV2Sender` at `0x6d0398`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d0428`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d0454`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d0474`.
  - `DeviceOptionPanelWidgetModel` at `0x6d04d0`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d04e8`.
  - `RDeviceMoreItemWidgetModel` at `0x6d0500`.
  - `RDeviceFindItemWidgetModel` at `0x6d0578`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6d0590`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d0688`.

#### zircon

- Source: `rs_common_v2/zircon_device_config.dart`;
  `ZirconDeviceConfig` extends `BaseDeviceConfig`.
- Display: `Zircon`;
  protocol: `rs-common-v2`; form: `tws`.
- Config routine: `0x7b5550`.
- Sender allocations: `RsCommonV2Sender` at `0x6d2dd0`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6d2e60`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6d2e8c`.
  - `DeviceEqPanelItemWidgetModel` at `0x6d2ed8`.
  - `DeviceOptionPanelWidgetModel` at `0x6d2f34`.
  - `RDeviceRecordItemWidgetModel` at `0x6d2f4c`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0x6d2fd0`.
  - `DeviceGamePanelItemWidgetModel` at `0x6d3098`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6d30b8`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6d30cc`.
  - `RDeviceFindItemWidgetModel` at `0x6d3100`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6d3118`.
  - `RDeviceLangItemWidgetModel` at `0x6d3130`.
  - `RDeviceMoreItemWidgetModel` at `0x6d3150`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6d31f0`.
  - `DeviceDetectionSwitchPanelItemWidgetModel` at `0x6d32f4`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6d3374`.
  - `DeviceLimitNoiseAncPanelItemWidgetModel` at `0x6d33f8`.
  - `DeviceAncLinePanelItemWidgetModel` at `0x6d3458`.

### zt

#### earfree-i3

- Source: `zt/earfree_i3_device_config.dart`;
  `EarfreeI3DeviceConfig` extends `BaseDeviceConfig`.
- Display: `EarFree-i3`;
  protocol: `zt`; form: `tws`.
- Config routine: `0x7b55e4`.
- Sender allocations: `Zt219Sender` at `0x6d9fdc`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6da09c`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6da0c8`.
  - `DeviceEqPanelItemWidgetModel` at `0x6da110`.
  - `DeviceOptionPanelWidgetModel` at `0x6da16c`.
  - `DeviceGamePanelItemWidgetModel` at `0x6da184`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6da1a4`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6da1dc`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6da20c`.
  - `RDeviceMoreItemWidgetModel` at `0x6da224`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6da2b4`.
  - `RDeviceHideSearchItemWidgetModel` at `0x6da3b4`.

#### EARFREE-i5

- Source: `zt/earfree_i5_device_config.dart`;
  `EarfreeI5DeviceConfig` extends `BaseDeviceConfig`.
- Display: `EarFeel-i5`;
  protocol: `zt`; form: `tws`.
- Config routine: `0x7b5668`.
- Sender allocations: `Zt229Sender` at `0x6dac14`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6dacd4`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6dad00`.
  - `DeviceEqPanelItemWidgetModel` at `0x6dad4c`.
  - `DeviceOptionPanelWidgetModel` at `0x6dada8`.
  - `DeviceGamePanelItemWidgetModel` at `0x6dadc0`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6dade0`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6dae18`.
  - `RDeviceFindItemWidgetModel` at `0x6dae48`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6dae60`.
  - `RDeviceMoreItemWidgetModel` at `0x6dae78`.
  - `RDeviceStarburstBusinessEntryItemWidgetModel` at `0x6daf10`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6daf7c`.
  - `DeviceDetectionSwitchPanelItemWidgetModel` at `0x6db090`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6db110`.
  - `DeviceAncLinePanelItemWidgetModel` at `0x6db194`.

#### headfree

- Source: `zt/headfree_device_config.dart`;
  `HeadfreeDeviceConfig` extends `BaseDeviceConfig`.
- Display: `HeadFeel`;
  protocol: `zt`; form: `head`.
- Config routine: `0x7b56dc`.
- Sender allocations: `Zt231Sender` at `0x6da610`.
- Direct widget candidates:
  - `RDeviceComponentDisplayBarWidgetModel` at `0x6da6d4`.
  - `RDeviceComponentNoiseBarWidgetModel` at `0x6da700`.
  - `DeviceEqPanelItemWidgetModel` at `0x6da74c`.
  - `DeviceOptionPanelWidgetModel` at `0x6da7b8`.
  - `RDeviceGainLevelItemWidgetModel` at `0x6da7d0`.
  - `DeviceGamePanelItemWidgetModel` at `0x6da7f0`.
  - `DeviceMulDevicePanelItemWidgetModel` at `0x6da810`.
  - `DeviceAudioPanelItemWidgetModel` at `0x6da830`.
  - `RDeviceFindItemWidgetModel` at `0x6da860`.
  - `RDeviceTouchSettingItemWidgetModel` at `0x6da878`.
  - `RDeviceMoreItemWidgetModel` at `0x6da890`.
  - `DeviceOtaPanelItemWidgetModel` at `0x6da928`.
  - `DeviceDingLevelPanelItemWidgetModel` at `0x6daa2c`.
  - `DeviceAncLinePanelItemWidgetModel` at `0x6daab0`.
