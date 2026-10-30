package dev.hyperears.protocol.flos2

/**
 * Binary GATT protocol spoken by the Dreame Flo S2 and TheAstro OpenPro S2 sport earbuds on the
 * `AC00` service (write `AC01`, notify `AC02`).
 *
 * Frame layout:
 *
 * ```text
 * AE | primary | sub | payloadLength | payload[payloadLength] | CRC16 LE
 * ```
 *
 * The CRC is CRC-16/CCITT-FALSE (poly `0x1021`, init `0xFFFF`, no reflection) over every byte
 * from the marker through the end of the payload, transmitted little-endian. Both directions use
 * the same framing; responses carry `sub or 0x80`.
 *
 * Implemented subset: the periodic full-status response (`0x66/0xFF`), the battery push event
 * (`0x66/0xE0`) and the sound-effect command (`0x67/0x03`). Sport-recording and file-transfer
 * families are out of scope for headset integration.
 */
object FloS2WireCodec {
    const val MARKER: Int = 0xAE

    /** Status, configuration and push-event family. */
    const val PRIMARY_STATUS: Int = 0x66

    /** Sport control family; also carries audio-mode commands such as the sound effect. */
    const val PRIMARY_SPORT: Int = 0x67

    /** Full status snapshot, sent by the earbud after every [queryAllStatus] request. */
    const val SUB_QUERY_ALL_STATUS: Int = 0xFF

    /** Unsolicited battery push, handler-named `_onProtocolFrameBatteryReport` in the vendor app. */
    const val SUB_BATTERY_REPORT: Int = 0xE0

    /** Charging-state wire value emitted while the earbud is on the charger. */
    const val CHARGING_STATE_CHARGING: Int = 1

    /** Sound-effect switch (vendor app symbol `setEqMode`). */
    const val SUB_SET_SOUND_EFFECT: Int = 0x03

    /** Response subtype of [SUB_SET_SOUND_EFFECT]: `sub or 0x80`. */
    const val SUB_ACK_SET_SOUND_EFFECT: Int = 0x83

    /** Exact payload byte count of a full-status response (`0x48` on the wire). */
    const val STATUS_PAYLOAD_LENGTH: Int = 72

    private const val HEADER_LENGTH = 4
    private const val CRC_LENGTH = 2
    private const val MAX_PAYLOAD_LENGTH = 0xFF

    /** Four selectable EQ presets; the wire value equals the vendor app's UI index. */
    enum class SoundEffect(val value: Int) {
        STANDARD(0),
        SWIMMING(1),
        VOCALS(2),
        ENHANCED(3),
        ;

        companion object {
            fun fromValue(value: Int): SoundEffect? = entries.firstOrNull { it.value == value }
        }
    }

    data class Frame(
        val primaryType: Int,
        val subType: Int,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Frame &&
                primaryType == other.primaryType &&
                subType == other.subType &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int = primaryType * 31 * 31 + subType * 31 + payload.contentHashCode()
    }

    /** Fields decoded from the 72-byte full-status payload. Offsets cite the evidence capture. */
    data class DeviceStatus(
        val batteryPercent: Int?,
        val soundEffect: SoundEffect?,
        val firmwareVersion: String?,
        val hardwareVersion: String?,
        val mcuFirmwareVersion: String?,
        val deviceName: String?,
        val serial: String?,
    )

    /** Fields decoded from the unsolicited battery push (`0x66/0xE0`). */
    data class BatteryReport(
        val batteryPercent: Int,
        /**
         * Raw charging-state byte: 0 = discharging, 1 = charging; the vendor parser also accepts
         * 2, which real captures never emitted. `null` when the byte is absent or outside the
         * vendor enum.
         */
        val chargingState: Int?,
    )

    /** Recomputes the wire CRC over `bytes[from, to)`. */
    fun crc16(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    fun frame(primaryType: Int, subType: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_PAYLOAD_LENGTH) { "FloS2 payload exceeds one byte length" }
        val crc = crc16Frame(primaryType, subType, payload)
        val out = ByteArray(HEADER_LENGTH + payload.size + CRC_LENGTH)
        out[0] = MARKER.toByte()
        out[1] = primaryType.toByte()
        out[2] = subType.toByte()
        out[3] = payload.size.toByte()
        payload.copyInto(out, HEADER_LENGTH)
        out[out.size - 2] = (crc and 0xFF).toByte()
        out[out.size - 1] = ((crc ushr 8) and 0xFF).toByte()
        return out
    }

    private fun crc16Frame(primaryType: Int, subType: Int, payload: ByteArray): Int {
        val head = byteArrayOf(MARKER.toByte(), primaryType.toByte(), subType.toByte(), payload.size.toByte())
        return crc16(head + payload)
    }

    /** Empty-payload status query; wire bytes `AE 66 FF 00 D8 E6`. */
    val queryAllStatus: ByteArray = frame(PRIMARY_STATUS, SUB_QUERY_ALL_STATUS)

    fun setSoundEffect(effect: SoundEffect): ByteArray =
        frame(PRIMARY_SPORT, SUB_SET_SOUND_EFFECT, byteArrayOf(effect.value.toByte()))

    fun isSoundEffectAck(frame: Frame): Boolean =
        frame.primaryType == PRIMARY_SPORT && frame.subType == SUB_ACK_SET_SOUND_EFFECT

    /**
     * Parses the full-status response.
     *
     * Verified payload map (0-based): 0-1 RTC seconds, 6-10 firmware, 11-15 hardware,
     * 16-20 MCU firmware, 21-24 reserved `FF`, 25-26 battery (u16 LE), 27-30 storage,
     * 36-41 device name, 42-47 reversed MAC, 48 pool length, 49 sound effect, 50 broadcast
     * mode, 51-54 work/play mode and flags, 55-70 serial, 71 sport state.
     */
    fun parseStatus(frame: Frame): DeviceStatus? {
        if (frame.primaryType != PRIMARY_STATUS || frame.subType != SUB_QUERY_ALL_STATUS) return null
        val payload = frame.payload
        if (payload.size != STATUS_PAYLOAD_LENGTH) return null
        return DeviceStatus(
            batteryPercent = payload.uInt16Le(25)?.takeIf { it in 0..100 },
            soundEffect = payload[49].unsigned().let(SoundEffect::fromValue),
            firmwareVersion = payload.ascii(6, 11),
            hardwareVersion = payload.ascii(11, 16),
            mcuFirmwareVersion = payload.ascii(16, 21),
            deviceName = payload.ascii(36, 42),
            serial = payload.ascii(55, 71),
        )
    }

    /**
     * Parses the battery push event: payload is `[battery% u8][charging u8]`.
     *
     * Mirrors the vendor handler: the percentage byte is clamped to 100, and the charging byte
     * decodes only when the payload carries it and holds one of the enum values {0, 1, 2}.
     * Charging evidence on the
     * wire: plugging the charger in makes the earbud emit one final push with charging = 1 and
     * power off ~290 ms later, so that frame is the only charging signal while cabled; charging
     * inside the case keeps the link alive with the byte stuck at 0, where only the rising
     * percentage hints at charging.
     */
    fun parseBatteryReport(frame: Frame): BatteryReport? {
        if (frame.primaryType != PRIMARY_STATUS || frame.subType != SUB_BATTERY_REPORT) return null
        val payload = frame.payload
        if (payload.isEmpty()) return null
        return BatteryReport(
            batteryPercent = payload[0].unsigned().coerceAtMost(100),
            chargingState = payload.getOrNull(1)?.unsigned()?.takeIf { it in 0..2 },
        )
    }

    class Decoder {
        private var pending = ByteArray(0)

        /** Feeds notify bytes and returns every complete, CRC-valid frame contained within. */
        fun offer(bytes: ByteArray): List<Frame> {
            if (bytes.isEmpty()) return emptyList()
            pending += bytes
            val result = mutableListOf<Frame>()
            while (true) {
                val marker = pending.indexOf(MARKER.toByte())
                if (marker < 0) {
                    pending = ByteArray(0)
                    break
                }
                if (marker > 0) pending = pending.copyOfRange(marker, pending.size)
                if (pending.size < HEADER_LENGTH) break
                val payloadLength = pending[3].unsigned()
                val frameSize = HEADER_LENGTH + payloadLength + CRC_LENGTH
                if (pending.size < frameSize) break
                val end = frameSize - CRC_LENGTH
                if (crc16(pending, from = 0, to = end) ==
                    pending[end].unsigned() or (pending[end + 1].unsigned() shl 8)
                ) {
                    result += Frame(
                        primaryType = pending[1].unsigned(),
                        subType = pending[2].unsigned(),
                        payload = pending.copyOfRange(HEADER_LENGTH, end),
                    )
                    pending = pending.copyOfRange(frameSize, pending.size)
                } else {
                    pending = pending.copyOfRange(1, pending.size)
                }
            }
            return result
        }

        fun reset() {
            pending = ByteArray(0)
        }
    }

    private fun ByteArray.uInt16Le(offset: Int): Int? {
        if (size < offset + 2) return null
        return this[offset].unsigned() or (this[offset + 1].unsigned() shl 8)
    }

    private fun ByteArray.ascii(from: Int, to: Int): String? {
        if (size < to) return null
        val text = copyOfRange(from, to).toString(Charsets.US_ASCII).trim('\u0000', ' ')
        return text.takeIf { it.isNotEmpty() }
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF
}
