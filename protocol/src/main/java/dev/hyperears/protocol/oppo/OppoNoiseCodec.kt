package dev.hyperears.protocol.oppo

/** Leaf CapabilityProfileFactory/QuickBuds Commands: per-product bit index, not a fixed enum. */
object OppoNoiseCodec {
    fun setIndex(index: Int): ByteArray {
        require(index in 0..15)
        val value = 1 shl index
        return OppoWireCodec.setAnc(value and 255, if (index >= 8) value ushr 8 else null)
    }
    fun reportIndex(frame: OppoWireCodec.Frame): Int? {
        val p = frame.payload
        val start = when (frame.command) {
            0x810C -> if (p.firstOrNull() == 0.toByte()) 1 else return null
            0x0204 -> if (p.firstOrNull() == 3.toByte()) 1 else return null
            else -> return null
        }
        if (p.size - start !in 3..4 || p[start] != 1.toByte() || p[start+1] != 1.toByte()) return null
        val raw = (p[start+2].toInt() and 255) or ((p.getOrNull(start+3)?.toInt()?.and(255) ?: 0) shl 8)
        if (raw == 0 || raw and (raw-1) != 0) return null
        return Integer.numberOfTrailingZeros(raw)
    }
}
