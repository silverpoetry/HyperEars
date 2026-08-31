package dev.hyperears.protocol.technics

/**
 * Airoha RACE framing and the Technics commands used by HyperEars.
 *
 * RACE is carried directly over RFCOMM. Its little-endian length includes the two-byte RACE id,
 * but excludes the channel, type, and length fields.
 */
object TechnicsRaceWireCodec {
    const val RFCOMM_SERVICE = "00000000-0000-0000-0099-aabbccddeeff"
    const val RFCOMM_FALLBACK_SERVICE = "00001101-0000-1000-8000-00805f9b34fb"

    enum class MessageType(val value: Int) {
        COMMAND_NEED_RESPONSE(0x5A),
        RESPONSE(0x5B),
        COMMAND_NO_RESPONSE(0x5C),
        INDICATION(0x5D),
        ;

        companion object {
            fun fromValue(value: Int): MessageType? = entries.firstOrNull { it.value == value }
        }
    }

    enum class BatteryComponent {
        LEFT,
        RIGHT,
        CASE,
    }

    enum class OutsideControlMode(val value: Int) {
        OFF(0x00),
        ANC(0x01),
        TRANSPARENCY(0x02),
        ;

        companion object {
            fun fromValue(value: Int): OutsideControlMode? =
                entries.firstOrNull { it.value == value }
        }
    }

    enum class NoiseMode {
        OFF,
        ANC,
        TRANSPARENCY,
    }

    data class Frame(
        val type: MessageType,
        val raceId: Int,
        val payload: ByteArray,
        val bytes: ByteArray,
    )

    data class BatteryReading(
        val component: BatteryComponent,
        val percent: Int,
    )

    data class OutsideControlState(
        val mode: OutsideControlMode,
        val noiseCancelLevel: Int,
        val ambientLevel: Int,
    )

    val queryAgentBattery: ByteArray =
        encode(RACE_ID_TWS_GET_BATTERY, byteArrayOf(COMPONENT_AGENT.toByte()))

    val queryClientBattery: ByteArray =
        encode(RACE_ID_TWS_GET_BATTERY, byteArrayOf(COMPONENT_CLIENT.toByte()))

    val queryCaseBattery: ByteArray = encode(RACE_ID_GET_CRADLE_BATTERY)
    val queryOutsideControl: ByteArray = encode(RACE_ID_GET_OUTSIDE_CTRL)

    fun encode(
        raceId: Int,
        payload: ByteArray = byteArrayOf(),
        type: MessageType = MessageType.COMMAND_NEED_RESPONSE,
    ): ByteArray {
        require(raceId in 0..0xFFFF)
        val length = RACE_ID_SIZE + payload.size
        require(FRAME_PREFIX_SIZE + length <= MAX_FRAME_SIZE)
        return byteArrayOf(
            RACE_CHANNEL.toByte(),
            type.value.toByte(),
            length.toByte(),
            (length ushr 8).toByte(),
            raceId.toByte(),
            (raceId ushr 8).toByte(),
        ) + payload
    }

    fun setNoiseMode(
        mode: NoiseMode,
        noiseCancelLevel: Int = DEFAULT_NOISE_CANCEL_LEVEL,
        ambientLevel: Int = DEFAULT_AMBIENT_LEVEL,
    ): List<ByteArray> {
        requireLevel(noiseCancelLevel)
        requireLevel(ambientLevel)
        return when (mode) {
            NoiseMode.OFF -> listOf(
                disableAdaptiveAnc(),
                setOutsideControl(OutsideControlMode.OFF, noiseCancelLevel, ambientLevel),
            )
            NoiseMode.ANC -> listOf(
                disableAdaptiveAnc(),
                setOutsideControl(OutsideControlMode.ANC, noiseCancelLevel, ambientLevel),
                SET_NOISE_CANCELING_ADJUST_DEFAULT,
            )
            NoiseMode.TRANSPARENCY -> listOf(
                disableAdaptiveAnc(),
                SET_AMBIENT_MODE_TRANSPARENT,
                setOutsideControl(
                    OutsideControlMode.TRANSPARENCY,
                    noiseCancelLevel,
                    ambientLevel,
                ),
            )
        }
    }

    fun parseBattery(frame: Frame): BatteryReading? {
        if (frame.type != MessageType.RESPONSE && frame.type != MessageType.INDICATION) return null
        return when (frame.raceId) {
            RACE_ID_TWS_GET_BATTERY -> {
                if (!frame.payload.hasSuccessfulStatus(TWS_BATTERY_RESPONSE_SIZE)) return null
                val component = when (frame.payload[1].unsigned()) {
                    COMPONENT_AGENT -> BatteryComponent.RIGHT
                    COMPONENT_CLIENT -> BatteryComponent.LEFT
                    else -> return null
                }
                val percent = frame.payload[2].validPercent() ?: return null
                BatteryReading(component, percent)
            }
            RACE_ID_GET_CRADLE_BATTERY -> {
                if (!frame.payload.hasSuccessfulStatus(CRADLE_BATTERY_RESPONSE_SIZE)) return null
                val percent = frame.payload[1].validPercent() ?: return null
                BatteryReading(BatteryComponent.CASE, percent)
            }
            else -> null
        }
    }

    fun parseOutsideControl(frame: Frame): OutsideControlState? {
        if (frame.type != MessageType.RESPONSE || frame.raceId != RACE_ID_GET_OUTSIDE_CTRL) {
            return null
        }
        if (!frame.payload.hasSuccessfulStatus(OUTSIDE_CONTROL_RESPONSE_SIZE)) return null
        val mode = OutsideControlMode.fromValue(frame.payload[1].unsigned()) ?: return null
        val noiseCancelLevel = frame.payload[2].validPercent() ?: return null
        val ambientLevel = frame.payload[3].validPercent() ?: return null
        return OutsideControlState(mode, noiseCancelLevel, ambientLevel)
    }

    class Decoder {
        private var pending = ByteArray(0)

        fun offer(bytes: ByteArray): List<Frame> {
            if (bytes.isEmpty()) return emptyList()
            pending += bytes
            val frames = mutableListOf<Frame>()

            while (pending.size >= HEADER_SIZE) {
                val marker = pending.indexOf(RACE_CHANNEL.toByte())
                if (marker < 0) {
                    pending = ByteArray(0)
                    break
                }
                if (marker > 0) pending = pending.copyOfRange(marker, pending.size)
                if (pending.size < HEADER_SIZE) break

                val type = MessageType.fromValue(pending[TYPE_OFFSET].unsigned())
                if (type == null) {
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }

                val length = pending.readUnsignedShortLittleEndian(LENGTH_OFFSET)
                val frameSize = FRAME_PREFIX_SIZE + length
                if (length < RACE_ID_SIZE || frameSize > MAX_FRAME_SIZE) {
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }
                if (pending.size < frameSize) break

                val candidate = pending.copyOfRange(0, frameSize)
                pending = pending.copyOfRange(frameSize, pending.size)
                frames += Frame(
                    type = type,
                    raceId = candidate.readUnsignedShortLittleEndian(RACE_ID_OFFSET),
                    payload = candidate.copyOfRange(HEADER_SIZE, candidate.size),
                    bytes = candidate,
                )
            }
            return frames
        }

        fun reset() {
            pending = ByteArray(0)
        }
    }

    internal fun hex(value: String): ByteArray {
        val compact = value.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        require(compact.length % 2 == 0)
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun setOutsideControl(
        mode: OutsideControlMode,
        noiseCancelLevel: Int,
        ambientLevel: Int,
    ): ByteArray = encode(
        RACE_ID_SET_OUTSIDE_CTRL,
        byteArrayOf(mode.value.toByte(), noiseCancelLevel.toByte(), ambientLevel.toByte()),
    )

    private fun disableAdaptiveAnc(): ByteArray = encode(
        RACE_ID_SET_ADAPTIVE_ANC,
        byteArrayOf(ADAPTIVE_ANC_OFF.toByte()),
    )

    private fun requireLevel(level: Int) {
        require(level in 0..100)
    }

    private fun ByteArray.hasSuccessfulStatus(minimumSize: Int): Boolean =
        size >= minimumSize && first().unsigned() == STATUS_SUCCESS

    private fun Byte.validPercent(): Int? = unsigned().takeIf { it in 0..100 }

    private fun ByteArray.readUnsignedShortLittleEndian(offset: Int): Int =
        this[offset].unsigned() or (this[offset + 1].unsigned() shl 8)

    private fun ByteArray.indexOf(value: Byte): Int {
        for (index in indices) {
            if (this[index] == value) return index
        }
        return -1
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private const val TYPE_OFFSET = 1
    private const val LENGTH_OFFSET = 2
    private const val RACE_ID_OFFSET = 4
    private const val FRAME_PREFIX_SIZE = 4
    private const val RACE_ID_SIZE = 2
    private const val HEADER_SIZE = FRAME_PREFIX_SIZE + RACE_ID_SIZE
    private const val MAX_FRAME_SIZE = 512

    private const val RACE_ID_TWS_GET_BATTERY = 0x0CD6
    private const val RACE_ID_GET_CRADLE_BATTERY = 0x0040
    private const val RACE_ID_GET_OUTSIDE_CTRL = 0x000A
    private const val RACE_ID_SET_OUTSIDE_CTRL = 0x000B
    private const val RACE_ID_SET_AMBIENT_MODE = 0x0022
    private const val RACE_ID_SET_NOISE_CANCELING_ADJUST = 0x0039
    private const val RACE_ID_SET_ADAPTIVE_ANC = 0x0068

    private const val COMPONENT_AGENT = 0x00
    private const val COMPONENT_CLIENT = 0x01
    private const val STATUS_SUCCESS = 0x00
    private const val ADAPTIVE_ANC_OFF = 0x00
    private const val DEFAULT_NOISE_CANCEL_LEVEL = 100
    private const val DEFAULT_AMBIENT_LEVEL = 50
    private const val TWS_BATTERY_RESPONSE_SIZE = 3
    private const val CRADLE_BATTERY_RESPONSE_SIZE = 2
    private const val OUTSIDE_CONTROL_RESPONSE_SIZE = 4

    private const val RACE_CHANNEL = 0x05
    private val SET_AMBIENT_MODE_TRANSPARENT =
        encode(RACE_ID_SET_AMBIENT_MODE, byteArrayOf(0x00, 0x00))
    private val SET_NOISE_CANCELING_ADJUST_DEFAULT =
        encode(RACE_ID_SET_NOISE_CANCELING_ADJUST, byteArrayOf(0x20, 0x02))
}
