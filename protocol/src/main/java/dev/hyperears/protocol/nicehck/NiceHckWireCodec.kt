package dev.hyperears.protocol.nicehck

/** Binary RFCOMM protocol used by the NiceHCK/YuanDao OriG in. */
object NiceHckWireCodec {
    enum class NoiseMode(val value: Int) {
        OFF(0x00),
        TRANSPARENCY(0x01),
        ANC(0x02),
        DEEP_ANC(0x03),
        EXPERIMENTAL_ANC(0x10),
        WIND(0x11),
    }

    data class Frame(
        val opcode: Int,
        val parameters: ByteArray,
    )

    data class BatteryState(
        val leftPercent: Int?,
        val rightPercent: Int?,
        val casePercent: Int?,
    )

    val queryBattery: ByteArray = command(BATTERY_OPCODE)
    val queryNoiseMode: ByteArray = command(NOISE_QUERY_OPCODE)

    fun setNoiseMode(mode: NoiseMode): ByteArray =
        command(NOISE_SET_OPCODE, byteArrayOf(mode.value.toByte(), 0))

    fun command(opcode: Int, parameters: ByteArray = ByteArray(0)): ByteArray {
        val payloadLength = HEADER_PAYLOAD_SIZE + parameters.size
        return byteArrayOf(
            MARKER,
            payloadLength.toByte(),
            (payloadLength ushr 8).toByte(),
            0,
            opcode.toByte(),
            (opcode ushr 8).toByte(),
        ) + parameters
    }

    fun parseBattery(frame: Frame): BatteryState? {
        if (frame.opcode != BATTERY_OPCODE || frame.parameters.size < 3) return null
        return BatteryState(
            leftPercent = frame.parameters[0].batteryPercent(),
            rightPercent = frame.parameters[1].batteryPercent(),
            casePercent = frame.parameters[2].batteryPercent(),
        )
    }

    fun parseNoiseMode(frame: Frame): NoiseMode? {
        if (frame.opcode != NOISE_QUERY_OPCODE || frame.parameters.size != 1) return null
        return NoiseMode.entries.firstOrNull { it.value == frame.parameters[0].unsigned() }
    }

    class Decoder {
        private var pending = ByteArray(0)

        fun offer(bytes: ByteArray): List<Frame> {
            if (bytes.isEmpty()) return emptyList()
            pending += bytes
            val result = mutableListOf<Frame>()

            while (pending.size >= MIN_FRAME_SIZE) {
                val marker = pending.indexOf(MARKER)
                if (marker < 0) {
                    pending = ByteArray(0)
                    break
                }
                if (marker > 0) pending = pending.copyOfRange(marker, pending.size)
                if (pending.size < MIN_FRAME_SIZE) break

                val payloadLength = pending[1].unsigned() or (pending[2].unsigned() shl 8)
                val frameSize = payloadLength + LENGTH_PREFIX_SIZE
                if (payloadLength < HEADER_PAYLOAD_SIZE || frameSize > MAX_FRAME_SIZE) {
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }
                if (pending.size < frameSize) break

                val frame = pending.copyOfRange(0, frameSize)
                pending = pending.copyOfRange(frameSize, pending.size)
                result += Frame(
                    opcode = frame[4].unsigned() or (frame[5].unsigned() shl 8),
                    parameters = frame.copyOfRange(MIN_FRAME_SIZE, frame.size),
                )
            }
            return result
        }

        fun reset() {
            pending = ByteArray(0)
        }
    }

    private fun Byte.batteryPercent(): Int? = unsigned().takeIf { it in 1..100 }
    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private const val BATTERY_OPCODE = 0x0005
    private const val NOISE_QUERY_OPCODE = 0x0101
    private const val NOISE_SET_OPCODE = 0x0201
    private const val HEADER_PAYLOAD_SIZE = 3
    private const val LENGTH_PREFIX_SIZE = 3
    private const val MIN_FRAME_SIZE = 6
    private const val MAX_FRAME_SIZE = 1024
    private const val MARKER: Byte = 0x4E
}
