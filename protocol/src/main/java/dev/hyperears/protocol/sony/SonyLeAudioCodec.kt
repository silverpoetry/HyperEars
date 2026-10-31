package dev.hyperears.protocol.sony

/** WH/HBS V2 Table1 LEA subset, SonyPods 3f701ff. No Android pairing side effects. */
object SonyLeAudioCodec {
    sealed interface Report {
        data class Capability(val supported: Boolean) : Report
        data class Identity(val classic: String, val leAddresses: List<String>) : Report
        data class Availability(val available: Boolean) : Report
        data class Setting(val enabled: Boolean?, val notification: Boolean) : Report
        data class History(val value: Int?) : Report
    }
    fun parse(payload: ByteArray): Report? {
        val p = payload.map { it.toInt() and 255 }
        if (p.size < 2) return null
        if (p[0] == 7 && p[1] == 0) {
            if (p.size < 3 || p.size != 3 + p[2] * 2) return null
            val codes = p.drop(3).chunked(2).map { it[0] }
            if (codes.distinct().size != codes.size) return null
            return Report.Capability(0x41 in codes && 0x42 in codes)
        }
        if (p[0] == 0x41 && p[1] == 1) {
            if ((p.size - 2) % 17 != 0 || (p.size - 2) / 17 !in 2..3) return null
            val addresses = payload.copyOfRange(2, payload.size).toList().chunked(17).map {
                String(it.toByteArray(), Charsets.US_ASCII).uppercase(java.util.Locale.ROOT)
            }
            if (addresses.any { !Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}").matches(it) } || addresses.distinct().size != addresses.size) return null
            return Report.Identity(addresses.first(), addresses.drop(1))
        }
        if (p.size != 3) return null
        return when {
            p[0] in setOf(0x43, 0x45) && p[1] == 12 && p[2] in 0..1 -> Report.Availability(p[2] == 0)
            p[0] in setOf(0x47, 0x49) && p[1] == 12 && p[2] in setOf(0, 1, 255) -> Report.Setting(if (p[2] == 255) null else p[2] == 0, p[0] == 0x49)
            p[0] in setOf(0x47, 0x49) && p[1] == 1 && p[2] in setOf(0, 1, 2, 255) -> Report.History(p[2].takeIf { it != 255 })
            else -> null
        }
    }
    fun reads() = listOf(byteArrayOf(0x40, 1), byteArrayOf(0x42, 12), byteArrayOf(0x46, 12), historyQuery())
    fun historyQuery() = byteArrayOf(0x46, 1)
    fun setting(enabled: Boolean, changeConnectionMethod: Boolean) = byteArrayOf(0x48, 12,
        if (enabled) 0 else 1, if (changeConnectionMethod) 0 else 1)
}
