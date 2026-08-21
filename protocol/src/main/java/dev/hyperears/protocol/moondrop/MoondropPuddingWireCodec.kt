package dev.hyperears.protocol.moondrop

/**
 * MOONDROP Pudding's Earphones command framing observed on the Classic SPP channel.
 *
 * The frame layout matches MoondropRobinWireCodec: an 8-byte header followed by
 * variable-length parameters. The SPP UUID is intentionally not part of identity
 * matching: it is the Bluetooth SIG standard SPP service and is used only by the
 * integration transport to open RFCOMM.
 *
 * Observed captures sometimes carry one extra trailing byte after the payload
 * (e.g. `40` on headset responses). Its presence and value are inconsistent, so
 * every parser ignores everything outside the length field: only the parameters
 * covered by the declared length participate in validation.
 */
object MoondropPuddingWireCodec {
    /** Query and write share the same value for every mode. */
    enum class NoiseMode(val value: Int) {
        OFF(0x00),
        ANC(0x01),
        TRANSPARENCY(0x02),
    }

    data class Frame(
        val command: Int,
        val subcommand: Int,
        val opcode: Int,
        val parameters: ByteArray,
    )

    data class BatteryState(
        val leftPercent: Int?,
        val rightPercent: Int?,
        val casePercent: Int?,
    )

    /** FF 01 00 00 00 0A 03 00 — the same handshake command MOONDROP Robin uses. */
    val handshake: ByteArray = frame(
        type = 0x01,
        command = 0x0A,
        subcommand = 0x03,
        opcode = 0x00,
        parameters = byteArrayOf(),
    )

    /** FF 04 00 00 00 1D 40 03 */
    val queryNoiseMode: ByteArray = frame(
        command = 0x1D,
        subcommand = 0x40,
        opcode = 0x03,
        parameters = byteArrayOf(),
    )

    /** FF 04 00 02 00 1D 1A 01 01 02 */
    val queryBattery: ByteArray = frame(
        command = 0x1D,
        subcommand = 0x1A,
        opcode = 0x01,
        parameters = byteArrayOf(0x01, 0x02),
    )

    /** FF 04 00 01 00 1D 40 04 <mode> */
    fun setNoiseMode(mode: NoiseMode): ByteArray =
        frame(
            command = 0x1D,
            subcommand = 0x40,
            opcode = 0x04,
            parameters = byteArrayOf(mode.value.toByte()),
        )

    /**
     * Battery state from either the query response (1D 1B 01) or the unsolicited push (1D 1A 81):
     * parameters 01 <left> 02 <right> 03 <case>.
     *
     * For both buds, 00 and FF mean "not connected / unreadable" (e.g. bud resting in the case)
     * and decode to null. The case keeps 0..100 valid (an empty case is a real state); FF is
     * unreadable and decodes to null. A frame is rejected only when it contains no readable
     * battery component.
     */
    fun parseBattery(frame: Frame): BatteryState? {
        if (frame.command != 0x1D || frame.opcode != 0x01 || frame.parameters.size != 6) return null
        if (frame.subcommand != 0x1B && frame.subcommand != 0x81) return null
        if (
            frame.parameters[0].unsigned() != 0x01 ||
            frame.parameters[2].unsigned() != 0x02 ||
            frame.parameters[4].unsigned() != 0x03
        ) {
            return null
        }
        val left = frame.parameters[1].unsigned().takeIf { it in 1..100 }
        val right = frame.parameters[3].unsigned().takeIf { it in 1..100 }
        val case = frame.parameters[5].unsigned().takeIf { it in 0..100 }
        if (left == null && right == null && case == null) return null
        return BatteryState(left, right, case)
    }

    /**
     * Handshake response (or unsolicited hello): FF 04 00 04 00 0A 83 00 00 04 03 01.
     * The parameters must exactly match the expected payload; trailing bytes are ignored.
     */
    fun parseHandshake(frame: Frame): Boolean =
        frame.command == 0x0A &&
            frame.subcommand == 0x83 &&
            frame.opcode == 0x00 &&
            frame.parameters.contentEquals(byteArrayOf(0x00, 0x04, 0x03, 0x01))

    /**
     * Query response: FF 04 00 01 00 1D 41 03 <mode>.
     * The single parameter is the current mode; unknown values are rejected.
     * Trailing bytes after the declared payload are ignored.
     */
    fun parseNoiseModeQuery(frame: Frame): NoiseMode? {
        if (frame.command != 0x1D || frame.subcommand != 0x41 ||
            frame.opcode != 0x03 || frame.parameters.size != 1
        ) {
            return null
        }
        return NoiseMode.entries.firstOrNull { it.value == frame.parameters[0].unsigned() }
    }

    /**
     * Write confirmation: FF 04 00 01 00 1D 41 04 <mode>.
     * The single parameter echoes the written mode; unknown values are rejected.
     * Captures sometimes show an extra `40` byte after the payload; it is ignored.
     */
    fun parseNoiseModeConfirm(frame: Frame): NoiseMode? {
        if (frame.command != 0x1D || frame.subcommand != 0x41 ||
            frame.opcode != 0x04 || frame.parameters.size != 1
        ) {
            return null
        }
        return NoiseMode.entries.firstOrNull { it.value == frame.parameters[0].unsigned() }
    }

    fun frame(
        type: Int = 0x04,
        command: Int,
        subcommand: Int,
        opcode: Int,
        parameters: ByteArray,
    ): ByteArray {
        require(
            type in 0..0xFF &&
                command in 0..0xFF &&
                subcommand in 0..0xFF &&
                opcode in 0..0xFF,
        )
        require(parameters.size <= MAX_PAYLOAD_SIZE)
        val length = parameters.size
        return byteArrayOf(
            MARKER,
            type.toByte(),
            0,
            length.toByte(),
            (length ushr 8).toByte(),
            command.toByte(),
            subcommand.toByte(),
            opcode.toByte(),
        ) + parameters
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
                if (pending[1].unsigned() != RESPONSE_TYPE || pending[2].unsigned() != 0) {
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }
                val payloadLength = pending[3].unsigned() or (pending[4].unsigned() shl 8)
                val frameSize = HEADER_SIZE + payloadLength
                if (payloadLength > MAX_PAYLOAD_SIZE || frameSize > MAX_FRAME_SIZE) {
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }
                if (pending.size < frameSize) break
                result += Frame(
                    command = pending[5].unsigned(),
                    subcommand = pending[6].unsigned(),
                    opcode = pending[7].unsigned(),
                    parameters = pending.copyOfRange(HEADER_SIZE, frameSize),
                )
                pending = pending.copyOfRange(frameSize, pending.size)
            }
            return result
        }

        fun reset() {
            pending = ByteArray(0)
        }
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private const val MARKER: Byte = 0xFF.toByte()
    private const val RESPONSE_TYPE = 0x04
    private const val HEADER_SIZE = 8
    private const val MIN_FRAME_SIZE = HEADER_SIZE
    private const val MAX_PAYLOAD_SIZE = 256
    private const val MAX_FRAME_SIZE = HEADER_SIZE + MAX_PAYLOAD_SIZE
}
