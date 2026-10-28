package dev.hyperears.protocol.rose

/**
 * Checksum-framed RFCOMM codec used by the ROSE BudsFeel protocol family.
 *
 * A response is encoded as `DD sequence payloadLength payload checksum AA`. The payload may be
 * either one field (as returned by Furina Endless) or an aggregate LTV block (as returned by the
 * published BudsFeel MK2 captures).
 */
object RoseBudsFeelMk2WireCodec {
    enum class NoiseMode(val value: Int) {
        ANC(1),
        OFF(2),
        TRANSPARENCY(3),
        WIND(4),
        ADAPTIVE_ANC(5),
        EXTREME_ANC(6),
    }

    sealed interface State {
        data class Battery(
            val leftPercent: Int?,
            val rightPercent: Int?,
            val casePercent: Int?,
        ) : State

        data class Noise(val mode: NoiseMode) : State
        data class Settings(val values: Map<Int, Int>) : State
    }

    fun queryStatus(sequence: Int): ByteArray =
        command(sequence, STATUS_COMMAND, STATUS_QUERY_PAYLOAD)

    fun setNoiseMode(sequence: Int, mode: NoiseMode): ByteArray =
        command(sequence, SET_COMMAND, byteArrayOf(NOISE_TYPE.toByte(), mode.value.toByte()))

    fun command(sequence: Int, command: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(REQUEST_MARKER, sequence.toByte(), command.toByte()) + payload
        return body + byteArrayOf(body.checksum(), TERMINATOR)
    }

    class Decoder(private val includeSettings: Boolean = false) {
        private var pending = ByteArray(0)

        fun offer(bytes: ByteArray): List<State> {
            if (bytes.isEmpty()) return emptyList()
            pending += bytes
            val states = mutableListOf<State>()

            while (pending.size >= MIN_FRAME_SIZE) {
                val marker = pending.indexOf(RESPONSE_MARKER)
                if (marker < 0) {
                    pending = ByteArray(0)
                    break
                }
                if (marker > 0) pending = pending.copyOfRange(marker, pending.size)

                val end = pending.validFrameEnd()
                if (end == INCOMPLETE_FRAME) break
                if (end == INVALID_FRAME) {
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }

                val frame = pending.copyOfRange(0, end + 1)
                pending = pending.copyOfRange(end + 1, pending.size)
                states += parseFrame(frame, includeSettings)
            }
            return states
        }

        fun reset() {
            pending = ByteArray(0)
        }
    }

    private fun ByteArray.validFrameEnd(): Int {
        validatedFrameEndAt(0)?.let { return it }

        // A terminator or response marker can legally occur inside extension data. Therefore a
        // partial frame is never discarded from either byte alone. Resynchronize only when a
        // complete, checksum-valid frame is already available at a later response marker.
        for (candidateStart in 1 until size) {
            if (this[candidateStart] != RESPONSE_MARKER) continue
            if (validatedFrameEndAt(candidateStart) != null) return INVALID_FRAME
        }

        // A permanently corrupt frame cannot retain the stream indefinitely.
        if (size > MAX_FRAME_SIZE) return INVALID_FRAME
        return INCOMPLETE_FRAME
    }

    /** Returns the first checksum-valid frame end at [start], or `null` while none is complete. */
    private fun ByteArray.validatedFrameEndAt(start: Int): Int? {
        if (size - start < FRAME_HEADER_SIZE) return null
        if (this[start] != RESPONSE_MARKER) return null

        val payloadLength = this[start + PAYLOAD_LENGTH_INDEX].unsigned()
        val firstChecksumIndex = start + FRAME_HEADER_SIZE + payloadLength
        if (firstChecksumIndex + 1 >= size) return null

        // The checksum excludes the candidate checksum byte itself. Carry the sum forward while
        // scanning so each possible compact/extended boundary is tested without copying arrays.
        var expectedChecksum = 0
        for (index in start until firstChecksumIndex) {
            expectedChecksum = (expectedChecksum + this[index].unsigned()) and 0xFF
        }
        for (checksumIndex in firstChecksumIndex until size - 1) {
            if (this[checksumIndex + 1] == TERMINATOR &&
                this[checksumIndex].unsigned() == expectedChecksum
            ) {
                return checksumIndex + 1
            }
            expectedChecksum = (expectedChecksum + this[checksumIndex].unsigned()) and 0xFF
        }
        return null
    }

    private fun parseFrame(frame: ByteArray, includeSettings: Boolean): List<State> {
        if (frame.size < MIN_FRAME_SIZE) return emptyList()
        if (includeSettings) return parseProfileFrame(frame)
        val payloadLength = frame[PAYLOAD_LENGTH_INDEX].unsigned()
        val payloadEnd = FRAME_HEADER_SIZE + payloadLength
        if (payloadEnd + FRAME_TRAILER_SIZE > frame.size) return emptyList()
        val states = parsePayload(
            frame.copyOfRange(FRAME_HEADER_SIZE, payloadEnd),
        ).toMutableList()
        val extensionEnd = frame.size - FRAME_TRAILER_SIZE
        if (payloadEnd < extensionEnd) {
            states += parseTlvBlock(frame, payloadEnd, extensionEnd)
        }
        if (includeSettings) {
            RoseRfcommSettingsCodec.parse(frame)?.takeIf { it.isNotEmpty() }?.let { states += State.Settings(it) }
        }
        return states
    }

    /** Opt-in profiles use flat fields; opaque touch/vendor data cannot establish capabilities. */
    private fun parseProfileFrame(frame: ByteArray): List<State> {
        val payload = frame.copyOfRange(FRAME_HEADER_SIZE, frame.size - FRAME_TRAILER_SIZE)
        val declared = frame[PAYLOAD_LENGTH_INDEX].unsigned()
        if (declared == 4 && payload.size == 4 && payload[0].unsigned() == BATTERY_TYPE) {
            return listOf(decodeBattery(payload, 1))
        }
        val fields = RoseRfcommSettingsCodec.parse(frame) ?: return emptyList()
        val states = mutableListOf<State>()
        fields[NOISE_TYPE]?.let { value ->
            value.toByte().toNoiseMode()?.let { states += State.Noise(it) }
        }
        if (!(declared == 2 && payload.size == 2)) {
            var index = if (declared == 21 && payload.firstOrNull()?.unsigned() == 1) 21 else 0
            while (index < payload.size) {
                val length = payload[index].unsigned()
                if (length == 0 && (index until payload.size).all { payload[it] == 0.toByte() }) break
                if (payload[index + 1].unsigned() == BATTERY_TYPE) {
                    if (length != 4) return emptyList()
                    states += decodeBattery(payload, index + 2)
                }
                index += length + 1
            }
        }
        if (fields.isNotEmpty()) states += State.Settings(fields)
        return states
    }

    private fun parsePayload(payload: ByteArray): List<State> {
        if (payload.size == DIRECT_NOISE_PAYLOAD_LENGTH &&
            payload[0].unsigned() == NOISE_TYPE
        ) {
            return payload[1].toNoiseMode()?.let { listOf(State.Noise(it)) }.orEmpty()
        }
        if (payload.size == DIRECT_BATTERY_PAYLOAD_LENGTH &&
            payload[0].unsigned() == BATTERY_TYPE
        ) {
            return listOf(decodeBattery(payload, 1))
        }
        return parseTlvBlock(payload, 0, payload.size)
    }

    private fun parseTlvBlock(data: ByteArray, start: Int, end: Int): List<State> {
        val result = mutableListOf<State>()
        var index = start
        while (index + 1 < end) {
            val length = data[index].unsigned()
            val entryEnd = index + length + 1
            if (length < 2 || entryEnd > end) {
                index += 1
                continue
            }

            when (data[index + 1].unsigned()) {
                NOISE_TYPE -> data[index + 2].toNoiseMode()?.let {
                    result += State.Noise(it)
                }

                BATTERY_TYPE -> if (length >= 4) {
                    result += decodeBattery(data, index + 2)
                }
            }

            val nestedStart = index + 2
            if (entryEnd - nestedStart >= 3) {
                result += parseTlvBlock(data, nestedStart, entryEnd)
            }
            index = entryEnd
        }
        return result.distinct()
    }

    private fun Byte.toNoiseMode(): NoiseMode? =
        NoiseMode.entries.firstOrNull { it.value == unsigned() }

    private fun decodeBattery(data: ByteArray, valueStart: Int): State.Battery {
        return State.Battery(
            leftPercent = data[valueStart].batteryPercent(),
            rightPercent = data[valueStart + 1].batteryPercent(),
            casePercent = data[valueStart + 2].batteryPercent(),
        )
    }

    /** BudsFeel carries the percentage in the lower seven bits. Bit 7 remains reserved. */
    private fun Byte.batteryPercent(): Int? =
        (unsigned() and BATTERY_PERCENT_MASK).takeIf { it in 0..100 }

    private fun ByteArray.checksum(): Byte = sumOf { it.unsigned() }.and(0xFF).toByte()
    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private const val STATUS_COMMAND = 0x1E
    private const val SET_COMMAND = 0x02
    private const val NOISE_TYPE = 0x09
    private const val BATTERY_TYPE = 0x0C
    private const val MIN_FRAME_SIZE = 5
    private const val FRAME_HEADER_SIZE = 3
    private const val FRAME_TRAILER_SIZE = 2
    private const val PAYLOAD_LENGTH_INDEX = 2
    private const val MAX_FRAME_SIZE = 1024
    private const val DIRECT_NOISE_PAYLOAD_LENGTH = 2
    private const val DIRECT_BATTERY_PAYLOAD_LENGTH = 4
    private const val BATTERY_PERCENT_MASK = 0x7F
    private const val INCOMPLETE_FRAME = -1
    private const val INVALID_FRAME = -2
    private const val REQUEST_MARKER: Byte = 0xFF.toByte()
    private const val RESPONSE_MARKER: Byte = 0xDD.toByte()
    private const val TERMINATOR: Byte = 0xAA.toByte()

    private val STATUS_QUERY_PAYLOAD = byteArrayOf(
        0xFA.toByte(), 0x01,
        0x07, 0x08, 0x09, 0x0C, 0x0D, 0x0E, 0x12,
        0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F,
        0x31, 0x32, 0x33, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D,
        0x3F, 0x45, 0x46, 0x49,
    )
}
