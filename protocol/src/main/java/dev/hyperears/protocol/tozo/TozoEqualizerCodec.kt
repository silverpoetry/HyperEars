package dev.hyperears.protocol.tozo

/** NC20 Pro advanced EQ: ten (frequency LE16, signed gain tenths, Q byte) records. */
object TozoEqualizerCodec {
    data class Band(val frequencyHz: Int, val gainTenths: Int, val qValue: Int)

    fun parse(bytes: ByteArray): List<Band>? {
        val frame = TozoWireCodec.decode(bytes) ?: return null
        if (frame.command !in setOf(0x0018, 0x201f) || frame.payload.size != 40) return null
        val data = frame.payload
        return (0 until 40 step 4).map { i ->
            Band((data[i].toInt() and 255) or ((data[i+1].toInt() and 255) shl 8),
                data[i+2].toInt(), data[i+3].toInt() and 255)
        }
    }

    fun read(): ByteArray = TozoWireCodec.encode(0x0018, byteArrayOf())

    fun write(bands: List<Band>, persist: Boolean): ByteArray {
        require(bands.size == 10)
        require(bands.all { it.frequencyHz in 0..65535 && it.gainTenths in -128..127 && it.qValue in 0..255 })
        val payload = ByteArray(41)
        payload[0] = if (persist) 1 else 0
        bands.forEachIndexed { index, band ->
            val offset = 1 + index * 4
            payload[offset] = band.frequencyHz.toByte()
            payload[offset+1] = (band.frequencyHz ushr 8).toByte()
            payload[offset+2] = band.gainTenths.toByte()
            payload[offset+3] = band.qValue.toByte()
        }
        return TozoWireCodec.encode(0x1018, payload)
    }
}
