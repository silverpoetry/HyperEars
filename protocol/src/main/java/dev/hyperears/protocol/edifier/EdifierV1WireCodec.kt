package dev.hyperears.protocol.edifier

/**
 * Edifier V1 (bleVersion=1) command framing, verified on real hardware (Edifier W820NB 双金标版
 * via Edifier Connect v8.4.48 logcat capture of the live RFCOMM session).
 *
 * Unlike the BES v2 framing used by W860NB PRO / Evo Pro / FitClip Ultra, V1 devices use:
 *
 * ```
 * Send:    [0xAA][LEN][CMD_INDEX][PAYLOAD...][CRC_H][CRC_L]
 * Receive: [0xBB][LEN][CMD_INDEX][PAYLOAD...][CRC_H][CRC_L]   (older firmware may reply 0xCC)
 * ```
 *
 * - LEN = payload.size + 1 (the command byte is counted).
 * - No XOR encryption; payloads are plaintext.
 * - CRC16 = (8217 + sum of all preceding bytes), big-endian 2 bytes. Verified:
 *   `AA 01 D0 21 94` -> 8217+AA+01+D0 = 0x2194; `BB 02 D0 35 21 DB` -> 8217+BB+02+D0+35 = 0x21DB.
 *
 * Command indices (shared with the EC family): D0 battery, CC ANC query, C1 ANC set, F2 device
 * state, D8 device function, C6 version, C9 name.
 */
object EdifierV1WireCodec {
    const val SEND_HEADER = 0xAA
    const val RECEIVE_HEADER = 0xBB
    const val RECEIVE_HEADER_OLD = 0xCC

    /** BleV1CRCCode from Edifier Connect's CommandManager (8217 = 0x2019). */
    const val CRC_SEED = 8217

    const val CMD_BATTERY_QUERY = 0xD0        // 208 — battery, response payload[0] = percent
    const val CMD_ANC_QUERY = 0xCC            // 204 — ANC/noise state: [mode][level]
    const val CMD_ANC_SET = 0xC1              // 193 — set ANC: [mode] or [mode][level]
    const val CMD_DEVICE_STATE_QUERY = 0xF2   // 242 — TWS device state
    const val CMD_FUNCTION_QUERY = 0xD8       // 216 — device capabilities
    const val CMD_VERSION_QUERY = 0xC6        // 198 — version
    const val CMD_NAME_QUERY = 0xC9           // 201 — device name
    const val CMD_GAME_STATE_QUERY = 0x08     // 8   — game mode, response payload[0] = 0/1
    const val CMD_GAME_STATE_SET = 0x09       // 9   — set game mode [1]/[0]

    // Verified ANC dialect on W820NB 双金标版:
    // 01 = 标准 (standard / NC off), 02 = 降噪 (ANC), 03 = 通透 (transparency, level byte follows).
    const val ANC_MODE_STANDARD = 1
    const val ANC_MODE_NOISE_CANCELLATION = 2
    const val ANC_MODE_TRANSPARENCY = 3

    data class Frame(
        val header: Int,
        val commandIndex: Int,
        val payload: ByteArray,
        val bytes: ByteArray,
    )

    data class AncState(
        val mode: Int,
        val level: Int?,
    )

    // ── Pre-built query packets (verified against live capture) ──

    val queryBattery: ByteArray = packet(CMD_BATTERY_QUERY)
    val queryAnc: ByteArray = packet(CMD_ANC_QUERY)
    val queryDeviceState: ByteArray = packet(CMD_DEVICE_STATE_QUERY)
    val queryFunction: ByteArray = packet(CMD_FUNCTION_QUERY)
    val queryVersion: ByteArray = packet(CMD_VERSION_QUERY)
    val queryGameState: ByteArray = packet(CMD_GAME_STATE_QUERY) // AA 01 08 20 CC

    /** Game-mode write: `AA 02 09 01 20 CF` (on) / `AA 02 09 00 20 CE` (off). */
    fun setGameState(enabled: Boolean): ByteArray =
        packet(CMD_GAME_STATE_SET, byteArrayOf(if (enabled) 1 else 0))

    /** ANC write. [level] is optional (transparency gain / ANC level for 通透 on W820NB 双金标版). */
    fun setAnc(mode: Int, level: Int? = null): ByteArray {
        require(mode in 1..0xFF)
        val payload = if (level != null && level in 1..0xFF) {
            byteArrayOf(mode.toByte(), level.toByte())
        } else {
            byteArrayOf(mode.toByte())
        }
        return packet(CMD_ANC_SET, payload)
    }

    // ── Parsing ──

    /** Battery response: `BB 02 D0 35 21 DB` -> payload [0x35] = 53%. */
    fun parseBatteryState(frame: Frame): Int? {
        if (!isProtocolResponse(frame) || frame.commandIndex != CMD_BATTERY_QUERY) return null
        return frame.payload.singleOrNull()?.unsigned()?.takeIf { it in 0..100 }
    }

    /** ANC response: `BB 03 CC 02 06 21 AB` -> mode=0x02, level=0x06. */
    fun parseAncState(frame: Frame): AncState? {
        if (!isProtocolResponse(frame) || frame.commandIndex != CMD_ANC_QUERY) return null
        val mode = frame.payload.firstOrNull()?.unsigned() ?: return null
        val level = frame.payload.getOrNull(1)?.unsigned()
        return AncState(mode = mode, level = level)
    }

    /**
     * Game-mode response (query `0x08` or set `0x09` echo): `BB 02 08 00 20 DE` -> off,
     * `BB 02 09 01 20 E0` -> on. Any other payload is not a valid boolean state.
     */
    fun parseGameState(frame: Frame): Boolean? {
        if (!isProtocolResponse(frame)) return null
        if (frame.commandIndex != CMD_GAME_STATE_QUERY && frame.commandIndex != CMD_GAME_STATE_SET) {
            return null
        }
        return when (frame.payload.singleOrNull()?.unsigned()) {
            0 -> false
            1 -> true
            else -> null
        }
    }

    /** Device-originated V1 frame that can establish protocol evidence. */
    fun isProtocolResponse(frame: Frame): Boolean =
        frame.header == RECEIVE_HEADER || frame.header == RECEIVE_HEADER_OLD

    // ── Frame building ──

    fun packet(commandIndex: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(commandIndex in 0..0xFF)
        val body = ByteArray(3 + payload.size).apply {
            this[0] = SEND_HEADER.toByte()
            this[1] = (payload.size + 1).toByte() // LEN counts the command byte
            this[2] = commandIndex.toByte()
            payload.copyInto(this, destinationOffset = 3)
        }
        return addCrc16(body)
    }

    /** Appends CRC16 = (CRC_SEED + sum of all bytes) as big-endian 2 bytes. */
    fun addCrc16(data: ByteArray): ByteArray {
        var sum = CRC_SEED
        for (b in data) sum += b.unsigned()
        return data + byteArrayOf(((sum shr 8) and 0xFF).toByte(), (sum and 0xFF).toByte())
    }

    // ── Decoder ──

    class Decoder(initialCapacity: Int = 256) {
        private var bytes = ByteArray(initialCapacity.coerceAtLeast(16))
        private var size = 0

        fun offer(chunk: ByteArray): List<Frame> {
            if (chunk.isEmpty()) return emptyList()
            append(chunk)
            val frames = mutableListOf<Frame>()
            while (true) {
                discardNoise()
                if (size < 4) return frames // header + len + cmd + crc_h (shortest frame: len=1, 2 crc bytes -> 5)

                val header = peek(0)
                if (header != RECEIVE_HEADER && header != RECEIVE_HEADER_OLD && header != SEND_HEADER) {
                    discard(1)
                    continue
                }

                val lengthField = peek(1) // payload.size + 1
                val frameLength = lengthField + 4 // header + len + cmd + payload(len-1) + crc16
                if (size < frameLength) return frames

                val candidate = peekBytes(frameLength)
                val crc16 = ((candidate[frameLength - 2].unsigned() shl 8) or
                    candidate[frameLength - 1].unsigned())
                var sum = CRC_SEED
                for (i in 0 until frameLength - 2) sum += candidate[i].unsigned()
                val expected = sum and 0xFFFF
                if (crc16 != expected) {
                    discard(1)
                    continue
                }

                discard(frameLength)
                frames += Frame(
                    header = header,
                    commandIndex = candidate[2].unsigned(),
                    payload = candidate.copyOfRange(3, 3 + lengthField - 1),
                    bytes = candidate,
                )
            }
        }

        fun reset() {
            size = 0
        }

        private fun append(chunk: ByteArray) {
            ensureCapacity(size + chunk.size)
            chunk.copyInto(bytes, destinationOffset = size)
            size += chunk.size
        }

        private fun discardNoise() {
            var count = 0
            while (count < size) {
                val b = peek(count)
                if (b == RECEIVE_HEADER || b == RECEIVE_HEADER_OLD || b == SEND_HEADER) break
                count++
            }
            if (count > 0) discard(count)
        }

        private fun peek(index: Int): Int = bytes[index].unsigned()
        private fun peekBytes(count: Int): ByteArray = bytes.copyOfRange(0, count)

        private fun discard(count: Int) {
            if (count >= size) {
                size = 0
                return
            }
            bytes.copyInto(bytes, destinationOffset = 0, startIndex = count, endIndex = size)
            size -= count
        }

        private fun ensureCapacity(required: Int) {
            if (required <= bytes.size) return
            var capacity = bytes.size
            while (capacity < required) capacity *= 2
            bytes = bytes.copyOf(capacity)
        }
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF
}
