package dev.hyperears.protocol.rose

/** HyperRose 4e212f: source-confirmed scalar settings; no decoding of opaque touch/vendor blocks. */
object RoseRfcommSettingsCodec {
    val ranges = mapOf(0x09 to (1..6).toSet(), 0x0E to setOf(0, 1), 0x2A to (0..3).toSet(),
        0x2C to setOf(1, 3, 5), 0x2D to setOf(1, 5), 0x07 to setOf(0, 1), 0x2E to (1..5).toSet())
    fun set(sequence: Int, type: Int, value: Int): ByteArray {
        require(value in ranges[type].orEmpty())
        return RoseBudsFeelMk2WireCodec.command(sequence, 2, byteArrayOf(type.toByte(), value.toByte()))
    }
    /** Called only with a complete checksum-validated frame from the existing stream decoder. */
    internal fun parse(frame: ByteArray): Map<Int, Int>? {
        val payload = frame.copyOfRange(3, frame.size - 2)
        if (frame[2].u() == 2 && payload.size == 2) {
            val type = payload[0].u(); val value = payload[1].u()
            return if (type in setOf(9, 14) && value in ranges[type].orEmpty()) mapOf(type to value) else null
        }
        var i = if (frame[2].u() == 21 && payload.firstOrNull()?.u() == 1) 21 else 0
        if (i > payload.size) return null
        val result = mutableMapOf<Int, Int>()
        val seen = mutableSetOf<Int>()
        while (i < payload.size) {
            val length = payload[i].u()
            // Existing MK2 status captures pad the declared block with zero bytes.
            if (length == 0 && (i until payload.size).all { payload[it] == 0.toByte() }) break
            if (length < 2 || i + length + 1 > payload.size) return null
            val type = payload[i + 1].u()
            if (!seen.add(type)) return null
            if (type in ranges) {
                if (length != 2 || payload[i + 2].u() !in ranges.getValue(type)) return null
                result[type] = payload[i + 2].u()
            }
            i += length + 1
        }
        return result
    }
    private fun Byte.u() = toInt() and 255
}
