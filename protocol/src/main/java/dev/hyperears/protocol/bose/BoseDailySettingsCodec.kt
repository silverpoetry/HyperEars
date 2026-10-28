package dev.hyperears.protocol.bose

/** Bosectl 70570b2 / noisecancel 0391b61 interoperability fields; only STATUS is evidence. */
object BoseDailySettingsCodec {
    const val PROMPTS = 3
    const val CNC = 5
    const val ANR = 6
    const val EQ = 7
    const val MULTIPOINT = 10
    const val AUTO_PAUSE = 24
    data class EqBand(val id: Int, val minimum: Int, val maximum: Int, val current: Int)
    data class Cnc(val rawLevel: Int, val enabled: Boolean)
    fun query(function: Int) = BoseBmapWireCodec.packet(1, function, BoseBmapWireCodec.Operator.GET)
    private fun isState(frame: BoseBmapWireCodec.Frame, function: Int) =
        frame.functionBlock == 1 && frame.function == function && frame.operator == BoseBmapWireCodec.Operator.STATUS
    fun parseEq(frame: BoseBmapWireCodec.Frame): List<EqBand>? {
        if (!isState(frame, EQ) || frame.payload.size % 4 != 0 || frame.payload.size > 12) return null
        val bands = frame.payload.asList().chunked(4).map {
            EqBand(it[3].toInt() and 255, it[0].toInt(), it[1].toInt(), it[2].toInt())
        }
        if (bands.any { it.id !in 0..2 || it.minimum > it.maximum || it.current !in it.minimum..it.maximum } ||
            bands.map { it.id }.distinct().size != bands.size) return null
        return bands
    }
    fun parsePrompts(frame: BoseBmapWireCodec.Frame): Int? =
        if (isState(frame, PROMPTS)) frame.payload.singleOrNull()?.toInt()?.and(255) else null
    fun parseMultipoint(frame: BoseBmapWireCodec.Frame): Boolean? =
        if (isState(frame, MULTIPOINT)) frame.payload.singleOrNull()?.let { it.toInt() and 2 != 0 } else null
    fun parseAutoPause(frame: BoseBmapWireCodec.Frame): Boolean? =
        if (isState(frame, AUTO_PAUSE)) frame.payload.singleOrNull()?.toInt()?.takeIf { it in 0..1 }?.let { it == 1 } else null
    fun parseCnc(frame: BoseBmapWireCodec.Frame): Cnc? {
        if (!isState(frame, CNC) || frame.payload.size != 3 || frame.payload[0].toInt() != 11 ||
            frame.payload[1].toInt() !in 0..10 || frame.payload[2].toInt() !in 0..1) return null
        return Cnc(frame.payload[1].toInt(), frame.payload[2].toInt() == 1)
    }
    fun parseAnr(frame: BoseBmapWireCodec.Frame): Int? = if (isState(frame, ANR) && frame.payload.size == 2)
        frame.payload[0].toInt().takeIf { it in 0..3 } else null
    fun setEq(id: Int, value: Int): ByteArray {
        require(id in 0..2 && value in -128..127)
        return write(EQ, byteArrayOf(value.toByte(), id.toByte()))
    }
    fun setPrompts(raw: Int, enabled: Boolean): ByteArray {
        require(raw in 0..255)
        // Bit 5 is the owned toggle. Preserve language and opaque upper bits.
        return write(PROMPTS, byteArrayOf(((raw and 0xDF) or if (enabled) 0x20 else 0).toByte()))
    }
    fun setToggle(function: Int, enabled: Boolean): ByteArray {
        require(function in setOf(MULTIPOINT, AUTO_PAUSE))
        return write(function, byteArrayOf(if (enabled) 1 else 0))
    }
    private fun write(function: Int, payload: ByteArray) =
        BoseBmapWireCodec.packet(1, function, BoseBmapWireCodec.Operator.SET_GET, payload)
}
