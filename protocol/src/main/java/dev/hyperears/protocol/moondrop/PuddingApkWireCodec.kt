package dev.hyperears.protocol.moondrop

/** PuddingPods 3.47 executable: four-byte transport header, full application length.
 * Flags-zero is the only generated form. Flagged trailers have no proven checksum algorithm.
 */
object PuddingApkWireCodec {
    val handshake = packet(byteArrayOf(0,10,3,0), 1)
    fun packet(payload: ByteArray, type: Int = 4): ByteArray {
        require(payload.size in 4..255)
        return byteArrayOf(-1,type.toByte(),0,payload.size.toByte()) + payload
    }
    fun query(sub: Int) = packet(byteArrayOf(0,29,sub.toByte(),if (sub == 64) 3 else 1))
    fun write(sub: Int, value: Int) = packet(byteArrayOf(0,29,sub.toByte(),if (sub == 64) 4 else 2,value.toByte()))
    val batteryQuery = packet(byteArrayOf(0,29,26,1,1,2))
    data class Scalar(val sub: Int, val value: Int)
    fun scalar(payload: ByteArray): Scalar? {
        if (payload.size != 5 || payload[0] != 0.toByte() || payload[1] != 29.toByte()) return null
        val sub = payload[2].toInt() and 255
        val op = payload[3].toInt() and 255
        val value = payload[4].toInt() and 255
        val valid = when (sub) {
            65 -> op in 3..4 && value in 0..4
            31 -> op in 1..2 && value in 0..2
            39 -> op in 1..2 && value in 0..1
            else -> false
        }
        return if (valid) Scalar(sub,value) else null
    }
    fun battery(payload: ByteArray): Map<Int,Int>? {
        if (payload.size !in 6..10 || payload.size % 2 != 0 ||
            !payload.copyOfRange(0,4).contentEquals(byteArrayOf(0,29,27,1))) return null
        val pairs = payload.drop(4).chunked(2).map { (it[0].toInt() and 255) to (it[1].toInt() and 255) }
        if (pairs.any { it.first !in 1..3 || it.second !in 0..100 } || pairs.map { it.first }.distinct().size != pairs.size) return null
        return pairs.toMap()
    }
    class Decoder {
        // At most 261 bytes: extended header + bounded application payload + trailer.
        // Incremental consumption avoids repeated whole-chunk copies on arbitrary stream noise.
        private val pending = java.util.ArrayDeque<Byte>()
        internal val bufferedBytes: Int get() = pending.size
        fun reset() { pending.clear() }
        fun offer(bytes: ByteArray): List<ByteArray> = buildList {
            bytes.forEach { byte ->
                pending.addLast(byte)
                while (pending.isNotEmpty()) {
                    if (pending.first != (-1).toByte()) { pending.removeFirst(); continue }
                    if (pending.size < 4) break
                    val flags = pending.elementAt(2).toInt() and 255
                    val header = if (flags and 2 != 0) 5 else 4
                    if (pending.size < header) break
                    val length = if (header == 5) ((pending.elementAt(3).toInt() and 255) shl 8) or
                        (pending.elementAt(4).toInt() and 255) else pending.elementAt(3).toInt() and 255
                    if (length > 255) { pending.removeFirst(); continue }
                    val total = header + length + (flags and 1)
                    if (pending.size < total) break
                    val accepted = flags == 0 && pending.elementAt(1) == 4.toByte() && length >= 4
                    repeat(header) { pending.removeFirst() }
                    val payload = ByteArray(length) { pending.removeFirst() }
                    if (flags and 1 != 0) pending.removeFirst()
                    if (accepted) add(payload)
                }
            }
        }
    }
}
