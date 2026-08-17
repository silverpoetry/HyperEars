# HyperEars

![HyperEars cover](docs/assets/coolapk-title.png)

[简体中文](README.md) · [Installation](docs/installation.md) · [Compatibility](docs/compatibility.md) · [Controller scopes](docs/control-apps.md) · [Troubleshooting](docs/troubleshooting.md)

[![CI](https://github.com/silverpoetry/HyperEars/actions/workflows/ci.yml/badge.svg)](https://github.com/silverpoetry/HyperEars/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/silverpoetry/HyperEars?display_name=tag)](https://github.com/silverpoetry/HyperEars/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](LICENSE)

HyperEars integrates selected third-party Bluetooth headsets with Xiaomi HyperOS and the MiLink
device center. It complements Android's existing audio stack with device identity, battery,
noise-control state and handoff metadata for supported vivo/iQOO, OPPO Enco, Technics, Bose, Edifier,
StarRing, ROSESELSA, NiceHCK, MOONDROP, Honor, QCY and Sony devices.

> [!WARNING]
> HyperEars requires root, LSPosed and private HyperOS APIs. Be prepared to recover your system
> before installing it. ROM updates may temporarily break compatibility. This project is not
> affiliated with Xiaomi, vivo, iQOO, OPPO, Panasonic, Technics, Bose, Edifier, ROSESELSA, NiceHCK, MOONDROP, Honor, QCY, Sony or any
> other device vendor.

## Scope

### System integration

- Publishes eligible third-party headsets to the MiLink device center for device handoff and system volume.
- Provides the same handoff and volume path to standard A2DP/HFP headsets, with Android aggregate-battery fallback.

### Device capabilities

- Publishes battery according to the headset form and confirmed protocol: left/right and case, headphone aggregate, or Android system battery.
- Publishes noise cancellation, off, transparency and model-specific modes only after private-protocol confirmation; unconfirmed private controls remain unavailable.
- Devices with confirmed noise control can select the existing Mode metric on the HyperEars home-page session card and switch modes from its drop-down list.
- The MiLink card's “More settings” action can open the real Android Bluetooth-device details page, the declared vendor controller, or HyperEars. An unavailable controller falls back to system settings.

### Sessions and ownership

- Maintains recognition, connection channel, protocol and MiLink-publication state independently for each connected headset; the app displays these per-device sessions.
- When runtime yielding is enabled and the declared vendor controller is hooked by LSPosed, HyperEars yields private-protocol ownership while that app is running. MiLink handoff, system volume and Android audio routing remain available.
- HyperEars integration can be paused without disabling Android Bluetooth or audio routing; reconnect the headset after resuming to create a new module session.

### Settings and diagnostics

- Provides a drop-down for choosing what the MiLink card's “More settings” action opens, plus settings for runtime yielding, automatic update checks and pausing the integration.
- Provides a Debug > Adapters page that groups every model, family fallback and standard fallback Adapter by brand, with a group switch that disables or restores all Adapters in that brand.
- Provides a Debug page for detailed logging and diagnostic export.
- Provides root-only shortcuts for restarting MiLink, restarting Bluetooth and stopping supported vendor controllers.
- No module diagnostics are produced while detailed logging is disabled. When enabled, injected-process logs are written through the LSPosed daemon; settings changes and shortcut results are kept in the app's bounded local log and merged during export.

## Runtime boundary

HyperEars does not replace Android's A2DP/HFP audio path, proxy audio streams or continuously scan for
Bluetooth devices. Private GATT, RFCOMM or BR/EDR L2CAP channels are created only for adapters that
need vendor telemetry and remain bound to the corresponding device session.

## Requirements

- Xiaomi HyperOS on Android 15 or newer;
- LSPosed API 101 or newer;
- required LSPosed scopes are `com.android.bluetooth` and `com.milink.service`;
- to enable runtime yielding, also select the matching installed package from the
  [controller-app catalog](docs/control-apps.md); opening an installed controller from “More
  settings” does not by itself require that optional scope;
- a headset already paired through Android Bluetooth settings.

## Compatibility overview

| Adapter scope | Evidence level | Battery capability | Noise control |
|---|---|---|---|
| vivo / iQOO TWS | hardware-verified, public implementation, family extrapolation | private components | noise cancellation, off, transparency |
| OPPO Enco | reference protocol | private components | noise cancellation, off, transparency |
| StarRing | Ultra hardware-verified; others standard fallback | Ultra private components; others Android aggregate | Ultra: noise cancellation, off, transparency, wind-noise reduction |
| Bose | one hardware-verified model; public implementation, reference protocol and family extrapolation for others | private aggregate or components | explicit subset selected by BMAP product and control dialect |
| Edifier | W860NB PRO and Huazai Evo Pro hardware-verified; others family extrapolation | headphone aggregate, TWS left/right, or aggregate | noise cancellation, off, transparency and wind-noise reduction |
| ROSESELSA / ROSE | Furina Endless Solo of Solitude and ROSE Ceramics Ultra hardware-verified; two public implementations; product-line extrapolation; others standard fallback | private components after protocol confirmation; Android aggregate on fallback | noise cancellation, off, transparency and wind-noise reduction after protocol confirmation |
| NiceHCK / YuanDao | OriG in public implementation; others standard fallback | private components after protocol confirmation; Android aggregate on fallback | OriG in: noise cancellation, off, transparency and wind-noise reduction after protocol confirmation |
| MOONDROP | Robin public protocol; others standard fallback | Robin left/right battery after protocol confirmation; Android aggregate on fallback | Robin: noise cancellation, off and transparency after protocol confirmation |
| Honor | X5s Pro hardware-verified; others standard fallback | X5s Pro private components after protocol confirmation; Android aggregate on fallback | X5s Pro: noise cancellation, off and transparency after protocol confirmation |
| QCY | Crossky C50S public protocol; same-protocol family probe; others standard fallback | private components after protocol confirmation; Android aggregate on fallback | noise cancellation, off and transparency after protocol confirmation |
| Technics EAH-AZ TWS | AZ80 hardware-verified; public implementation for other models, with the reference implementation contributor-verified on AZ60, AZ80, AZ100 and others | private components after protocol confirmation | noise cancellation, off and transparency after a valid mode report; all three writes and device readback verified on AZ80 |
| Sony | public implementation, family extrapolation and standard fallback | private aggregate, private components or Android aggregate by form factor | explicit model-specific modes listed in the detailed matrix |
| other standard A2DP/HFP headsets | standard fallback | Android aggregate | none |

Every row includes device handoff and system volume. Public implementations, reference protocols
and family extrapolations are not hardware verification. A family name selects only a candidate
protocol; adapters that require confirmation also validate a service, on-wire identity or
accepted state frame. Bose devices are refined by
their on-wire BMAP product ID. Unknown BMAP devices retain battery telemetry and use GET-only
AudioModes, ANR and CNC discovery; no write is exposed before a valid status response.

Sony private adapters require a valid RFCOMM v1/v2 initialization response. Exact model adapters
select battery topology and the ambient-control dialect; unknown product-line models use
conservative family fallbacks. The exhaustive model list, transports, evidence and known limits are
maintained in the [compatibility matrix](docs/compatibility.md).

## Install

1. Download the APK and matching `.sha256` file from
   [Releases](https://github.com/silverpoetry/HyperEars/releases).
2. Verify the SHA-256 digest.
3. Install the APK, enable HyperEars in LSPosed and select at least `com.android.bluetooth` and
   `com.milink.service`. If runtime yielding is needed, also select the matching installed package
   from the [controller-app catalog](docs/control-apps.md); do not select Settings, System UI or
   every application.
4. Reboot the device, pair/connect the headset and inspect the HyperEars dashboard.

Early development builds used a different certificate. Android cannot update such a build in
place; disable it in LSPosed, uninstall it, install the public release and enable it again. The
complete upgrade and removal procedure is documented in [installation.md](docs/installation.md).

The public signing-certificate fingerprint and verification procedure are documented in
[release-signing.md](docs/release-signing.md).

## Repository layout

- `protocol`: stateless wire codecs and incremental decoders.
- `integration`: stateful adapters, capabilities and transferable per-device `ProtocolSession`s.
- `system-module`: production LSPosed module, Bluetooth lifecycle, MiLink bridge and dashboard.
- `protocol-test`: developer protocol laboratory; not shipped as a production artifact.

Controls use typed requests throughout: cards submit standard or model-specific requests, adapters
validate confirmed capabilities, the framework automatically transports versioned requests between
MiLink and Bluetooth, and `ProtocolSession` turns only accepted requests into vendor bytes. New
models do not write IPC or JSON by hand.

Battery, noise mode, and future model-specific state use one typed feature snapshot. Handshake,
capability evidence, and connection lifecycle remain separate concerns; MiLink's native battery
and ANC callbacks are only projections at the bridge boundary and do not constrain new state types.

Architecture, process boundaries, state revisioning and extension rules are described in
[system-module-architecture.md](docs/system-module-architecture.md).

When runtime yielding is enabled and a declared controller app is actually hooked, HyperEars
yields private-protocol ownership while that app is running. It closes only its own
GATT/RFCOMM/L2CAP channel and keeps MiLink handoff, system volume and standard Bluetooth
integration. Presence is determined solely by a process Binder token and Binder death, never by
whether the vendor app opened a Bluetooth connection. Private control is restored after all
hooked processes of that app have exited. The authoritative app names, package names,
Adapter-declared priority and scope behavior are maintained in the
[controller-app catalog](docs/control-apps.md).

## Privacy and security

The production app uses Internet access only to query the public latest GitHub Release. Automatic
checks are enabled by default, run only while HyperEars is opened, and are limited to once per day;
they can be disabled in Settings. No Bluetooth identity, settings, logs or protocol data is sent,
and injected processes never perform network requests. The app includes no analytics, advertising
or remote crash reporting. Bluetooth addresses remain local and are masked in normal module logs.
The protocol laboratory intentionally displays raw frames and device addresses, so redact them
before sharing diagnostics. See [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md).

## Build

JDK 17 and Android SDK 36 are required:

```powershell
.\gradlew.bat --no-daemon clean testDebugUnitTest `
  :protocol-test:assembleDebug `
  :system-module:lintRelease `
  :system-module:assembleRelease
```

Without the four `HYPEREARS_KEY*`/`HYPEREARS_KEYSTORE*` environment variables, the Release APK is
left unsigned. Tagged GitHub builds retrieve the durable release key from repository Secrets,
verify the resulting APK and publish a matching SHA-256 file.

CI also validates Markdown structure, local links, controller-catalog consistency, unit tests,
Android Lint and the Release build.

## Documentation

- [Installation, upgrade and removal](docs/installation.md)
- [Device compatibility and evidence levels](docs/compatibility.md)
- [Vendor controller apps and LSPosed scopes](docs/control-apps.md)
- [Troubleshooting and log collection](docs/troubleshooting.md)
- [System-module architecture](docs/system-module-architecture.md)
- [QCY standard GATT protocol](docs/qcy-standard-gatt-protocol.md)
- [Technics EAH-AZ / Airoha RACE protocol](docs/technics-race-protocol.md)
- [Release signing and artifact verification](docs/release-signing.md)

## Contributing and licensing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a model or protocol change. Never publish
a complete personal Bluetooth MAC, account data, credentials or proprietary vendor assets.

HyperEars is licensed under [GNU GPL-3.0-only](LICENSE). Protocol research references and
attributions are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Product and trademark
names are used only to describe compatibility and remain the property of their respective owners.
