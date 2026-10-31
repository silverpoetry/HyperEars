package dev.hyperears.protocol.sony

/** V2 Table1 LE connection alerts, from SonyPods 3f701ff. Parsing never acknowledges an alert. */
object SonyLeAudioAlertCodec {
    data class Alert(val domain: Int, val message: Int, val action: Int, val items: List<Int> = emptyList())
    private val fixedMessages = setOf(44,45,46,47,48,49,52,53,54,55,56,57,64,65,116,117,118,119)
    private val flexibleMessages = setOf(12,13,14,15,16,17)

    /** Subscribe only when a user initiates connection-mode switching, not on every connection. */
    fun enableConnectionAlerts(): List<ByteArray> = listOf(
        byteArrayOf(0x94.toByte(),0,0),
        byteArrayOf(0x94.toByte(),5,0),
    )

    fun parse(payload: ByteArray): Alert? {
        val p = payload.map { it.toInt() and 255 }
        if (p.size < 4 || p[0] != 0x99) return null
        return when (p[1]) {
            0,2,4 -> if (p.size == 4 && p[2] in fixedMessages) Alert(p[1],p[2],p[3]) else null
            6 -> if (p.size == 5 + p[3] && p[2] in flexibleMessages)
                Alert(6,p[2],p.last(),p.subList(4,4+p[3]).toList()) else null
            else -> null
        }
    }

    /** Only the known yes/no action; left/right selections require their own UI contract. */
    fun reply(alert: Alert, positive: Boolean): ByteArray? {
        if (alert.action != 0) return null
        if (alert.domain == 6) {
            if (alert.message !in flexibleMessages) return null
        } else if (alert.domain !in setOf(0,4) || alert.message !in fixedMessages || alert.items.isNotEmpty()) return null
        return byteArrayOf(0x98.toByte(),alert.domain.toByte(),alert.message.toByte(),if(positive)1 else 0)
    }
}
