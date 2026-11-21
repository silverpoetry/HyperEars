package dev.hyperears.protocol.samsung

/** Samsung Galaxy Buds message framing used by Buds2 Pro over its RFCOMM service. */
object SamsungBudsCodec {
    enum class Model {
        BUDS2_PRO, BUDS2, BUDS_FE, BUDS_LIVE, BUDS_PRO,
        BUDS3, BUDS3_PRO, BUDS_CORE, BUDS3_FE, BUDS4, BUDS4_PRO, UNKNOWN;

        val usesStandardSpp: Boolean get() = this == BUDS_LIVE || this == BUDS_PRO
        val ambient: Boolean get() = this !in setOf(BUDS_LIVE, BUDS3, BUDS4)
    }
    const val UNIVERSAL_ACK = 0x42
    const val STATUS_UPDATED = 0x60
    const val EXTENDED_STATUS_UPDATED = 0x61
    const val NOISE_CONTROLS_UPDATE = 0x77
    const val NOISE_CONTROLS = 0x78
    const val MANAGER_INFO = 0x88
    // GalaxyBudsClient Buds Live uses a separate boolean ANC command/report.
    const val SET_NOISE_REDUCTION = 0x98
    const val NOISE_REDUCTION_MODE_UPDATE = 0x9B

    enum class NoiseMode(val wire: Int) { OFF(0), ANC(1), AMBIENT(2) }

    data class Frame(val id: Int, val payload: ByteArray)

    data class BatteryState(
        val left: Int?,
        val right: Int?,
        val case: Int?,
        val leftCharging: Boolean = false,
        val rightCharging: Boolean = false,
        val caseCharging: Boolean = false,
    )

    fun packet(id: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(id in 0..0xFF)
        require(payload.size <= MAX_PAYLOAD_SIZE)
        val body = byteArrayOf(id.toByte()) + payload
        val size = body.size + 2
        val frame = ByteArray(size + 4)
        frame[0] = SOM
        frame[1] = size.toByte()
        frame[2] = (size shr 8).toByte()
        body.copyInto(frame, 3)
        val crc = crc16Xmodem(body)
        frame[frame.lastIndex - 2] = crc.toByte()
        frame[frame.lastIndex - 1] = (crc shr 8).toByte()
        frame[frame.lastIndex] = EOM
        return frame
    }

    fun noiseModeCommand(mode: NoiseMode, model: Model = Model.BUDS2_PRO): ByteArray {
        require(model.ambient || mode != NoiseMode.AMBIENT)
        return if (model == Model.BUDS_LIVE) packet(SET_NOISE_REDUCTION, byteArrayOf(mode.wire.toByte()))
        else packet(NOISE_CONTROLS, byteArrayOf(mode.wire.toByte()))
    }

    fun managerInfoCommand(androidSdk: Int = 35): ByteArray =
        packet(MANAGER_INFO, byteArrayOf(1, 2, androidSdk.coerceIn(0, 255).toByte()))

    fun parseFrame(bytes: ByteArray): Frame? {
        if (bytes.size < MIN_FRAME_SIZE || bytes[0] != SOM || bytes.last() != EOM) return null
        val size = (bytes[1].u() or (bytes[2].u() shl 8)) and 0x3FF
        if (bytes.size != size + 4 || size < 3) return null
        val body = bytes.copyOfRange(3, bytes.size - 3)
        val expected = bytes[bytes.lastIndex - 2].u() or (bytes[bytes.lastIndex - 1].u() shl 8)
        if (crc16Xmodem(body) != expected) return null
        return Frame(body[0].u(), body.copyOfRange(1, body.size))
    }

    fun parseBattery(frame: Frame, model: Model = Model.BUDS2_PRO): BatteryState? = when (frame.id) {
        STATUS_UPDATED -> frame.payload.takeIf { it.size >= 7 }?.let { p ->
            val charging = if (model.usesStandardSpp) 0 else p.getOrNull(7)?.u() ?: 0
            BatteryState(
                p[1].u().percent(), p[2].u().percent(), p[6].u().percent(),
                charging and 0x10 != 0, charging and 0x04 != 0, charging and 0x01 != 0,
            )
        }
        EXTENDED_STATUS_UPDATED -> frame.payload.takeIf { it.size >= 8 }?.let { p ->
            val chargingIndex = when (model) {
                Model.BUDS2 -> 36
                Model.BUDS2_PRO, Model.BUDS_FE -> 43
                else -> -1
            }
            val charging = p.getOrNull(chargingIndex)?.u() ?: 0
            BatteryState(p[2].u().percent(), p[3].u().percent(), p[7].u().percent(),
                charging and 0x10 != 0, charging and 0x04 != 0, charging and 0x01 != 0)
        }
        else -> null
    }

    fun parseNoiseMode(frame: Frame, model: Model = Model.BUDS2_PRO): NoiseMode? {
        val legacyAnc = model == Model.BUDS_LIVE
        val updateId = if (legacyAnc) NOISE_REDUCTION_MODE_UPDATE else NOISE_CONTROLS_UPDATE
        val commandId = if (legacyAnc) SET_NOISE_REDUCTION else NOISE_CONTROLS
        val value = when (frame.id) {
            EXTENDED_STATUS_UPDATED -> frame.payload.getOrNull(12)
            updateId -> frame.payload.firstOrNull()
            UNIVERSAL_ACK -> frame.payload.takeIf { it.size == 2 && it[0].u() == commandId }?.get(1)
            else -> null
        }
        return value?.u()?.toNoiseMode()?.takeIf { model.ambient || it != NoiseMode.AMBIENT }
    }

    fun crc16Xmodem(data: ByteArray): Int {
        var crc = 0
        data.forEach { value ->
            crc = crc xor (value.u() shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    class Decoder {
        private var pending = ByteArray(0)
        fun offer(bytes: ByteArray): List<ByteArray> {
            pending += bytes
            val result = mutableListOf<ByteArray>()
            while (pending.size >= MIN_FRAME_SIZE) {
                val start = pending.indexOfFirst { it == SOM }
                if (start < 0) { pending = ByteArray(0); break }
                if (start > 0) pending = pending.copyOfRange(start, pending.size)
                if (pending.size < 3) break
                val size = (pending[1].u() or (pending[2].u() shl 8)) and 0x3FF
                val total = size + 4
                if (size < 3 || total > MAX_FRAME_SIZE) { pending = pending.copyOfRange(1, pending.size); continue }
                if (pending.size < total) break
                val candidate = pending.copyOfRange(0, total)
                if (parseFrame(candidate) != null) {
                    result += candidate
                    pending = pending.copyOfRange(total, pending.size)
                } else {
                    // A corrupt length can overlap the next packet. Resynchronize
                    // from the next byte instead of discarding that packet's start.
                    pending = pending.copyOfRange(1, pending.size)
                }
            }
            return result
        }
        fun reset() { pending = ByteArray(0) }
    }

    private fun Byte.u(): Int = toInt() and 0xFF
    private fun Int.percent(): Int? = takeIf { it in 0..100 }
    private fun Int.toNoiseMode(): NoiseMode? = NoiseMode.entries.firstOrNull { it.wire == this }

    private const val SOM: Byte = 0xFD.toByte()
    private const val EOM: Byte = 0xDD.toByte()
    private const val MIN_FRAME_SIZE = 7
    private const val MAX_PAYLOAD_SIZE = 1020
    private const val MAX_FRAME_SIZE = 1027
}
