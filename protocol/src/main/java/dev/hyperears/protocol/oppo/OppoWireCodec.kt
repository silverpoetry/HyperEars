package dev.hyperears.protocol.oppo

/**
 * OPPO Enco command framing carried over its private Bluetooth Classic RFCOMM service.
 *
 * This codec deliberately keeps model semantics out of the wire layer. In particular,
 * `OPPO Enco Air2 Pro` reverses two ANC values used by the rest of the family; the selected
 * integration Adapter owns that interpretation.
 */
object OppoWireCodec {
    // OppoPods Leaf 929d813f ProductIdParser: status followed by three little-endian ID bytes.
    const val QUERY_PRODUCT_ID = 0x0103
    const val PRODUCT_ID_RESPONSE = 0x8103
    const val QUERY_BATTERY = 0x0106
    const val BATTERY_RESPONSE = 0x8106
    const val QUERY_ANC = 0x010C
    const val ANC_RESPONSE = 0x810C
    const val SET_ANC = 0x0404
    const val ACTIVE_REPORT = 0x0204
    const val QUERY_NOTIFICATION_SUPPORT = 0x0200
    const val NOTIFICATION_SUPPORT_RESPONSE = 0x8200
    const val REGISTER_NOTIFICATIONS = 0x0205

    data class Frame(
        val command: Int,
        val sequence: Int,
        val payload: ByteArray,
        val bytes: ByteArray,
    )

    data class BatteryReading(
        val percent: Int,
        val charging: Boolean,
    )

    data class BatteryState(
        val left: BatteryReading?,
        val right: BatteryReading?,
        val case: BatteryReading?,
    )

    data class AncState(
        val primary: Int,
        val secondary: Int?,
    )

    val queryProductId: ByteArray = packet(QUERY_PRODUCT_ID)
    val queryBattery: ByteArray = packet(QUERY_BATTERY)
    val queryNotificationSupport: ByteArray = packet(QUERY_NOTIFICATION_SUPPORT)
    val queryAnc: ByteArray = packet(
        command = QUERY_ANC,
        payload = byteArrayOf(0x01, 0x01),
    )

    fun setAnc(primary: Int, secondary: Int? = null): ByteArray {
        require(primary in 0..0xFF)
        require(secondary == null || secondary in 0..0xFF)
        val payload = buildList {
            add(0x01.toByte())
            add(0x01.toByte())
            add(primary.toByte())
            secondary?.let { add(it.toByte()) }
        }.toByteArray()
        return packet(SET_ANC, payload = payload)
    }

    fun registerNotifications(ids: ByteArray): ByteArray {
        require(ids.size <= MAX_NOTIFICATION_COUNT)
        return packet(
            command = REGISTER_NOTIFICATIONS,
            payload = byteArrayOf(ids.size.toByte()) + ids,
        )
    }

    fun parseProductId(frame: Frame): Int? {
        if (frame.command != PRODUCT_ID_RESPONSE || frame.payload.size != 4 ||
            frame.payload[0].unsigned() != STATUS_SUCCESS
        ) return null
        return frame.payload[1].unsigned() or (frame.payload[2].unsigned() shl 8) or
            (frame.payload[3].unsigned() shl 16)
    }

    fun parseNotificationSupport(frame: Frame): ByteArray? {
        if (frame.command != NOTIFICATION_SUPPORT_RESPONSE || frame.payload.size < 2) {
            return null
        }
        if (frame.payload[0].unsigned() != STATUS_SUCCESS) return null
        val count = frame.payload[1].unsigned()
        if (frame.payload.size < 2 + count) return null
        return frame.payload.copyOfRange(2, 2 + count)
    }

    fun parseBatteryState(frame: Frame): BatteryState? {
        val pairs = when (frame.command) {
            BATTERY_RESPONSE -> frame.payload
            ACTIVE_REPORT -> {
                if (frame.payload.size < 2 || frame.payload[0].unsigned() != REPORT_BATTERY) {
                    return null
                }
                val count = frame.payload[1].unsigned()
                val availablePairs = (frame.payload.size - 2) / 2
                if (availablePairs < count) return null
                frame.payload.copyOfRange(2, 2 + count * 2)
            }

            else -> return null
        }
        if (pairs.size < 2) return null

        var left: BatteryReading? = null
        var right: BatteryReading? = null
        var case: BatteryReading? = null
        for (offset in 0 until pairs.size - 1 step 2) {
            val raw = pairs[offset + 1].unsigned()
            val reading = BatteryReading(
                percent = raw and BATTERY_PERCENT_MASK,
                charging = raw and BATTERY_CHARGING_MASK != 0,
            )
            if (reading.percent !in 0..100) continue
            when (pairs[offset].unsigned()) {
                COMPONENT_LEFT -> left = reading
                COMPONENT_RIGHT -> right = reading
                COMPONENT_CASE -> case = reading
            }
        }
        return BatteryState(left = left, right = right, case = case)
            .takeIf { it.left != null || it.right != null || it.case != null }
    }

    fun parseAncState(frame: Frame): AncState? {
        if (frame.command != ANC_RESPONSE && frame.command != ACTIVE_REPORT) return null
        if (
            frame.command == ACTIVE_REPORT &&
            frame.payload.firstOrNull()?.unsigned() in setOf(REPORT_BATTERY, REPORT_WEAR)
        ) {
            return null
        }
        val payload = frame.payload
        for (offset in 0 until payload.size - 2) {
            if (payload[offset].unsigned() != 0x01 ||
                payload[offset + 1].unsigned() != 0x01
            ) {
                continue
            }
            return AncState(
                primary = payload[offset + 2].unsigned(),
                secondary = payload.getOrNull(offset + 3)?.unsigned(),
            )
        }
        return null
    }

    fun packet(
        command: Int,
        sequence: Int = DEFAULT_SEQUENCE,
        payload: ByteArray = byteArrayOf(),
    ): ByteArray {
        require(command in 0..0xFFFF)
        require(sequence in 0..0xFF)
        require(payload.size <= MAX_PAYLOAD_SIZE)
        val bodyLength = FIXED_BODY_SIZE + payload.size
        return ByteArray(2 + bodyLength).apply {
            this[0] = HEADER.toByte()
            this[1] = bodyLength.toByte()
            this[2] = 0
            this[3] = 0
            writeShortLe(command, 4)
            this[6] = sequence.toByte()
            writeShortLe(payload.size, 7)
            payload.copyInto(this, destinationOffset = PAYLOAD_OFFSET)
        }
    }

    class Decoder(initialCapacity: Int = 256) {
        private var bytes = ByteArray(initialCapacity.coerceAtLeast(16))
        private var size = 0

        fun offer(chunk: ByteArray): List<Frame> {
            if (chunk.isEmpty()) return emptyList()
            append(chunk)
            val frames = mutableListOf<Frame>()
            while (true) {
                discardNoise()
                if (size < 2) return frames

                val bodyLength = peek(1)
                if (bodyLength < FIXED_BODY_SIZE) {
                    discard(1)
                    continue
                }
                val frameLength = bodyLength + 2
                if (size < frameLength) return frames

                val candidate = take(frameLength)
                val payloadLength = candidate.readShortLe(7)
                if (payloadLength != bodyLength - FIXED_BODY_SIZE) continue
                frames += Frame(
                    command = candidate.readShortLe(4),
                    sequence = candidate[6].unsigned(),
                    payload = candidate.copyOfRange(
                        PAYLOAD_OFFSET,
                        PAYLOAD_OFFSET + payloadLength,
                    ),
                    bytes = candidate,
                )
            }
        }

        fun reset() {
            size = 0
        }

        private fun append(chunk: ByteArray) {
            ensureCapacity(size + chunk.size)
            chunk.copyInto(bytes, destinationOffset = size)
            size += chunk.size
        }

        private fun discardNoise() {
            var count = 0
            while (count < size && peek(count) != HEADER) count++
            if (count > 0) discard(count)
        }

        private fun peek(index: Int): Int = bytes[index].unsigned()

        private fun take(count: Int): ByteArray =
            bytes.copyOfRange(0, count).also { discard(count) }

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

    fun ByteArray.hex(): String =
        joinToString(" ") { "%02X".format(it.unsigned()) }

    private fun ByteArray.writeShortLe(value: Int, offset: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.readShortLe(offset: Int): Int =
        this[offset].unsigned() or (this[offset + 1].unsigned() shl 8)

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private const val HEADER = 0xAA
    private const val DEFAULT_SEQUENCE = 0xF0
    private const val FIXED_BODY_SIZE = 7
    private const val PAYLOAD_OFFSET = 9
    private const val MAX_PAYLOAD_SIZE = 248
    private const val MAX_NOTIFICATION_COUNT = MAX_PAYLOAD_SIZE - 1
    private const val STATUS_SUCCESS = 0x00

    private const val REPORT_BATTERY = 0x01
    private const val REPORT_WEAR = 0x02
    private const val COMPONENT_LEFT = 0x01
    private const val COMPONENT_RIGHT = 0x02
    private const val COMPONENT_CASE = 0x03
    private const val BATTERY_PERCENT_MASK = 0x7F
    private const val BATTERY_CHARGING_MASK = 0x80
}
