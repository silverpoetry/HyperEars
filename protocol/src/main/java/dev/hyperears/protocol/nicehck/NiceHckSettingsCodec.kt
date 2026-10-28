package dev.hyperears.protocol.nicehck

/** OriG in protocol facts from NiceHCK_Controller f1348a8d; no model capability inference. */
object NiceHckSettingsCodec {
    enum class Toggle(val query: Int, val write: Int) {
        GAME(0x0108, 0x0208), LOW_LATENCY(0x0106, 0x0206),
        MULTIPOINT(0x0105, 0x0205), WEAR_DETECTION(0x0109, 0x0209),
        WIND_SUPPRESSION(0x01E1, 0x02E1),
    }
    data class Firmware(val major: Int, val minor: Int)
    val queryFirmware get() = NiceHckWireCodec.command(0x0003)
    val queryEqualizer get() = NiceHckWireCodec.command(0x0107)
    fun query(toggle: Toggle) = NiceHckWireCodec.command(toggle.query)
    fun setToggle(toggle: Toggle, enabled: Boolean) = NiceHckWireCodec.command(
        toggle.write, byteArrayOf(if (enabled) 1 else 0))
    fun setEqualizer(preset: Int): ByteArray {
        require(preset in 0..6)
        // Both the source setter and report use the unshifted enum; no legacy offset exists.
        return NiceHckWireCodec.command(0x0207, byteArrayOf(preset.toByte()))
    }
    fun parseFirmware(frame: NiceHckWireCodec.Frame): Firmware? {
        if (frame.opcode != 0x0003 || frame.parameters.size != 2) return null
        return Firmware(frame.parameters[1].toInt() and 255, frame.parameters[0].toInt() and 255)
    }
    fun parseEqualizer(frame: NiceHckWireCodec.Frame): Int? {
        if (frame.opcode != 0x0107) return null
        return frame.parameters.singleOrNull()?.toInt()?.and(255)?.takeIf { it in 0..6 }
    }
    fun parseToggle(frame: NiceHckWireCodec.Frame): Pair<Toggle, Boolean>? {
        val toggle = Toggle.entries.firstOrNull { it.query == frame.opcode } ?: return null
        val value = frame.parameters.singleOrNull()?.toInt() ?: return null
        if (value !in 0..1) return null
        return toggle to (value == 1)
    }
}
