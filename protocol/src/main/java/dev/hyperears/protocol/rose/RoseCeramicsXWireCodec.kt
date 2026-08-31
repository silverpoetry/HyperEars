package dev.hyperears.protocol.rose

/** Vendor value codec captured from the ROSE Ceramics X companion BLE endpoint. */
object RoseCeramicsXWireCodec {
    enum class NoiseMode(val value: Int) {
        WIND(0x00),
        ANC(0x01),
        TRANSPARENCY(0x02),
        OFF(0x03),
    }

    val queryNoiseMode: ByteArray
        get() = byteArrayOf(0x00, 0x27, 0x01, 0x00, 0x01, NOISE_FIELD)

    fun setNoiseMode(mode: NoiseMode): ByteArray =
        byteArrayOf(0x00, 0x2C, 0x01, 0x00, 0x01, mode.value.toByte())

    /** Accepts both the direct query response (`0x27`) and asynchronous state report (`0x28`). */
    fun parseNoiseMode(value: ByteArray): NoiseMode? {
        if (value.size != NOISE_REPORT_SIZE) return null
        if (value[0].unsigned() != 0x00) return null
        if (value[1].unsigned() !in setOf(0x27, 0x28)) return null
        if (value[2].unsigned() != 0x02 || value[3].unsigned() != 0x00) return null
        if (value[4].unsigned() != 0x03 || value[5].unsigned() != NOISE_FIELD.unsigned()) return null
        if (value[6].unsigned() != 0x01) return null
        return NoiseMode.entries.firstOrNull { it.value == value[7].unsigned() }
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private const val NOISE_REPORT_SIZE = 8
    private const val NOISE_FIELD: Byte = 0x0C
}
