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
        /** Original physical frame(s), concatenated for a reassembled message. */
        val bytes: ByteArray,
        /** A physical frame used an unambiguous, canonical two-byte LEB128 length. */
        val canonicalLongLength: Boolean = false,
        val fragmented: Boolean = false,
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

    // Leaf 929d813f OPOv1 framer bound; QuickBuds 6e069325 confirms canonical LEB128.
    const val MAX_FRAME_SIZE = 12388
    private const val MAX_INNER_SIZE = 65535 + 5
    val queryFrameBudget: ByteArray = packet(0x0107)

    fun parseFrameBudget(frame: Frame): Int? {
        if (frame.command != 0x8107 || frame.payload.size != 3 || frame.payload[0] != 0.toByte()) return null
        return frame.payload.readShortLe(1).takeIf { it in 10..MAX_FRAME_SIZE }
    }

    /** Explicit OPOv1 encoding. No checksum; body length excludes AA and length bytes. */
    fun canonicalPackets(command: Int, sequence: Int = DEFAULT_SEQUENCE,
        payload: ByteArray = byteArrayOf(), frameBudget: Int): List<ByteArray> {
        require(command in 0..0xFFFF && sequence in 0..0xFF && payload.size <= 65535)
        require(frameBudget in 10..MAX_FRAME_SIZE)
        val inner = ByteArray(5 + payload.size).apply {
            writeShortLe(command, 0); this[2] = sequence.toByte()
            writeShortLe(payload.size, 3); payload.copyInto(this, 5)
        }
        fun size(data: Int, sliced: Boolean): Int {
            val body = data + if (sliced) 3 else 2
            return 1 + (if (body < 128) 1 else 2) + body
        }
        fun frame(control: Int, fsn: Int, data: ByteArray): ByteArray {
            val body = data.size + if (control == 0) 2 else 3
            val length = if (body < 128) byteArrayOf(body.toByte()) else
                byteArrayOf(((body and 0x7F) or 0x80).toByte(), (body ushr 7).toByte())
            return byteArrayOf(HEADER.toByte()) + length + byteArrayOf(control.toByte(), 0) +
                (if (control == 0) byteArrayOf() else byteArrayOf(fsn.toByte())) + data
        }
        if (size(inner.size, false) <= frameBudget) return listOf(frame(0, 0, inner))
        val perSlice = (frameBudget downTo 1).first { size(it, true) <= frameBudget }
        val count = (inner.size + perSlice - 1) / perSlice
        require(count <= 256) { "Fragment sequence would wrap" }
        return List(count) { index ->
            frame(if (index == 0) 1 else if (index == count - 1) 3 else 2, index,
                inner.copyOfRange(index * perSlice, minOf(inner.size, (index + 1) * perSlice)))
        }
    }

    /** Bounded RFCOMM stream parser, including OPOv1 fragments and legacy raw-u8 lengths. */
    class Decoder(@Suppress("UNUSED_PARAMETER") initialCapacity: Int = 256) {
        private val bytes = ByteArray(MAX_FRAME_SIZE)
        private var size = 0
        private val assembly = java.io.ByteArrayOutputStream()
        private val physical = java.io.ByteArrayOutputStream()
        private var nextFsn = -1
        private var longEvidence = false

        fun offer(chunk: ByteArray): List<Frame> = buildList {
            // Incremental append prevents a hostile input chunk from growing the pending buffer.
            for (byte in chunk) {
                if (this@Decoder.size == bytes.size) { discard(1); clearAssembly() }
                bytes[this@Decoder.size++] = byte
                drain(this)
            }
        }

        private fun drain(frames: MutableList<Frame>) {
            while (size > 0) {
                if (u(0) != HEADER) { discard(1); clearAssembly(); continue }
                if (size < 2) return
                var width = 1
                var body = u(1)
                var canonical = false
                if (body >= 128) {
                    if (size < 3) return
                    // Legacy raw-u8 has control=00 here. A canonical second length byte is 01..7F.
                    if (u(2) != 0) {
                        if (u(2) >= 128) { discard(1); clearAssembly(); continue }
                        body = (body and 0x7F) or (u(2) shl 7)
                        width = 2; canonical = true
                    }
                }
                val start = 1 + width
                val total = start + body
                if (body < 3 || total > MAX_FRAME_SIZE) { discard(1); clearAssembly(); continue }
                if (size < start + 2) return
                val control = u(start)
                // Raw-u8 long frames only have the original whole-message grammar.
                if (u(start + 1) != 0 || control !in 0..3 ||
                    (width == 1 && body >= 128 && control != 0) || (control == 0 && body < 7)) {
                    discard(1); clearAssembly(); continue
                }
                // Check the redundant inner length early, before waiting for a false outer length.
                if (control == 0 && size >= start + 7 &&
                    bytes.readShortLe(start + 5) != body - 7) {
                    discard(1); clearAssembly(); continue
                }
                if (size < total) return
                val raw = bytes.copyOfRange(0, total)
                discard(total)
                if (control == 0) {
                    clearAssembly()
                    decodeInner(raw.copyOfRange(start + 2, total), raw, canonical, false)?.let(frames::add)
                    continue
                }
                if (body <= 3) { clearAssembly(); continue }
                val fsn = raw[start + 2].unsigned()
                if (control == 1) {
                    clearAssembly()
                    if (fsn != 0) continue
                    nextFsn = 0
                }
                if (nextFsn < 0 || fsn != nextFsn || (control != 1 && fsn == 0)) {
                    clearAssembly(); continue
                }
                val data = raw.copyOfRange(start + 3, total)
                if (assembly.size() + data.size > MAX_INNER_SIZE) { clearAssembly(); continue }
                assembly.write(data); physical.write(raw)
                longEvidence = longEvidence || canonical
                nextFsn++
                val inner = assembly.toByteArray()
                if (inner.size >= 5) {
                    val expected = 5 + inner.readShortLe(3)
                    if (inner.size > expected || (control != 3 && inner.size == expected)) {
                        clearAssembly(); continue
                    }
                }
                if (control == 3) {
                    decodeInner(inner, physical.toByteArray(), longEvidence, true)?.let(frames::add)
                    clearAssembly()
                } else if (nextFsn > 255) clearAssembly()
            }
        }

        private fun decodeInner(inner: ByteArray, raw: ByteArray, canonical: Boolean, sliced: Boolean): Frame? {
            if (inner.size < 5 || inner.readShortLe(3) != inner.size - 5) return null
            return Frame(inner.readShortLe(0), inner[2].unsigned(), inner.copyOfRange(5, inner.size),
                raw, canonical, sliced)
        }
        fun reset() { size = 0; clearAssembly() }
        private fun clearAssembly() { assembly.reset(); physical.reset(); nextFsn = -1; longEvidence = false }
        private fun u(index: Int) = bytes[index].unsigned()
        private fun discard(count: Int) {
            bytes.copyInto(bytes, 0, count, size)
            size -= count
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
