package dev.hyperears.protocol.vivo

/** Independently implemented from ScrewVivoTWS 81beeef0 command bytes; TWS 3e only. */
object VivoTws3eWireCodec {
    private fun command(id: Int, vararg payload: Int) = VivoTwsProtocol.frame(
        3, VivoTwsProtocol.VIVO_VENDOR, id, payload.map(Int::toByte).toByteArray(),
    )

    fun setDoubleTap(right: Boolean, action: Int): ByteArray {
        require(action in setOf(0, 1, 2, 3, 6))
        return command(0x0102, action or if (right) 0x10 else 0)
    }

    fun setHold(left: Int, right: Int): ByteArray {
        require(left in HOLD_VALUES && right in HOLD_VALUES)
        return command(0x0131, 5, left, right)
    }

    fun setDeepX(preset: Int): ByteArray {
        require(preset in setOf(0, 1, 2, 3, 5))
        return command(0x0118, preset)
    }

    fun setWearDetection(enabled: Boolean) = command(0x0103, if (enabled) 1 else 0)

    // The source documents writes, but not an authoritative call-behavior readback. Kept at
    // codec level only: no request/UI capability can be unlocked by unrelated JSON fields.
    fun setCallGestures(doubleTap: Boolean, hold: Boolean) =
        command(0x0150, 3, (if (doubleTap) 2 else 0) or (if (hold) 1 else 0))

    private val HOLD_VALUES = setOf(8, 9, 10, 11, 255)

    /** Bounded JSON object extraction from the mixed RFCOMM stream, independent of packet splits. */
    class StatusDecoder {
        private val buffer = java.io.ByteArrayOutputStream()
        private var depth = 0
        private var quoted = false
        private var escaped = false
        fun offer(bytes: ByteArray): List<String> = buildList {
            for (byte in bytes) {
                val c = byte.toInt() and 255
                if (depth == 0) {
                    if (c != '{'.code) continue
                    buffer.reset()
                    depth = 1
                    buffer.write(c)
                    continue
                }
                buffer.write(c)
                if (buffer.size() > 8192) { reset(); continue }
                if (quoted) {
                    if (escaped) escaped = false
                    else if (c == '\\'.code) escaped = true
                    else if (c == '"'.code) quoted = false
                } else when (c) {
                    '"'.code -> quoted = true
                    '{'.code -> depth++
                    '}'.code -> depth--
                }
                if (depth == 0) { add(buffer.toString("UTF-8")); reset() }
            }
        }
        fun reset() { buffer.reset(); depth = 0; quoted = false; escaped = false }
    }
}
