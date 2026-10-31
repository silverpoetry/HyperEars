# RayNeo X3 Pro BLE integration

## Identity and capability gates

Exact normalized X3 Pro names select a candidate only. `general_settings` must report `deviceType=RayNeoX3Pro` and `productModel=ARGF20` before device controls are admitted. Initial battery is Android's aggregate value; a valid private battery report promotes the private source. Existing system-supported devices stay on their official path.

## Transport

Service `0000b81d-0000-1000-8000-00805f9b34fb`; write `ea8b70d5-2bd3-49ab-9c31-9c38b2c3c4f9`; notify `7db3e235-3608-41f3-a03c-955fcbd2ea4b`. Request MTU 256 and reject negotiated MTU below 256. Frames are 253 bytes: marker 1 begins, 2 continues, 0 completes; zero padding is removed only after assembly. The underlying payload is JSON. Raw payload logs are redacted because Wi-Fi connection messages contain credentials.

## Controls

General status updates use a fresh full snapshot to preserve unrelated fields. Brightness is percent with an automatic-brightness flag. Audio streams 3 and 1 carry percent; the tested firmware quantizes both to 15 system steps. Voice modes are 0 standard, 1 whisper, 2 enhanced. Sleep settings allow -1,15,30,45,60 seconds. Wi-Fi scan is bounded and stopped after results or its deadline; credentials never appear in UI summaries or logs.

Capture and recording remain on the glasses. Screenshot preparation uses `ble.response.mix_shot` type 0, followed by type 10 after four seconds. Cancellation/error/reset suppresses the deferred capture. Recording uses `ble.response.mix_record` type 0 start / 1 stop. Only real result messages report completion.

`ble.response.app_market` uses nested JSON payloads. `installed_apps` returns package names only, `installed_versions` adds versions, `launch_status` returns package and status. No app-name request exists in the examined firmware handler; bundled names were read once from Android PackageManager. Unknown packages remain visible as package names. Launch is allowed only for a package in the current session's reported set. Four requested shortcuts and an arbitrary-app picker share this same path.

## Native UI and coexistence

Settings consumes authenticated state and dispatches typed controls. It reuses the HyperOS headset plugin, with a single battery badge, voice segments, automatic/manual brightness, launch shortcuts and transient result text. Private writes are disabled during vendor handoff; the native page remains visible. The companion is opened only after release acknowledgement. Runtime operation does not require glasses ADB, USB or Wi-Fi; ADB was used only for independent testing and collecting app-name labels.

## Evidence

The user verified the native page, rename, app names and layout. Live testing verified the four shortcuts and SmartTube via glasses foreground checks; brightness and automatic mode; both volume streams; voice mode round trips; capture with a new media record; recording start/stop with no remaining media-projection session. Those tests cover the pre-split local version. This independently trimmed branch still needs device revalidation. Wi-Fi connection and sleep changes lack full end-to-end device acceptance.

The protocol was reconstructed from the installed official companion and glasses launcher. No proprietary application code, APK, credentials or raw capture is included. The packaged product artwork is a generated derivative of a user-supplied product reference; the reference itself is not distributed.
