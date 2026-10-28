package dev.hyperears.protocol.apple

/** Private session material. Deliberately not a data class, feature, Parcelable or Serializable. */
class AppleProximityKeys private constructor(private val records: Map<Int, ByteArray>) : AutoCloseable {
    fun copy(): AppleProximityKeys = AppleProximityKeys(records.mapValues { it.value.copyOf() })
    fun copyKey(type: Int): ByteArray? = records[type]?.copyOf()
    override fun close() { records.values.forEach { it.fill(0) } }
    override fun toString(): String = "AppleProximityKeys[redacted]"

    companion object {
        private val prefix = byteArrayOf(4, 0, 4, 0, 0x31, 0)
        fun request(type: Int): ByteArray {
            require(type == 1 || type == 4)
            return byteArrayOf(4, 0, 4, 0, 0x30, 0, type.toByte(), 0)
        }
        /** Zero means incomplete; -1 means invalid. Stream callers consume the entire bounded record. */
        fun frameSize(bytes: ByteArray): Int {
            if (bytes.size < 7) return 0
            if (!prefix.indices.all { bytes[it] == prefix[it] }) return -1
            val count = bytes.u(6)
            if (count !in 1..8) return -1
            var cursor = 7
            repeat(count) {
                if (bytes.size < cursor + 4) return 0
                val length = bytes.u(cursor + 2) or (bytes.u(cursor + 3) shl 8)
                if (length > 256) return -1
                cursor += 4 + length
                if (bytes.size < cursor) return 0
            }
            return cursor
        }
        fun parse(bytes: ByteArray): AppleProximityKeys? {
            if (frameSize(bytes) != bytes.size || bytes.size < 7) return null
            val values = mutableMapOf<Int, ByteArray>()
            val seen = mutableSetOf<Int>()
            var cursor = 7
            fun reject(): AppleProximityKeys? { values.values.forEach { it.fill(0) }; return null }
            repeat(bytes.u(6)) {
                val type = bytes.u(cursor) or (bytes.u(cursor + 1) shl 8)
                val length = bytes.u(cursor + 2) or (bytes.u(cursor + 3) shl 8)
                cursor += 4
                if (!seen.add(type)) return reject()
                if (type == 1 || type == 4) {
                    if (length != 16) return reject()
                    values[type] = bytes.copyOfRange(cursor, cursor + length)
                }
                cursor += length
            }
            return if (values.isEmpty()) null else AppleProximityKeys(values)
        }
    }
}

internal fun ByteArray.u(index: Int): Int = this[index].toInt() and 255
