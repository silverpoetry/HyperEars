package dev.hyperears.protocol.sony

/** SonyPods 3f701ff, Table1 function discovery and USER_POWER_OFF; no implicit capability. */
object SonyPowerCodec {
    fun available(v2: Boolean, payload: ByteArray): Boolean? {
        val p = payload.map { it.toInt() and 255 }
        if (p.size < 3 || p[0] != 7 || p[1] != 0 || p.size != 3 + p[2] * if (v2) 2 else 1) return null
        val functions = if (v2) p.drop(3).chunked(2).map { it[0] } else p.drop(3)
        if (functions.distinct().size != functions.size) return null
        return (if (v2) 0x23 else 0x21) in functions
    }
    fun powerOff(v2: Boolean): ByteArray = if (v2) byteArrayOf(0x24, 3, 1) else byteArrayOf(0x22, 0, 1)
}
