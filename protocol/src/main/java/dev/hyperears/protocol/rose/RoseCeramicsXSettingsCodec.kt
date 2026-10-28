package dev.hyperears.protocol.rose

/** ROSELINK F8 sender/receiver with CeramicsXReceiveHandler domains; no other F8 model inferred. */
object RoseCeramicsXSettingsCodec {
    val ranges: Map<Int, IntRange> = mapOf(4 to 0..2, 8 to 0..1, 10 to 0..1,
        11 to 0..2, 17 to 0..2, 18 to 0..2, 22 to 0..1)
    private val writes = mapOf(4 to 0x20, 8 to 0x25, 10 to 0x29, 11 to 0x2B,
        17 to 0x30, 18 to 0x31, 22 to 0x33)
    data class Report(val field: Int, val value: Int)
    fun query(field: Int): ByteArray {
        require(field in ranges)
        return byteArrayOf(0, 0x27, 1, 0, 1, field.toByte())
    }
    fun write(field: Int, value: Int): ByteArray? {
        if (value !in (ranges[field] ?: return null)) return null
        val payload = if (field == 4) byteArrayOf(0, value.toByte()) else byteArrayOf(value.toByte())
        return byteArrayOf(0, writes.getValue(field).toByte(), 1, 0, payload.size.toByte()) + payload
    }
    fun parse(bytes: ByteArray): Report? {
        if (bytes.size !in 8..9 || bytes[0].toInt() != 0 || bytes[1].toInt() !in listOf(0x27, 0x28) ||
            bytes[2].toInt() != 2 || bytes[3].toInt() != 0 || bytes[4].toInt() != bytes.size - 5) return null
        val field = bytes[5].toInt() and 255
        val width = if (field == 4) 2 else 1
        if (bytes.size != 7 + width || bytes[6].toInt() != width || (field == 4 && bytes[7].toInt() != 0)) return null
        val value = bytes.last().toInt() and 255
        if (value !in (ranges[field] ?: return null)) return null
        return Report(field, value)
    }
}
