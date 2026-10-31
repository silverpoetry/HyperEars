package dev.hyperears.protocol.tozo

object TozoDailyCodec {
    enum class Setting(val command: Int, val notification: Int) {
        GAME(0x0006,0x200f), WEAR(0x0016,0x201e), MULTIPOINT(0x0025,0x201c), LDAC(0x0029,0x2020),
    }
    data class Report(val setting: Setting, val enabled: Boolean)
    fun parse(bytes: ByteArray): Report? {
        val frame = TozoWireCodec.decode(bytes) ?: return null
        val setting = Setting.entries.find { frame.command == it.command || frame.command == it.notification } ?: return null
        if (frame.payload.size != 1 || frame.payload[0].toInt() !in 0..1) return null
        return Report(setting, frame.payload[0].toInt() == 1)
    }
    fun read(setting: Setting) = TozoWireCodec.encode(setting.command,byteArrayOf())
    fun write(setting: Setting, enabled: Boolean) =
        TozoWireCodec.encode(setting.command + 0x1000,byteArrayOf(if(enabled)1 else 0))
}
