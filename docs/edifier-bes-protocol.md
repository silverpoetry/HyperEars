# Edifier (BES/恒玄) Bluetooth Protocol Analysis

> Edifier W860NB PRO was verified on real hardware through Edifier Connect v8.4.39 traffic.
> Edifier 花再 Evo Pro was verified from community protocol-test captures supplied in
> [HyperEars PR #17](https://github.com/silverpoetry/HyperEars/pull/17), 2026-08-02.
> The current implementation independently models the captured interoperability facts.
>
> **Evidence levels:**
>
> - **W860NB PRO** — Full real-device verification (ANC, battery, capabilities, SPP framing)
> - **花再 Evo Pro** — Real-device verification (BES framing, `ancIndex=0x1B`, left/right battery
>   via `0xF2`, and `1/2/3→ANC`, `4→WIND`, `5→TRANSPARENCY`, `6→OFF`)
> - **Other Edifier models (W820NB, W830NB, STAX, etc.)** — Based on BES/Edifier family protocol
>   speculation, **not yet verified on real hardware**. The same SPP UUID, channel 1, XOR 0xA5
>   encryption, and D0/CC/D8 commands are shared across the family, but individual firmware versions
>   may differ in channel, encryption, command set, and response structure. Contributions and Issue
>   reports for specific models are welcome.

## SPP Connection

- **Service UUID**: `EDF00000-EDFE-DFED-FEDF-EDFEDFEDFEDF`
- **Fallback**: RFCOMM channel 1 via `BluetoothDevice.createRfcommSocket(1)`
- **Connection order**: First try `createRfcommSocketToServiceRecord(uuid)`, on failure retry with channel 1

## Frame Format (BLE v2, confirmed live)

**App → Device (Send):**

```text
[0xAA][APP_CODE][CMD_INDEX][LEN_H][LEN_L][PAYLOAD...][CRC]
```

Example: `AA EC D8 00 00 6E` (device function query)

**Device → App (Response):**

```text
[0xBB][APP_CODE][CMD_INDEX][LEN_H][LEN_L][PAYLOAD...][CRC]
```

Older firmware may use `0xCC`. Example: `BB EC D0 00 01 99 11` (battery response)

| Field | Size | Description |
|-------|------|-------------|
| Header | 1 byte | Send=`0xAA`(170), Receive=`0xBB`(187), Old=`0xCC`(204) |
| APP_CODE | 1 byte | `0xEC` (236) for app commands |
| CMD_INDEX | 1 byte | Command identifier (see below) |
| LENGTH | 2 bytes | Big-endian, payload length (not including header/CRC) |
| PAYLOAD | N bytes | **Both send and receive payloads are XOR-encrypted with `0xA5`** |
| CRC | 1 byte | Sum of all preceding bytes & 0xFF (computed over encrypted payload) |

### Payload Encryption (XOR 0xA5, confirmed)

Both directions carry XOR-`0xA5`-encrypted payloads. Key source:
`ECCommand.EncryptionCode.Encryption10.value` = `Opcodes.IF_ACMPEQ` = 165 = `0xA5`.

- Battery response: `0x99 ^ 0xA5 = 0x3C = 60%`
- ANC response: `B5 A0 ^ A5 A5 = 10 05` → ancIndex=16, ancValue=5
- ANC set (send): plaintext `10 04` is transmitted as `B5 A1`

### CRC Verified

- `AA EC D8 00 00` → 170+236+216 = 622 = 0x26E → `&0xFF` = `0x6E` ✓
- `AA EC D0 00 00` → 170+236+208 = 614 = 0x266 → `&0xFF` = `0x66` ✓
- `BB EC D0 00 01 99` → 187+236+208+0+1+153 = 785 = 0x311 → `&0xFF` = `0x11` ✓ (CRC over encrypted payload)

## ANC Control (confirmed live)

### Set ANC (cmd 0xC1 / 193)

```text
[ancIndex][ancValue]
```

- **ancIndex** = `0x10` (16) — ANC16 slot on W860NB PRO
- **ancValue** mapping (verified by live capture):

| ancValue | Mode (中文) | Mode (EN) | NoiseMode |
|----------|------------|-----------|-----------|
| 1 | 深度降噪 | Deep NC | ANC |
| 2 | 舒适降噪 | Comfort NC | ANC |
| 3 | 防风噪 | Wind noise | WIND |
| 4 | 环境声 | Ambient | TRANSPARENCY |
| 5 | 降噪关 | NC Off | OFF |

Live example: `AA EC C1 00 02 B5 A6 B4` = set wind noise mode
(plaintext `10 03` → encrypted `B5 A6`; CRC: AA+EC+C1+00+02+B5+A6 = 0x2B4 → &0xFF = 0xB4 ✓)

### Query ANC (cmd 0xCC / 204)

```text
[empty payload]
```

Response payload is 2-3 bytes, XOR-encrypted. After decrypt:

- byte[0] = ancIndex (`0x10`)
- byte[1] = ancValue (1-5, see mapping above)

Verified: `BB EC CC 00 02 B5 A0 CA` → `B5 A0` → decrypt `10 05` = NC off.

### Query Battery (cmd 0xD0 / 208)

```text
[empty payload]
```

Response example: `BB EC D0 00 01 99 11` — single-byte XOR-encrypted payload.
`0x99 ^ 0xA5 = 0x3C = 60%`. Percent may include charging flag in high bit (not yet confirmed).

## 花再 Evo Pro dialect (confirmed capture)

Evo Pro retains the same RFCOMM framing, checksum and XOR key, but it does not use W860NB PRO's
ANC slot or battery query.

| Property | Evo Pro value |
|---|---|
| ANC slot | `0x1B` |
| Battery source | `0xF2` device-state response |
| Battery topology exposed by HyperEars | Independent left/right values; case unknown |
| Noise values | `1/2/3=ANC`, `4=WIND`, `5=TRANSPARENCY`, `6=OFF` |

Captured ANC state:

```text
BB EC CC 00 02 BE A3 D6
                 └─ BE A3 XOR A5 A5 = 1B 06 → slot 0x1B, off
```

Captured device state:

```text
BB EC F2 00 06 A6 C1 C7 A5 A6 B4 CC
                 └─ payload XOR A5 = 03 64 62 00 03 11
                                      └─ └─ left 100%, right 98%
```

The leading `03` is frame metadata, not a 3% battery value. This interpretation is corroborated by
the same real-device session reporting full left/right batteries. HyperEars exposes bytes 1 and 2
as left and right levels. The remaining `0xF2` fields stay unclaimed until charging and case
semantics are independently captured; the case therefore remains unavailable. A wind-noise write
uses value `4`, for example:

```text
AA EC C1 00 02 BE A1 B8
                 └─ plaintext 1B 04
```

## W820NB 双金标版 V1 dialect (confirmed live)

W820NB 双金标版 verified through a live Edifier Connect v8.4.48 RFCOMM session (2026-08-24). It
does **not** use the BES v2 framing above; it speaks the V1 (bleVersion=1) family framing:

```text
Send:    [0xAA][LEN][CMD_INDEX][PAYLOAD...][CRC_H][CRC_L]
Receive: [0xBB][LEN][CMD_INDEX][PAYLOAD...][CRC_H][CRC_L]   (older firmware replies 0xCC)
```

- **LEN** = payload.size + 1 (the command byte is counted). No XOR encryption.
- **CRC16** = `8217 + Σ(all preceding bytes)`, big-endian 2 bytes. Verified:
  `AA 01 D0 21 94` → 8217+AA+01+D0 = 0x2194 ✓; `BB 02 D0 35 21 DB` → 8217+BB+02+D0+35 = 0x21DB ✓.
- Payloads are plaintext; response `payload[0]` carries the value directly.

| Command | Index (hex) | Example / notes |
|---------|-------------|-----------------|
| battery_query | 0xD0 | `AA 01 D0 21 94` → `BB 02 D0 35 21 DB`, payload `0x35` = 53% |
| anc_query | 0xCC | `AA 01 CC 21 90` → `BB 03 CC 02 06 21 AB`, payload `[mode][level]` |
| anc_set | 0xC1 | `AA 02 C1 02 21 88` (mode only) or `AA 03 C1 03 06 21 90` (mode + level) |
| device_function_query | 0xD8 | `AA 01 D8 21 9C` → 21-byte capabilities payload |
| version_query | 0xC6 | `AA 01 C6 21 8A` → `BB 04 C6 03 00 04 21 A5` (version 3.0.4) |
| name_query | 0xC9 | `AA 01 C9 21 8D` → UTF-8 name payload |
| a2dp_state_query | 0xC3 | device pushes `BB 02 C3 03 21 9C` (payload 0x03) |
| phone_audio_codec_query | 0x68 | device pushes `BB 02 68 04 21 42` (payload 0x04) |
| game_state_query | 0x08 | `AA 01 08 20 CC` → `BB 02 08 00 20 DE`, payload `0`=off / `1`=on |
| game_state_set | 0x09 | `AA 02 09 01 20 CF` (on) / `AA 02 09 00 20 CE` (off) → echo `BB 02 09 01 20 E0` |

Verified ANC dialect (mode values reported by `0xCC` and accepted by `0xC1`):

| mode | 中文 | HyperEars projection |
|------|------|----------------------|
| 1 | 标准 | OFF |
| 2 | 降噪 | ANC |
| 3 | 通透（第二字节为通透度档位） | TRANSPARENCY |

The device has no separate "关闭" mode; 标准 (mode 1) is the passive state and projects to OFF.
ANC writes execute immediately and the device echoes `[mode][level]`.

## Other Key Commands (confirmed live)

| Command | Index (hex) | Direction | Notes |
|---------|-------------|-----------|-------|
| battery_query | 0xD0 | query | battery |
| anc_query | 0xCC | query | noise state |
| anc_set | 0xC1 | set | `[ancIndex][ancValue]` |
| device_state_query | 0xF2 | query | TWS state |
| version_query | 0xC6 | query | version |
| name_query | 0xC9 | query | device name |
| device_function_query | 0xD8 | query | capabilities bitmap |
| game_state_query | 0x08 | query | game mode |

## Device Name

- Bluetooth name confirmed: `EDIFIER W860NB Pro`
- Evo Pro capture name: `漫步者・花再 Evo Pro`

## Notes for HyperEars Integration

1. W860NB PRO is a **headphone** (formFactor = HEADPHONES)
2. SPP UUID `EDF00000-EDFE-DFED-FEDF-EDFEDFEDFEDF` is primary, fallback RFCOMM channel 1
3. Send header is `0xAA`, receive header is `0xBB` (older `0xCC`)
4. CRC = sum of all preceding bytes & 0xFF — confirmed
5. W860NB PRO uses slot `0x10` with values 1=depth, 2=comfort, 3=wind, 4=ambient, 5=off
6. Evo Pro uses slot `0x1B` with values 1/2/3=ANC, 4=wind, 5=ambient, 6=off
7. **Send payloads are also XOR-0xA5-encrypted** (not just responses)
8. The W860NB PRO executes ANC writes immediately; HyperEars skips the readback
   round-trip to keep control latency low
9. Family candidates start with no private battery or noise-control capability. A valid battery
   response opens private battery; a valid ANC state response records that device's returned
   supported dialect and only then opens writable noise modes. A D8 function reply confirms the
   BES transport but does not by itself claim battery or ANC support.
10. The stock MiLink card keeps its native transparency, ANC and off controls. After four-mode
    evidence is confirmed, HyperEars exposes WIND as a switch beside that native section. The same
    CardAdapter recognizes both the original ANC row and HyperOS 4's select-card row; the switch is
    rendered from authoritative Adapter state and keeps no duplicate UI state.
