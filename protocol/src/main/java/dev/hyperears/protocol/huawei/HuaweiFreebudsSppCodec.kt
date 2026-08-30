package dev.hyperears.protocol.huawei

/**
 * Huawei FreeBuds / FreeClip SPP wire codec.
 *
 * The devices speak a `5A` framed protocol over RFCOMM SPP. Every frame starts with the magic
 * byte `5A`, followed by a two-byte big-endian length field and a fixed `00`, then the command
 * id, a sequence of type-length-value parameters and a CRC16/XMODEM trailer:
 *
 * ```
 * [5A] [len:2BE] [00] [cmd:2] [param TLVs...] [crc:2]
 * ```
 *
 * - `len` = number of bytes from the fixed `00` through the last parameter byte (frame size is
 *   `len + 5`; the two CRC bytes are not counted). Captured frames are all below 256 bytes so
 *   the high length byte is `00` in practice, but the field is read and written as 16-bit.
 * - command id and parameter TLV fields are 1-byte values; an empty parameter is `[type][00]`.
 * - CRC16/XMODEM (poly `0x1021`, init `0x0000`) over all preceding bytes. Fields are treated as
 *   authoritative; incoming checksums are validated before a frame can become protocol evidence.
 *
 * Known commands:
 * - `01 08` battery read / response, `01 27` battery push
 * - `2B 2A` noise-mode state read / report, `2B 04` noise-mode write, `2B 03` on-device
 *   mode-change notification
 *
 * Reference: melianmiko/OpenFreebuds (GPL-3.0), `driver/huawei` package.
 */
object HuaweiFreebudsSppCodec {
    const val CMD_BATTERY_READ = 0x0108
    const val CMD_BATTERY_NOTIFY = 0x0127
    const val CMD_NOISE_STATE = 0x2B2A
    const val CMD_NOISE_WRITE = 0x2B04
    const val CMD_NOISE_CHANGE_NOTIFY = 0x2B03

    enum class NoiseMode(val wire: Int) {
        OFF(0x00),
        ANC(0x01),
        TRANSPARENCY(0x02),
    }

    /**
     * Decoded battery report. Percent bytes are authoritative `0..100` values; a missing or
     * zero-level component is reported as null. The charging flag comes from parameter 3
     * carrying `0x01` (any position, so component/charging status details are not distinguished).
     */
    data class BatteryState(
        val globalPercent: Int?,
        val leftPercent: Int?,
        val rightPercent: Int?,
        val casePercent: Int?,
        val isCharging: Boolean = false,
    )

    /**
     * Decoded noise-mode report (`2B 2A`): the mode byte with the raw level byte. Level meaning
     * is model-dependent: for ANC `0=normal, 1=comfort, 2=ultra, 3=dynamic`; for transparency
     * `1=voice_boost, 2=normal`. OFF carries no meaningful level.
     *
     * Wire layout of parameter 1 is `[level, mode]` — the mode byte is the *second* byte. This
     * mirrors the reference client (`anc.py` reads `active_mode = data[1]`, level from `data[0]`)
     * and Melianmiko's FreeBuds 4i protocol notes ("Second — current ANC mode"). Write commands
     * (`2B 04`) differ: they are `[mode, level]`, mode byte first.
     */
    data class NoiseState(
        val mode: NoiseMode,
        val level: Int?,
    )

    /** Raw decoded frame: command id plus parameters keyed by their TLV type byte. */
    data class Frame(
        val command: Int,
        val params: Map<Int, ByteArray>,
        val bytes: ByteArray,
    )

    val queryBattery: ByteArray = packet(
        CMD_BATTERY_READ,
        listOf(1 to byteArrayOf(), 2 to byteArrayOf(), 3 to byteArrayOf()),
    )

    val queryNoiseState: ByteArray = packet(
        CMD_NOISE_STATE,
        listOf(1 to byteArrayOf(), 2 to byteArrayOf()),
    )

    /**
     * Builds a `2B 04` noise-mode command. The second byte is `00` for OFF and `FF` (level
     * unchanged) for ANC / transparency, mirroring the vendor app and the reference client.
     */
    fun noiseModeCommand(mode: NoiseMode): ByteArray {
        val unchanged = if (mode == NoiseMode.OFF) 0x00 else 0xFF
        return packet(CMD_NOISE_WRITE, listOf(1 to byteArrayOf(mode.wire.toByte(), unchanged.toByte())))
    }

    /** Builds a `2B 04` noise-level command: `[mode] [level]`, leaving the mode unchanged. */
    fun noiseLevelCommand(mode: NoiseMode, level: Int): ByteArray =
        packet(CMD_NOISE_WRITE, listOf(1 to byteArrayOf(mode.wire.toByte(), level.toByte())))

    /**
     * Builds an arbitrary frame. Params keep their order (OpenFreebuds uses insertion order).
     * [command] must fit two bytes; each parameter value must fit a one-byte length field.
     */
    fun packet(command: Int, params: List<Pair<Int, ByteArray>>): ByteArray {
        require(command in 0..0xFFFF)
        params.forEach { (type, value) ->
            require(type in 0..0xFF)
            require(value.size <= 0xFF) { "parameter value exceeds 255 bytes" }
        }
        val bodySize = 2 + params.sumOf { 2 + it.second.size }
        require(bodySize + 6 <= MAX_FRAME_SIZE)
        val frame = ByteArray(bodySize + 6)
        frame[0] = MARKER
        val length = bodySize + 1
        frame[1] = (length shr 8).toByte()
        frame[2] = length.toByte()
        frame[3] = 0x00
        frame[4] = (command shr 8).toByte()
        frame[5] = command.toByte()
        var offset = 6
        for ((type, value) in params) {
            frame[offset++] = type.toByte()
            frame[offset++] = value.size.toByte()
            value.copyInto(frame, offset)
            offset += value.size
        }
        val crc = crc16Xmodem(frame, 0, frame.size - 2)
        frame[frame.size - 2] = (crc shr 8).toByte()
        frame[frame.size - 1] = crc.toByte()
        return frame
    }

    /**
     * CRC16/XMODEM over [data] in `[from, to)`: poly `0x1021`, init `0x0000`, no reflection,
     * no final XOR. Matches OpenFreebuds' table implementation byte-for-byte.
     */
    fun crc16Xmodem(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (data[i].unsigned() shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    /** Decodes a full frame; returns null for malformed bytes or a mismatched CRC. */
    fun parseFrame(bytes: ByteArray): Frame? {
        if (bytes.size < MIN_FRAME_SIZE) return null
        if (bytes[0] != MARKER) return null
        val length = (bytes[1].unsigned() shl 8) or bytes[2].unsigned()
        if (bytes.size != length + 5) return null
        if (bytes[3] != 0x00.toByte()) return null
        val expectedCrc = (bytes[bytes.lastIndex - 1].unsigned() shl 8) or bytes.last().unsigned()
        if (crc16Xmodem(bytes, 0, bytes.size - 2) != expectedCrc) return null
        val command = (bytes[4].unsigned() shl 8) or bytes[5].unsigned()
        val params = LinkedHashMap<Int, ByteArray>()
        var position = 6
        while (position < bytes.size - 2) {
            val type = bytes[position].unsigned()
            val valueLength = bytes[position + 1].unsigned()
            if (position + 2 + valueLength > bytes.size - 2) return null
            if (params.containsKey(type)) return null
            params[type] = bytes.copyOfRange(position + 2, position + 2 + valueLength)
            position += valueLength + 2
        }
        return Frame(command, params, bytes)
    }

    /**
     * Decodes a battery report (`01 08` response or `01 27` push). Parameter 1 is the global
     * percent, parameter 2 the `[left, right, case]` components, parameter 3 the charging flag.
     * Returns null when no level could be read.
     */
    fun parseBatteryFrame(bytes: ByteArray): BatteryState? {
        val frame = parseFrame(bytes) ?: return null
        if (frame.command != CMD_BATTERY_READ && frame.command != CMD_BATTERY_NOTIFY) return null
        val global = frame.params[1]?.singleOrNull()?.unsigned()?.percentOrNull()
        val components = frame.params[2]?.takeIf { it.size == 3 }
        val left = components?.get(0)?.unsigned()?.percentOrNull()
        val right = components?.get(1)?.unsigned()?.percentOrNull()
        val case = components?.get(2)?.unsigned()?.percentOrNull()
        if (global == null && left == null && right == null && case == null) return null
        val isCharging = frame.params[3]?.contains(0x01.toByte()) == true
        return BatteryState(global, left, right, case, isCharging)
    }

    /**
     * Decodes a `2B 2A` noise-mode report. Parameter 1 is `[level, mode]` — the mode byte is the
     * second byte (`data[1]`), the level the first (`data[0]`). See the reference client's
     * `anc.py` (`active_mode = data[1]`) and Melianmiko's protocol notes.
     */
    fun parseNoiseState(bytes: ByteArray): NoiseState? {
        val frame = parseFrame(bytes) ?: return null
        if (frame.command != CMD_NOISE_STATE) return null
        val data = frame.params[1] ?: return null
        if (data.size != 2) return null
        val mode = NoiseMode.entries.firstOrNull { it.wire == data[1].unsigned() } ?: return null
        val level = data[0].unsigned().takeIf { mode != NoiseMode.OFF }
        return NoiseState(mode, level)
    }

    /**
     * Streaming frame splitter for the `5A` protocol.
     *
     * RFCOMM delivers a continuous byte stream: one read may carry a partial frame, several
     * complete frames, or leading noise. The decoder buffers bytes, locates the magic byte,
     * cuts frames by their length field and performs bounded resync on malformed lengths.
     */
    class Decoder {
        private var pending = ByteArray(0)

        fun offer(bytes: ByteArray): List<ByteArray> {
            if (bytes.isEmpty()) return emptyList()
            pending += bytes
            val frames = mutableListOf<ByteArray>()
            while (pending.size >= MIN_FRAME_SIZE) {
                val marker = pending.indexOfMarker()
                if (marker < 0) {
                    pending = ByteArray(0)
                    break
                }
                if (marker > 0) pending = pending.copyOfRange(marker, pending.size)
                if (pending.size < MIN_FRAME_SIZE) break

                val length = (pending[1].unsigned() shl 8) or pending[2].unsigned()
                val frameSize = length + 5
                if (length < MIN_PAYLOAD_LENGTH) {
                    // Small keepalive frame: consume the whole frame and discard, mirroring
                    // OpenFreebuds' `length < 4` read-and-discard branch.
                    if (pending.size < frameSize) break
                    pending = pending.copyOfRange(frameSize, pending.size)
                    continue
                }
                if (frameSize > MAX_FRAME_SIZE || pending[3] != 0x00.toByte()) {
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }
                if (pending.size < frameSize) break

                frames += pending.copyOfRange(0, frameSize)
                pending = pending.copyOfRange(frameSize, pending.size)
            }
            return frames
        }

        fun reset() {
            pending = ByteArray(0)
        }

        private fun ByteArray.indexOfMarker(): Int {
            for (index in 0 until size) {
                if (this[index] == MARKER) return index
            }
            return -1
        }
    }

    fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it.unsigned()) }

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    // Zero percent means that component is not connected.
    private fun Int.percentOrNull(): Int? = takeIf { it in 1..100 }

    private const val MARKER = 0x5A.toByte()
    private const val MIN_FRAME_SIZE = 6
    private const val MIN_PAYLOAD_LENGTH = 4
    private const val MAX_FRAME_SIZE = 4096
}
