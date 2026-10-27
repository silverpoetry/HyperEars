package dev.hyperears.protocol.moondrop

/**
 * MOONDROP ZZZ-ANGELS-OWS wire codec for the shared MOONDROP GAIA family framing.
 *
 * Every byte in this codec comes from the 2026-10-20 btsnoop capture of the official
 * MOONDROP Link application talking to the device over SPP (firmware 1.2.0), cross-checked
 * against the vendor application's implementation. The framing is byte-identical to
 * `MoondropRobinWireCodec` / `MoondropPuddingWireCodec`:
 *
 * ```text
 * FF <type> 00 <len:u16-le> <b5> <b6> <b7> [parameters...]
 * ```
 *
 * - `type` is `01` for the family API-version request and `04` for device responses;
 * - `len` covers only the parameters, so bytes after the declared length are ignored
 *   (captured responses carry an extra trailing byte such as `40`);
 * - `b5 b6 b7` are the GAIA vendor byte and command word: battery `1D 1A 01`,
 *   API handshake `0A 03 00`.
 *
 * The ZZZ-ANGELS-OWS is an open-ear (OWS) clip model without noise cancellation, so this
 * codec intentionally covers only the handshake and battery telemetry. The family's noise
 * command dialects live in `MoondropRobinWireCodec` and `MoondropPuddingWireCodec`.
 */
object MoondropZzzAngelsWireCodec {
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

    /** `FF 01 00 00 00 0A 03 00` — captured family API-version request. */
    val handshake: ByteArray = frame(
        type = 0x01,
        command = 0x0A,
        subcommand = 0x03,
        opcode = 0x00,
        parameters = byteArrayOf(),
    )

    /** `FF 04 00 02 00 1D 1A 01 01 02` — captured battery request for the left and right buds. */
    val queryBattery: ByteArray = frame(
        command = 0x1D,
        subcommand = 0x1A,
        opcode = 0x01,
        parameters = byteArrayOf(0x01, 0x02),
    )

    /**
     * Handshake response `0A 83 00` with payload `<status> <protocol> <apiMajor> <apiMinor>`.
     *
     * The capture answers `00 04 03 01` (success, protocol v4, API 3.1), the same value the
     * family records. Only the success status and a protocol version from the vendor table
     * (1..4) are required: firmware may change the version triple, and a strict comparison
     * would drop an otherwise compatible device.
     */
    fun parseHandshake(frame: Frame): Boolean {
        if (frame.command != 0x0A || frame.subcommand != 0x83 || frame.opcode != 0x00) return false
        if (frame.parameters.size != 4) return false
        return frame.parameters[0].unsigned() == 0x00 && frame.parameters[1].unsigned() in 1..4
    }

    /** A handshake-family frame whose status byte reports failure. */
    fun isHandshakeFailure(frame: Frame): Boolean =
        frame.command == 0x0A &&
            frame.subcommand == 0x83 &&
            frame.opcode == 0x00 &&
            frame.parameters.size == 4 &&
            frame.parameters[0].unsigned() != 0x00

    /**
     * Battery state from the query response (`1D 1B 01`) or an unsolicited push (`1D 1A 81`).
     *
     * The capture shows the device answering `01 3C 02 64` — two `<id> <value>` pairs for the
     * left and right buds. The vendor battery table also defines `03 <case>` and the family's
     * Pudding reports it, so two or three pairs in any order are accepted. For both buds `00`
     * and `FF` mean "not connected / unreadable" and decode to null; the case keeps `0..100`
     * valid (an empty case is a real state) and `FF` decodes to null. Frames with unknown or
     * duplicated ids, odd parameter lengths, or no readable component are rejected.
     */
    fun parseBattery(frame: Frame): BatteryState? {
        if (frame.command != 0x1D || frame.opcode != 0x01) return null
        if (frame.subcommand != 0x1B && frame.subcommand != 0x81) return null
        if (frame.parameters.isEmpty() || frame.parameters.size % 2 != 0) return null

        var left: Int? = null
        var right: Int? = null
        var case: Int? = null
        val seen = mutableSetOf<Int>()
        for (index in frame.parameters.indices step 2) {
            val id = frame.parameters[index].unsigned()
            val value = frame.parameters[index + 1].unsigned()
            if (!seen.add(id)) return null
            when (id) {
                0x01 -> left = value.takeIf { it in 1..100 }
                0x02 -> right = value.takeIf { it in 1..100 }
                0x03 -> case = value.takeIf { it in 0..100 }
                else -> return null
            }
        }
        if (left == null && right == null && case == null) return null
        return BatteryState(left, right, case)
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

    /** Streaming frame splitter tolerant of RFCOMM fragmentation and coalescing. */
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
