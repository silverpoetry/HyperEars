package dev.hyperears.protocol.sennheiser

/**
 * Qualcomm GAIA V3 framing and the Sennheiser vendor commands used by HyperEars.
 *
 * GAIA travels directly over RFCOMM/SPP. The payload length excludes the eight-byte header and
 * every multi-byte field is big-endian. This codec is a pure byte transformer: it never opens a
 * channel, never touches an Android API and never decides which capabilities are available.
 */
object SennheiserGaiaWireCodec {
    /**
     * SDP service record used to open the GAIA channel.
     *
     * Channel numbers differ between models and firmware revisions, and the iAP2 record on the
     * same device answers GAIA frames by powering the headset off, so the UUID is the only
     * acceptable endpoint selector.
     */
    const val GAIA_SERVICE_UUID = "a2129ff3-081b-4c45-8afe-469d9c4842ec"

    const val VENDOR_SENNHEISER = 0x0495
    const val VENDOR_QUALCOMM = 0x001D

    const val FRAME_START = 0xFF
    const val FRAME_FLAGS = 0x03
    const val HEADER_SIZE = 8

    /** Defensive ceiling for a single payload; GAIA never legitimately approaches this. */
    const val MAX_PAYLOAD_SIZE = 512

    const val COMMAND_REGISTER_NOTIFICATION = 0x0007
    const val COMMAND_BATTERY = 0x0603
    const val COMMAND_MODEL_ID = 0x1206
    const val COMMAND_TRANSPARENCY_TOGGLE = 0x1804
    const val COMMAND_COMBINED_NOISE_MODE = 0x1A00
    const val COMMAND_ANC_TOGGLE = 0x1A04

    /** Feature ids accepted by [COMMAND_REGISTER_NOTIFICATION]. */
    const val FEATURE_TRANSPARENT_HEARING = 12
    const val FEATURE_ANC = 13
    const val FEATURE_BATTERY = 3

    /**
     * Sub-selectors carried inside the combined noise-mode payload.
     *
     * `0x1A00` is *not* the master noise switch: it only tunes the three sub-behaviours that ride on
     * top of the ANC master switch (`0x1A04`) and the transparent-hearing switch (`0x1804`). Selector
     * 2 is Comfort, not transparency — transparency is its own `0x1804` command.
     */
    const val NOISE_MODE_ANTI_WIND = 1
    const val NOISE_MODE_COMFORT = 2
    const val NOISE_MODE_ADAPTIVE = 3

    const val STATE_OFF = 0
    const val STATE_ON = 1

    /** Anti-wind accepts a third state on top of [STATE_OFF] and [STATE_ON] (maximum). */
    const val STATE_ANTI_WIND_AUTO = 2

    /** Type of a GAIA command id, encoded in bits 7-8 of the command id. */
    enum class FrameType(val value: Int) {
        COMMAND(0),
        NOTIFICATION(1),
        RESPONSE(2),
        ERROR(3),
        ;

        companion object {
            fun fromValue(value: Int): FrameType? = entries.firstOrNull { it.value == value }
        }
    }

    /** One `(mode, state)` pair of the combined noise-mode payload. */
    data class NoiseModeEntry(
        val mode: Int,
        val state: Int,
    )

    fun encode(
        vendorId: Int,
        commandId: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        require(vendorId in 0..0xFFFF) { "vendor id must fit in 16 bits: $vendorId" }
        require(commandId in 0..0xFFFF) { "command id must fit in 16 bits: $commandId" }
        require(payload.size <= MAX_PAYLOAD_SIZE) {
            "payload exceeds $MAX_PAYLOAD_SIZE bytes: ${payload.size}"
        }
        val length = payload.size
        return byteArrayOf(
            FRAME_START.toByte(),
            FRAME_FLAGS.toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
            (vendorId ushr 8).toByte(),
            vendorId.toByte(),
            (commandId ushr 8).toByte(),
            commandId.toByte(),
        ) + payload
    }

    val queryBattery: ByteArray = encode(VENDOR_SENNHEISER, COMMAND_BATTERY)

    val queryCombinedNoiseMode: ByteArray =
        encode(VENDOR_SENNHEISER, COMMAND_COMBINED_NOISE_MODE + GET_OFFSET)

    val queryAncToggle: ByteArray = encode(VENDOR_SENNHEISER, COMMAND_ANC_TOGGLE + GET_OFFSET)

    val queryTransparencyToggle: ByteArray =
        encode(VENDOR_SENNHEISER, COMMAND_TRANSPARENCY_TOGGLE + GET_OFFSET)

    val queryModelId: ByteArray = encode(VENDOR_SENNHEISER, COMMAND_MODEL_ID)

    val queryQualcommApiVersion: ByteArray = encode(VENDOR_QUALCOMM, 0x0000)

    fun setCombinedNoiseMode(mode: Int, state: Int): ByteArray {
        require(mode in 0..0xFF) { "noise mode selector must fit in a byte: $mode" }
        require(state in 0..0xFF) { "noise mode state must fit in a byte: $state" }
        return encode(
            VENDOR_SENNHEISER,
            COMMAND_COMBINED_NOISE_MODE,
            byteArrayOf(mode.toByte(), state.toByte()),
        )
    }

    fun setAncToggle(enabled: Boolean): ByteArray = encode(
        VENDOR_SENNHEISER,
        COMMAND_ANC_TOGGLE,
        byteArrayOf((if (enabled) STATE_ON else STATE_OFF).toByte()),
    )

    fun setTransparencyToggle(enabled: Boolean): ByteArray = encode(
        VENDOR_SENNHEISER,
        COMMAND_TRANSPARENCY_TOGGLE,
        byteArrayOf((if (enabled) STATE_ON else STATE_OFF).toByte()),
    )

    fun registerNotification(featureId: Int): ByteArray {
        require(featureId in 0..0xFF) { "feature id must fit in a byte: $featureId" }
        return encode(
            VENDOR_SENNHEISER,
            COMMAND_REGISTER_NOTIFICATION,
            byteArrayOf(featureId.toByte()),
        )
    }

    /**
     * Reads the six-byte `(mode, state)` triplet returned by a combined noise-mode response.
     *
     * The payload is positional rather than self-describing, so a short or misaligned body yields
     * null instead of a partially populated state.
     */
    fun parseCombinedNoiseMode(payload: ByteArray): List<NoiseModeEntry>? {
        if (payload.size != COMBINED_NOISE_MODE_PAYLOAD_SIZE) return null
        return List(payload.size / NOISE_MODE_ENTRY_SIZE) { index ->
            val offset = index * NOISE_MODE_ENTRY_SIZE
            NoiseModeEntry(
                mode = payload[offset].toInt() and BYTE_MASK,
                state = payload[offset + 1].toInt() and BYTE_MASK,
            )
        }
    }

    /** Battery percentage carried by a `0x0603` response, or null when out of range. */
    fun parseBatteryPercent(payload: ByteArray): Int? {
        val raw = payload.singleOrNull()?.toInt()?.and(BYTE_MASK) ?: return null
        return raw.takeIf { it in 0..100 }
    }

    /** Master switches report one byte: 0 off, 1 on. Other values carry no confirmed state. */
    fun parseToggle(payload: ByteArray): Boolean? = when (payload.singleOrNull()?.toInt()) {
        STATE_OFF -> false
        STATE_ON -> true
        else -> null
    }

    /** Model string carried by a `0x1206` response, trimmed of its NUL terminator. */
    fun parseModelId(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val terminator = payload.indexOfFirst { it == 0.toByte() }
        val end = if (terminator >= 0) terminator else payload.size
        if (end == 0) return null
        return payload.copyOfRange(0, end).toString(Charsets.US_ASCII)
    }

    internal fun hex(value: String): ByteArray {
        val compact = value.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        require(compact.length % 2 == 0) { "hex literal must have an even digit count" }
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private const val GET_OFFSET = 1
    private const val BYTE_MASK = 0xFF
    private const val NOISE_MODE_ENTRY_SIZE = 2
    private const val COMBINED_NOISE_MODE_PAYLOAD_SIZE = 6
}

/** One decoded GAIA frame, pairing the raw fields with their derived type and base command. */
data class SennheiserGaiaFrame(
    val vendorId: Int,
    val commandId: Int,
    val payload: ByteArray,
) {
    val type: SennheiserGaiaWireCodec.FrameType?
        get() = SennheiserGaiaWireCodec.FrameType.fromValue(
            (commandId shr COMMAND_TYPE_SHIFT) and COMMAND_TYPE_MASK,
        )

    /** Command id with the type bits cleared, so responses and notifications pair with requests. */
    val baseCommandId: Int
        get() = commandId and COMMAND_BASE_MASK

    private companion object {
        const val COMMAND_TYPE_SHIFT = 7
        const val COMMAND_TYPE_MASK = 0x03
        const val COMMAND_BASE_MASK = 0xFE7F
    }
}

/**
 * Reassembles GAIA frames across arbitrary RFCOMM reads.
 *
 * A stream may split a frame anywhere, may concatenate several frames, and may carry bytes that
 * are not GAIA at all. Anything that cannot become a frame is discarded one byte at a time so the
 * reader resynchronises on the next `FF 03` marker instead of stalling on a corrupt length.
 */
class SennheiserGaiaFrameReader {
    private var pending = ByteArray(0)

    fun offer(bytes: ByteArray): List<SennheiserGaiaFrame> {
        if (bytes.isEmpty()) return emptyList()
        pending += bytes
        val frames = mutableListOf<SennheiserGaiaFrame>()
        while (true) {
            val start = findFrameStart()
            if (start < 0) {
                pending = trimTrailingStartByte()
                break
            }
            if (start > 0) pending = pending.copyOfRange(start, pending.size)
            if (pending.size < SennheiserGaiaWireCodec.HEADER_SIZE) break

            val length = readUnsignedShortBigEndian(2)
            if (length > SennheiserGaiaWireCodec.MAX_PAYLOAD_SIZE) {
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            val frameSize = SennheiserGaiaWireCodec.HEADER_SIZE + length
            if (pending.size < frameSize) break

            frames += SennheiserGaiaFrame(
                vendorId = readUnsignedShortBigEndian(4),
                commandId = readUnsignedShortBigEndian(6),
                payload = pending.copyOfRange(SennheiserGaiaWireCodec.HEADER_SIZE, frameSize),
            )
            pending = pending.copyOfRange(frameSize, pending.size)
        }
        return frames
    }

    fun reset() {
        pending = ByteArray(0)
    }

    /**
     * Keeps a trailing start byte, because it may be the first half of a marker split across two
     * reads; everything else in a buffer without a marker is noise.
     */
    private fun trimTrailingStartByte(): ByteArray {
        val last = pending.lastOrNull() ?: return ByteArray(0)
        return if (last == SennheiserGaiaWireCodec.FRAME_START.toByte()) {
            pending.copyOfRange(pending.size - 1, pending.size)
        } else {
            ByteArray(0)
        }
    }

    private fun findFrameStart(): Int {
        var index = 0
        while (index + 1 < pending.size) {
            if (
                pending[index] == SennheiserGaiaWireCodec.FRAME_START.toByte() &&
                pending[index + 1] == SennheiserGaiaWireCodec.FRAME_FLAGS.toByte()
            ) {
                return index
            }
            index++
        }
        return -1
    }

    private fun readUnsignedShortBigEndian(offset: Int): Int =
        ((pending[offset].toInt() and BYTE_MASK) shl 8) or (pending[offset + 1].toInt() and BYTE_MASK)

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}
