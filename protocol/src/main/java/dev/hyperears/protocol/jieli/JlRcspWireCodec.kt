package dev.hyperears.protocol.jieli

/**
 * JieLi (杰理, JL) RCSP framing carried over the headset's Bluetooth Classic SPP service.
 *
 * Captured on real hardware from DaiLing T3 (`DaiLing T3`, JL AC697 firmware) while the vendor app
 * (`com.dailing.audio` v1.0.36) was driving the headset:
 *
 * ```
 * [0xFE][0xDC][0xBA][FLAG][OPCODE][LEN_H][LEN_L][DATA...]
 * ```
 *
 * - `LEN` is big-endian and counts only `DATA`.
 * - There is **no checksum**: the next frame starts at the next `FE DC BA` marker, so the decoder
 *   resynchronises on the marker instead of validating a trailer.
 * - `FLAG` identifies direction: `0x00` device response, `0x80` device event report, `0xC0` app
 *   command. App commands start `DATA` with their sequence byte; device frames usually echo the
 *   same byte first as well.
 * - Both directions reuse the same opcodes and often the same attribute layout, so every parser
 *   below rejects app-originated frames explicitly. A headset must never "confirm" a feature from a
 *   byte sequence its own app wrote.
 *
 * Evidence from the capture:
 * - device information response `0x03` (124 bytes) — used only as the handshake proof;
 * - noise attribute `FF 0A 0D <mode> <8 bytes>` written by the app with `op 0x08` and reported back
 *   with the same layout — `00` off, `01` ANC, `02` transparency, plus a per-mode parameter block
 *   (`0x6000` for ANC, `0x4000` for transparency, zeros for off);
 * - `0xC1` name/battery channel carrying `04 00 <left> <right>`;
 * - `0xC2` TWS state packet carrying the peer address and both component levels.
 */
object JlRcspWireCodec {
    const val HEADER_0 = 0xFE
    const val HEADER_1 = 0xDC
    const val HEADER_2 = 0xBA

    const val HEADER_SIZE = 7
    const val MAX_DATA_SIZE = 512

    /** Frame end tag required by the official `JL_PACKET` layout. */
    const val END_FLAG: Byte = 0xEF.toByte()

    /** Device -> app response. */
    const val FLAG_DEVICE_RESPONSE = 0x00

    /** Device -> app event report. */
    const val FLAG_DEVICE_EVENT = 0x80

    /** App -> device command. */
    const val FLAG_APP_COMMAND = 0xC0

    const val OP_DEVICE_INFO = 0x03
    const val OP_ATTRIBUTE_WRITE = 0x07
    const val OP_ATTRIBUTE_SET = 0x08
    const val OP_ATTRIBUTE_REPORT = 0x09
    const val OP_EXT_ATTRIBUTE = 0xFF
    /** ADV-info channel: device reports and settings queries share this opcode (`0xC1`). */
    const val OP_ADV_INFO = 0xC1
    const val OP_NAME_BATTERY = OP_ADV_INFO
    const val OP_TWS_STATE = 0xC2
    const val OP_AUX_QUERY = 0xC3

    /**
     * Attribute identifier for the noise-processing state: `FF 0A 0D <mode> <8 parameter bytes>`.
     *
     * Written by the vendor app with opcode `0x08` and reported back by the headset with the same
     * layout, which is why the app's own write frame is byte-identical to a device report apart from
     * the frame flag.
     */
    const val ATTRIBUTE_NOISE_MARKER = 0xFF
    const val ATTRIBUTE_NOISE_HI = 0x0A
    const val ATTRIBUTE_NOISE_LO = 0x0D

    /** `0x0E` attribute: all noise modes; the vendor app carries the ANC depth level here. */
    const val ATTR_ALL_NOISE_MODE: Byte = 0x0E

    /** Custom command for the dual-device (second slot) switch. */
    const val CUSTOM_COMMAND_DUAL_CONNECTION: Byte = 0x1A
    const val DUAL_CONNECTION_OFF: Int = 0x00
    const val DUAL_CONNECTION_ON: Int = 0x01
    const val DUAL_CONNECTION_QUERY: Int = 0xFF

    /** ADV-info settings write; the game-mode LTV record rides on it. */
    const val OP_SET_ADV_INFO = 0xC0
    const val WORK_MODE_LTV_LENGTH: Byte = 0x02
    const val WORK_MODE_LTV_TYPE: Byte = 0x05
    const val WORK_MODE_NORMAL: Byte = 0x01
    const val WORK_MODE_GAME: Byte = 0x02

    /** ADV-info function mask for the work mode (function 5). */
    const val ADV_MASK_WORK_MODE = 0x20

    const val NOISE_MODE_OFF = 0x00
    const val NOISE_MODE_ANC = 0x01
    const val NOISE_MODE_TRANSPARENCY = 0x02

    /** First byte of the four little-endian parameter pairs carried with each noise mode. */
    const val NOISE_PARAM_OFF = 0x00
    const val NOISE_PARAM_ANC = 0x60
    const val NOISE_PARAM_TRANSPARENCY = 0x40

    /** Sub-command byte of the `0xC1` battery channel (`04 00 <left> <right> 00`). */
    const val BATTERY_SUBCOMMAND = 0x04

    data class Frame(
        val flag: Int,
        val opCode: Int,
        val data: ByteArray,
        val bytes: ByteArray,
    ) {
        /** Only `0xC0` frames are app commands; `0x00`/`0x80` come from the headset. */
        val fromDevice: Boolean get() = flag != FLAG_APP_COMMAND
    }

    /** One authoritative component battery reading; missing components stay null. */
    data class BatteryReport(
        val leftPercent: Int?,
        val rightPercent: Int?,
    ) {
        val usable: Boolean get() = leftPercent != null || rightPercent != null
    }

    /** Noise-processing state decoded from the `0A 0D` attribute. */
    enum class NoiseState(val rawValue: Int, val parameterByte: Int) {
        OFF(NOISE_MODE_OFF, NOISE_PARAM_OFF),
        ANC(NOISE_MODE_ANC, NOISE_PARAM_ANC),
        TRANSPARENCY(NOISE_MODE_TRANSPARENCY, NOISE_PARAM_TRANSPARENCY),
        ;

        companion object {
            fun fromRaw(value: Int): NoiseState? = entries.firstOrNull { it.rawValue == value }
        }
    }

    // ── Commands ──

    /**
     * Read-only status query used as the handshake probe.
     *
     * Shape copied from the vendor app's own request (`FE DC BA C0 C3 00 02 07 00`): opcode `0xC3`
     * with the two selector bytes the app uses.
     */
    fun queryStatus(): ByteArray =
        frame(FLAG_APP_COMMAND, OP_AUX_QUERY, byteArrayOf(0x07, 0x00))

    /**
     * Vendor app initialisation burst, copied byte for byte from the capture.
     *
     * The headset stays silent after authentication until these five read-only requests arrive;
     * only then does it start reporting device information, battery and TWS state.
     */
    fun appInitialization(startSequence: Int = 0): List<ByteArray> {
        var sequence = startSequence and 0xFF
        fun command(opCode: Int, vararg payload: Int): ByteArray {
            val data = byteArrayOf(sequence.toByte()) + ByteArray(payload.size) { payload[it].toByte() }
            sequence = (sequence + 1) and 0xFF
            return frame(FLAG_APP_COMMAND, opCode, data)
        }
        return listOf(
            command(OP_DEVICE_INFO, 0xFF, 0xFF, 0xFF, 0xFF, 0x00),
            command(OP_ATTRIBUTE_WRITE, 0xFF, 0x00, 0x00, 0x00, 0x04),
            command(OP_AUX_QUERY, 0x00),
            command(OP_NAME_BATTERY, 0x00, 0x00, 0x00, 0x01),
            command(OP_NAME_BATTERY, 0x00, 0x00, 0x00, 0x02),
        )
    }

    /**
     * Noise-mode write.
     *
     * Captured verbatim from the vendor app: opcode `0x08`, data
     * `[sn] FF 0A 0D <mode> <p 00 p 00 p 00 p 00>` with `p = 0x60` for ANC, `0x40` for transparency
     * and `0x00` for off. Replaying this shape is the only verified way to switch the headset.
     */
    fun setNoiseMode(state: NoiseState, sequence: Int): ByteArray {
        val parameter = state.parameterByte
        val data = byteArrayOf(
            sequence.toByte(),
            ATTRIBUTE_NOISE_MARKER.toByte(),
            ATTRIBUTE_NOISE_HI.toByte(),
            ATTRIBUTE_NOISE_LO.toByte(),
            state.rawValue.toByte(),
            parameter.toByte(), 0x00,
            parameter.toByte(), 0x00,
            parameter.toByte(), 0x00,
            parameter.toByte(), 0x00,
        )
        return frame(FLAG_APP_COMMAND, OP_ATTRIBUTE_SET, data)
    }

    /**
     * Builds one frame: `FE DC BA | FLAG | OPCODE | LEN(2B) | DATA | EF`.
     *
     * The `0xEF` trailer is part of the official `JL_PACKET` layout (`JL_ONE_PACKET_LEN` equals
     * header + data + 1). Without it the headset drops the whole frame and never answers.
     */
    fun frame(flag: Int, opCode: Int, data: ByteArray): ByteArray {
        require(data.size <= MAX_DATA_SIZE) { "RCSP payload exceeds the bounded frame size" }
        val out = ByteArray(HEADER_SIZE + data.size + 1)
        out[0] = HEADER_0.toByte()
        out[1] = HEADER_1.toByte()
        out[2] = HEADER_2.toByte()
        out[3] = flag.toByte()
        out[4] = opCode.toByte()
        out[5] = ((data.size shr 8) and 0xFF).toByte()
        out[6] = (data.size and 0xFF).toByte()
        data.copyInto(out, destinationOffset = HEADER_SIZE)
        out[out.lastIndex] = END_FLAG
        return out
    }

    // ── Parsing ──

    /**
     * Decodes component battery levels from a **device** frame.
     *
     * `0xC1` reports the `04 00 <left> <right>` shape; `0xC2` carries the peer address followed by
     * both levels. Values outside `1..100` invalidate the field instead of being clamped.
     */
    fun parseBattery(frame: Frame): BatteryReport? {
        if (!frame.fromDevice) return null
        val data = frame.data
        return when (frame.opCode) {
            OP_NAME_BATTERY -> {
                val index = indexOfSequence(data, BATTERY_SUBCOMMAND, 0x00)
                if (index < 0 || index + 3 >= data.size) {
                    null
                } else {
                    BatteryReport(
                        leftPercent = data[index + 2].unsigned().takeIf { it in 1..100 },
                        rightPercent = data[index + 3].unsigned().takeIf { it in 1..100 },
                    ).takeIf(BatteryReport::usable)
                }
            }

            OP_TWS_STATE -> {
                // Captured: 0F 05 D6 00 00 00 00 22 <peer address x6> 00 <left> <right> 00 17
                if (data.size < TWS_STATE_MIN_SIZE) {
                    null
                } else {
                    BatteryReport(
                        leftPercent = data[TWS_LEFT_OFFSET].unsigned().takeIf { it in 1..100 },
                        rightPercent = data[TWS_RIGHT_OFFSET].unsigned().takeIf { it in 1..100 },
                    ).takeIf(BatteryReport::usable)
                }
            }

            else -> null
        }
    }

    /** Decodes the noise-mode attribute from a **device** frame. */
    fun parseNoiseState(frame: Frame): NoiseState? {
        if (!frame.fromDevice) return null
        val data = frame.data
        var index = 0
        while (index + 3 < data.size) {
            if (data[index].unsigned() == ATTRIBUTE_NOISE_MARKER &&
                data[index + 1].unsigned() == ATTRIBUTE_NOISE_HI &&
                data[index + 2].unsigned() == ATTRIBUTE_NOISE_LO
            ) {
                return NoiseState.fromRaw(data[index + 3].unsigned())
            }
            index++
        }
        return null
    }

    /**
     * Dual-device connection (`SYS_INFO_ATTR_AI`, custom command `0x1A`).
     *
     * The vendor SDK sends `1A 01` to enable and `1A 00` to disable the second device slot; it
     * reads the current value with `1A FF` right after connecting.
     */
    fun setDualConnection(sequence: Int, enabled: Boolean): ByteArray = frame(
        FLAG_APP_COMMAND,
        OP_EXT_ATTRIBUTE,
        byteArrayOf(
            sequence.toByte(),
            CUSTOM_COMMAND_DUAL_CONNECTION,
            (if (enabled) DUAL_CONNECTION_ON else DUAL_CONNECTION_OFF).toByte(),
        ),
    )

    /** Reads the current dual-device value; the device answers with the same custom command. */
    fun queryDualConnection(sequence: Int): ByteArray = frame(
        FLAG_APP_COMMAND,
        OP_EXT_ATTRIBUTE,
        byteArrayOf(sequence.toByte(), CUSTOM_COMMAND_DUAL_CONNECTION, DUAL_CONNECTION_QUERY.toByte()),
    )

    /**
     * Current dual-device value from a device report (`[seq] 1A <0|1>`).
     *
     * The earbuds answer custom commands with the `0xC0` flag, which the decoder otherwise treats
     * as an app frame; direction is not required here because the session only feeds device bytes.
     */
    fun parseDualConnection(frame: Frame): Boolean? {
        if (frame.opCode != OP_EXT_ATTRIBUTE) return null
        val data = frame.data
        if (data.size < 3) return null
        if (data[1] != CUSTOM_COMMAND_DUAL_CONNECTION) return null
        return when (data[2].unsigned()) {
            DUAL_CONNECTION_OFF -> false
            DUAL_CONNECTION_ON -> true
            else -> null
        }
    }

    /**
     * Low-latency game mode (`CMD_ADV_SETTINGS`): opcode `0xC0` carrying an LTV record
     * `LEN=2, TYPE=5, VALUE` where the vendor SDK maps `NORMAL=1`, `GAME=2`.
     */
    fun setGameMode(sequence: Int, enabled: Boolean): ByteArray = frame(
        FLAG_APP_COMMAND,
        OP_SET_ADV_INFO,
        byteArrayOf(
            sequence.toByte(),
            WORK_MODE_LTV_LENGTH,
            WORK_MODE_LTV_TYPE,
            if (enabled) WORK_MODE_GAME else WORK_MODE_NORMAL,
        ),
    )

    /** Queries one ADV-info function mask; the vendor app reads work mode (function 5) this way. */
    fun queryAdvSettings(sequence: Int, mask: Int = ADV_MASK_WORK_MODE): ByteArray = frame(
        FLAG_APP_COMMAND,
        OP_ADV_INFO,
        byteArrayOf(
            sequence.toByte(),
            (mask shr 24).toByte(),
            (mask shr 16).toByte(),
            (mask shr 8).toByte(),
            mask.toByte(),
        ),
    )

    /** Parses the work-mode LTV (`02 05 <1|2>`) from a device ADV-info response. */
    fun parseWorkMode(frame: Frame): Boolean? {
        if (frame.opCode != OP_ADV_INFO) return null
        val data = frame.data
        var index = 0
        while (index + 2 < data.size) {
            if (data[index] == WORK_MODE_LTV_LENGTH && data[index + 1] == WORK_MODE_LTV_TYPE) {
                return when (data[index + 2]) {
                    WORK_MODE_NORMAL -> false
                    WORK_MODE_GAME -> true
                    else -> null
                }
            }
            index++
        }
        return null
    }

    /** ANC depth levels; capture values: `0x00` deep, `0x01` light, `0x02` middle. */
    enum class AncDepth(val wire: Int) {
        DEEP(0x00),
        LIGHT(0x01),
        MIDDLE(0x02),
    }

    /** Sets the ANC depth: `0xFF` with data `[seq] 0E 01 <level>` (captured from the app). */
    fun setAncDepth(sequence: Int, depth: AncDepth): ByteArray =
        frame(
            FLAG_APP_COMMAND,
            OP_EXT_ATTRIBUTE,
            byteArrayOf(
                sequence.toByte(),
                ATTR_ALL_NOISE_MODE,
                0x01,
                depth.wire.toByte(),
            ),
        )

    /**
     * Current noise-mode query: `0x07` with data `[seq] FF 00 00 20 00`.
     *
     * The capture shows the vendor app reading the current mode this way; the device answers with
     * the `FF 0A 0D <mode>` attribute block. The headset never pushes the noise state on connect,
     * so this read is required to open the three-state control.
     */
    fun currentNoiseModeQuery(sequence: Int): ByteArray =
        frame(
            FLAG_APP_COMMAND,
            OP_ATTRIBUTE_WRITE,
            byteArrayOf(
                sequence.toByte(),
                0xFF.toByte(),
                0x00,
                0x00,
                0x20,
                0x00,
            ),
        )

    /** True when the frame is a device-information response (`0x03`). */
    fun isDeviceInfo(frame: Frame): Boolean =
        frame.opCode == OP_DEVICE_INFO && frame.fromDevice && frame.data.size >= DEVICE_INFO_MIN_SIZE

    private fun indexOfSequence(data: ByteArray, first: Int, second: Int): Int {
        var index = 0
        while (index + 1 < data.size) {
            if (data[index].unsigned() == first && data[index + 1].unsigned() == second) return index
            index++
        }
        return -1
    }

    // ── Streaming decoder ──

    /**
     * Bounded marker-driven decoder.
     *
     * SPP delivers an arbitrary byte stream; the decoder keeps only the undecoded tail and
     * resynchronises on the three-byte marker whenever the declared length cannot be satisfied.
     */
    class Decoder(initialCapacity: Int = 256) {
        private var bytes = ByteArray(initialCapacity.coerceAtLeast(HEADER_SIZE))
        private var size = 0

        fun offer(chunk: ByteArray): List<Frame> {
            if (chunk.isNotEmpty()) append(chunk)
            val frames = mutableListOf<Frame>()
            while (true) {
                if (!alignToHeader()) return frames
                if (size < HEADER_SIZE) return frames
                val dataLength = (peek(5) shl 8) or peek(6)
                if (dataLength > MAX_DATA_SIZE) {
                    discard(1)
                    continue
                }
                val frameLength = HEADER_SIZE + dataLength
                if (size < frameLength) return frames
                val candidate = peekBytes(frameLength)
                frames += Frame(
                    flag = candidate[3].unsigned(),
                    opCode = candidate[4].unsigned(),
                    data = candidate.copyOfRange(HEADER_SIZE, frameLength),
                    bytes = candidate,
                )
                discard(frameLength)
            }
        }

        fun reset() {
            size = 0
        }

        private fun alignToHeader(): Boolean {
            while (size >= 1) {
                if (size < 3) {
                    if (peek(0) != HEADER_0) {
                        discard(1)
                        continue
                    }
                    return true
                }
                if (peek(0) == HEADER_0 && peek(1) == HEADER_1 && peek(2) == HEADER_2) return true
                discard(1)
            }
            return size > 0
        }

        private fun append(chunk: ByteArray) {
            ensureCapacity(size + chunk.size)
            chunk.copyInto(bytes, destinationOffset = size)
            size += chunk.size
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

    private const val DEVICE_INFO_MIN_SIZE = 16
    private const val TWS_STATE_MIN_SIZE = 17
    private const val TWS_LEFT_OFFSET = 15
    private const val TWS_RIGHT_OFFSET = 16
}
