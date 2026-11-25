package dev.hyperears.protocol.edifier

/**
 * Edifier V1 (`bleVersion = 1`) command framing, used by the W820NB headphone line.
 *
 * The W860NB PRO / Evo Pro / FitClip Ultra family speaks the BES v2 framing implemented by
 * [EdifierWireCodec]; W820NB-class headphones answer only this older layout:
 *
 * ```
 * Send:    [0xAA][LEN][CMD_INDEX][PAYLOAD...][CRC_H][CRC_L]
 * Receive: [0xBB][LEN][CMD_INDEX][PAYLOAD...][CRC_H][CRC_L]   (older firmware: 0xCC header)
 * ```
 *
 * - `LEN` counts the command byte: `payload.size + 1`.
 * - Payloads are plaintext; there is no XOR obfuscation step.
 * - `CRC16 = (8217 + sum of every preceding byte) & 0xFFFF`, big-endian.
 *
 * The receive layout is confirmed by a device-originated frame captured on a retail W820NB:
 * `BB 02 C3 03 21 9C` (A2DP state push) where `8217 + 0xBB + 0x02 + 0xC3 + 0x03 = 0x219C`.
 * Sending BES v2 (`AA EC ...`) to such a headset is silently ignored, which is why the private
 * channel never completes a v2 handshake.
 */
object EdifierV1WireCodec {
    const val SEND_HEADER = 0xAA
    const val RECEIVE_HEADER = 0xBB
    const val RECEIVE_HEADER_OLD = 0xCC

    /** `BleV1CRCCode` seed used by Edifier Connect's command manager (0x2019). */
    const val CRC_SEED = 8217

    // Command indices shared with the EC family.
    const val CMD_BATTERY_QUERY = 0xD0 // 208 - battery, response payload[0] = percent
    const val CMD_ANC_QUERY = 0xCC // 204 - noise state: [mode][level]
    const val CMD_ANC_SET = 0xC1 // 193 - set noise state: [mode] or [mode][level]
    const val CMD_A2DP_STATE = 0xC3 // 195 - device-originated A2DP state push
    const val CMD_VERSION_QUERY = 0xC6 // 198 - firmware version
    const val CMD_NAME_QUERY = 0xC9 // 201 - device name
    const val CMD_FUNCTION_QUERY = 0xD8 // 216 - device capability list

    /** Verified W820NB noise-mode dialect reported by `0xCC` and accepted by `0xC1`. */
    const val ANC_MODE_STANDARD = 1 // 标准 (passive / noise control off)
    const val ANC_MODE_NOISE_CANCELLATION = 2 // 降噪
    const val ANC_MODE_TRANSPARENCY = 3 // 通透; the optional second byte is the level

    /** Longest accepted `LEN` field; the capability response carries 21 payload bytes. */
    private const val MAX_LENGTH_FIELD = 64

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

    // Pre-built query packets, byte-for-byte identical to the verified captures.
    val queryBattery: ByteArray = packet(CMD_BATTERY_QUERY) // AA 01 D0 21 94
    val queryAnc: ByteArray = packet(CMD_ANC_QUERY) // AA 01 CC 21 90
    val queryFunction: ByteArray = packet(CMD_FUNCTION_QUERY) // AA 01 D8 21 9C

    /**
     * Noise-mode write. A level is only attached for modes that accept one (通透 on W820NB):
     * `AA 02 C1 02 21 88` for 降噪, `AA 03 C1 03 06 21 90` for 通透 level 6.
     */
    fun setAnc(mode: Int, level: Int? = null): ByteArray {
        require(mode in 1..0xFF) { "Noise mode must be one byte" }
        val payload = if (level != null && level in 1..0xFF) {
            byteArrayOf(mode.toByte(), level.toByte())
        } else {
            byteArrayOf(mode.toByte())
        }
        return packet(CMD_ANC_SET, payload)
    }

    // -- Parsing --

    /** `BB 02 D0 35 21 DB` -> 0x35 = 53%. */
    fun parseBattery(frame: Frame): Int? {
        if (!isResponse(frame) || frame.commandIndex != CMD_BATTERY_QUERY) return null
        return frame.payload.singleOrNull()?.unsigned()?.takeIf { it in 0..100 }
    }

    /** `BB 03 CC 02 06 21 AB` -> mode 2 (降噪), level 6. Also accepts the `0xC1` write echo. */
    fun parseAnc(frame: Frame): AncState? {
        if (!isResponse(frame)) return null
        if (frame.commandIndex != CMD_ANC_QUERY && frame.commandIndex != CMD_ANC_SET) return null
        val mode = frame.payload.firstOrNull()?.unsigned() ?: return null
        return AncState(mode = mode, level = frame.payload.getOrNull(1)?.unsigned())
    }

    /** A device-originated frame that can establish protocol evidence. */
    fun isResponse(frame: Frame): Boolean =
        frame.header == RECEIVE_HEADER || frame.header == RECEIVE_HEADER_OLD

    // -- Frame building --

    fun packet(commandIndex: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(commandIndex in 0..0xFF) { "Command index must be one byte" }
        require(payload.size + 1 <= MAX_LENGTH_FIELD) { "Payload exceeds the V1 length field" }
        val body = ByteArray(3 + payload.size).apply {
            this[0] = SEND_HEADER.toByte()
            this[1] = (payload.size + 1).toByte()
            this[2] = commandIndex.toByte()
            payload.copyInto(this, destinationOffset = 3)
        }
        return body + crcBytes(body)
    }

    /** CRC16 over the first [length] bytes, big-endian on the wire. */
    fun crc16(data: ByteArray, length: Int = data.size): Int {
        var sum = CRC_SEED
        for (index in 0 until length) sum += data[index].unsigned()
        return sum and 0xFFFF
    }

    private fun crcBytes(data: ByteArray): ByteArray {
        val crc = crc16(data)
        return byteArrayOf((crc shr 8 and 0xFF).toByte(), (crc and 0xFF).toByte())
    }

    /**
     * Stream decoder for V1 receive frames.
     *
     * Threading and socket ownership stay in the runtime; this class only reassembles a byte
     * stream one read at a time. Bytes that cannot start a length-consistent frame are discarded,
     * so a stray BES v2 reply cannot wedge the buffer.
     */
    class Decoder(initialCapacity: Int = 128) {
        private var bytes = ByteArray(initialCapacity.coerceAtLeast(16))
        private var size = 0

        fun offer(chunk: ByteArray): List<Frame> {
            if (chunk.isEmpty()) return emptyList()
            append(chunk)
            val frames = mutableListOf<Frame>()
            while (true) {
                discardNoise()
                if (size < 3) break
                val lengthField = peek(1)
                if (lengthField !in 1..MAX_LENGTH_FIELD) {
                    discard(1)
                    continue
                }
                val frameLength = lengthField + 4
                if (size < frameLength) break
                val candidate = bytes.copyOfRange(0, frameLength)
                val declared = (candidate[frameLength - 2].unsigned() shl 8) or
                    candidate[frameLength - 1].unsigned()
                if (declared != crc16(candidate, frameLength - 2)) {
                    discard(1)
                    continue
                }
                discard(frameLength)
                frames += Frame(
                    header = candidate[0].unsigned(),
                    commandIndex = candidate[2].unsigned(),
                    payload = candidate.copyOfRange(3, 3 + lengthField - 1),
                    bytes = candidate,
                )
            }
            return frames
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
                val value = peek(count)
                if (value == RECEIVE_HEADER || value == RECEIVE_HEADER_OLD) break
                count++
            }
            if (count > 0) discard(count)
        }

        private fun peek(index: Int): Int = bytes[index].unsigned()

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
