package dev.hyperears.protocol.tozo

/** TOZO NC20 Pro V7: four actions and one reserved byte per ear. */
object TozoTouchCodec {
    private val clicks = setOf(0, 1, 2, 3, 4, 5, 6, 8, 10)
    private val holds = setOf(0, 4, 5, 6, 8, 10)
    fun actions(offset: Int): Set<Int> = when (offset) {
        0, 1, 2, 5, 6, 7 -> clicks
        3, 8 -> holds
        else -> emptySet()
    }
    fun read(): ByteArray = TozoWireCodec.encode(0x0003, byteArrayOf())
    fun parse(bytes: ByteArray): ByteArray? {
        val frame = TozoWireCodec.decode(bytes) ?: return null
        return frame.payload.takeIf { frame.command == 0x0003 && it.size == 10 }?.copyOf()
    }
    fun write(previous: ByteArray, offset: Int, action: Int): ByteArray {
        require(previous.size == 10 && action in actions(offset))
        return TozoWireCodec.encode(0x1003, previous.copyOf().also { it[offset] = action.toByte() })
    }
}
