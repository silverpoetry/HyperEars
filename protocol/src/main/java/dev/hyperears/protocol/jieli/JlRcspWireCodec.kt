package dev.hyperears.protocol.jieli

/**
 * DaiLing T3's observed RCSP subset: FE DC BA, flags, opcode, big-endian payload length, data, EF.
 * Protocol facts and the contributor's captures are documented in docs/dailing-t3-protocol.md.
 * Flags describe command/response semantics; they do not establish Bluetooth traffic direction.
 */
object JlRcspWireCodec {
    const val HEADER_SIZE = 7
    const val MAX_DATA_SIZE = 512
    const val FLAG_DEVICE_RESPONSE = 0x00
    const val FLAG_DEVICE_EVENT = 0x80
    const val FLAG_APP_COMMAND = 0xC0
    const val OP_DEVICE_INFO = 0x03
    const val OP_ATTRIBUTE_QUERY = 0x07
    const val OP_ATTRIBUTE_SET = 0x08
    const val OP_ATTRIBUTE_REPORT = 0x09
    const val OP_SET_ADV_INFO = 0xC0
    const val OP_ADV_INFO = 0xC1
    const val OP_TWS_STATE = 0xC2
    const val OP_AUX_QUERY = 0xC3
    const val END_FLAG: Byte = 0xEF.toByte()

    data class Frame(val flag: Int, val opCode: Int, val data: ByteArray, val bytes: ByteArray)

    data class BatteryReport(val leftPercent: Int?, val rightPercent: Int?) {
        val usable: Boolean get() = leftPercent != null || rightPercent != null
    }

    enum class NoiseState(val rawValue: Int, val parameterByte: Int) {
        OFF(0, 0), ANC(1, 0x60), TRANSPARENCY(2, 0x40),
    }

    fun appInitialization(startSequence: Int = 0): List<ByteArray> {
        var sequence = startSequence
        fun command(opCode: Int, vararg payload: Int): ByteArray =
            frame(FLAG_APP_COMMAND, opCode,
                byteArrayOf((sequence++).toByte()) + ByteArray(payload.size) { payload[it].toByte() })
        return listOf(
            command(OP_DEVICE_INFO, 0xFF, 0xFF, 0xFF, 0xFF, 0),
            command(OP_ATTRIBUTE_QUERY, 0xFF, 0, 0, 0, 4),
            command(OP_AUX_QUERY, 0),
            command(OP_ADV_INFO, 0, 0, 0, 1),
            command(OP_ADV_INFO, 0, 0, 0, 2),
        )
    }

    /** Captured auxiliary query, with the session's sequence rather than a reused constant. */
    fun queryStatus(sequence: Int = 7): ByteArray =
        frame(FLAG_APP_COMMAND, OP_AUX_QUERY, byteArrayOf(sequence.toByte(), 0))

    fun currentNoiseModeQuery(sequence: Int): ByteArray = frame(
        FLAG_APP_COMMAND, OP_ATTRIBUTE_QUERY,
        byteArrayOf(sequence.toByte(), 0xFF.toByte(), 0, 0, 0x20, 0),
    )

    fun queryAdvSettings(sequence: Int): ByteArray = frame(
        FLAG_APP_COMMAND, OP_ADV_INFO, byteArrayOf(sequence.toByte(), 0, 0, 0, 0x20),
    )

    /** Ordinary mode changes retain the device's own depth; no secondary depth write is added. */
    fun setNoiseMode(state: NoiseState, sequence: Int): ByteArray = frame(
        FLAG_APP_COMMAND, OP_ATTRIBUTE_SET,
        byteArrayOf(
            sequence.toByte(), 0xFF.toByte(), 0x0A, 0x0D, state.rawValue.toByte(),
            state.parameterByte.toByte(), 0, state.parameterByte.toByte(), 0,
            state.parameterByte.toByte(), 0, state.parameterByte.toByte(), 0,
        ),
    )

    fun setGameMode(sequence: Int, enabled: Boolean): ByteArray = frame(
        FLAG_APP_COMMAND, OP_SET_ADV_INFO, byteArrayOf(sequence.toByte(), 2, 5, if (enabled) 2 else 1),
    )

    fun frame(flag: Int, opCode: Int, data: ByteArray): ByteArray {
        require(data.size <= MAX_DATA_SIZE)
        return byteArrayOf(
            0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), flag.toByte(), opCode.toByte(),
            (data.size shr 8).toByte(), data.size.toByte(),
        ) + data + byteArrayOf(END_FLAG)
    }

    fun isDeviceInfo(frame: Frame): Boolean =
        frame.opCode == OP_DEVICE_INFO && successfulResponse(frame) &&
            attributes(frame.data, 2)?.isNotEmpty() == true

    /** A complete unknown report clears old levels after the session has confirmed battery. */
    fun parseBattery(frame: Frame): BatteryReport? {
        val levels = when {
            frame.opCode == OP_ADV_INFO && successfulResponse(frame) -> {
                val value = attributes(frame.data, 2)?.get(0) ?: return null
                if (value.size != 3) return null
                value.copyOfRange(0, 2)
            }
            frame.opCode == OP_TWS_STATE && frame.flag == FLAG_DEVICE_EVENT -> {
                if (frame.data.size != 19) return null
                frame.data.copyOfRange(15, 17)
            }
            else -> return null
        }
        if (levels.any { it.unsigned() !in 0..100 && it.unsigned() != 255 }) return null
        return BatteryReport(
            levels[0].unsigned().takeIf { it in 1..100 },
            levels[1].unsigned().takeIf { it in 1..100 },
        )
    }

    fun parseNoiseState(frame: Frame): NoiseState? {
        val start = when {
            frame.flag == FLAG_DEVICE_EVENT && frame.opCode == OP_ATTRIBUTE_REPORT -> 1
            successfulResponse(frame) &&
                frame.opCode in setOf(OP_ATTRIBUTE_QUERY, OP_ATTRIBUTE_SET) -> 2
            else -> return null
        }
        if (frame.data.getOrNull(start)?.unsigned() != 0xFF) return null
        val value = attributes(frame.data, start + 1)?.get(0x0D) ?: return null
        if (value.size != 9) return null
        return NoiseState.entries.firstOrNull { it.rawValue == value[0].unsigned() }
    }

    fun parseWorkMode(frame: Frame): Boolean? {
        if (frame.opCode != OP_ADV_INFO || !successfulResponse(frame)) return null
        val value = attributes(frame.data, 2)?.get(5) ?: return null
        if (value.size != 1) return null
        return when (value[0].unsigned()) { 1 -> false; 2 -> true; else -> null }
    }

    private fun successfulResponse(frame: Frame): Boolean =
        frame.flag == FLAG_DEVICE_RESPONSE && frame.data.size >= 2 && frame.data[0] == 0.toByte()

    /** LTV lengths include the type byte. Never scan arbitrary payload bytes for an attribute. */
    private fun attributes(data: ByteArray, offset: Int): Map<Int, ByteArray>? {
        if (offset >= data.size) return null
        val result = mutableMapOf<Int, ByteArray>()
        var index = offset
        while (index < data.size) {
            val length = data[index].unsigned()
            if (length < 1 || index + 1 + length > data.size) return null
            val type = data[index + 1].unsigned()
            if (type in result) return null
            result[type] = data.copyOfRange(index + 2, index + 1 + length)
            index += 1 + length
        }
        return result
    }

    /** Fixed-size undecoded tail; validates the end marker before yielding a frame. */
    class Decoder {
        private val buffer = ByteArray(HEADER_SIZE + MAX_DATA_SIZE + 1)
        private var bufferedBytes = 0

        fun offer(chunk: ByteArray): List<Frame> = buildList {
            for (value in chunk) {
                buffer[bufferedBytes++] = value
                while (bufferedBytes > 0) {
                    if (!headerPrefixMatches()) {
                        discard(1)
                        continue
                    }
                    if (bufferedBytes < HEADER_SIZE) break
                    val payloadSize = (buffer[5].unsigned() shl 8) or buffer[6].unsigned()
                    val flag = buffer[3].unsigned()
                    if (payloadSize > MAX_DATA_SIZE || flag !in VALID_FLAGS) {
                        discard(1)
                        continue
                    }
                    val frameSize = HEADER_SIZE + payloadSize + 1
                    if (bufferedBytes < frameSize) break
                    if (buffer[frameSize - 1] != END_FLAG) {
                        discard(1)
                        continue
                    }
                    val bytes = buffer.copyOf(frameSize)
                    add(Frame(flag, buffer[4].unsigned(), bytes.copyOfRange(HEADER_SIZE, frameSize - 1), bytes))
                    discard(frameSize)
                }
            }
        }

        fun reset() { bufferedBytes = 0 }

        private fun headerPrefixMatches(): Boolean =
            (0 until minOf(bufferedBytes, HEADER.size)).all { buffer[it] == HEADER[it] }

        private fun discard(count: Int) {
            buffer.copyInto(buffer, 0, count, bufferedBytes)
            bufferedBytes -= count
        }

        private companion object {
            val HEADER = byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte())
            val VALID_FLAGS = setOf(FLAG_DEVICE_RESPONSE, FLAG_DEVICE_EVENT, FLAG_APP_COMMAND)
        }
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF
}
