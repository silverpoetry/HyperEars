package dev.hyperears.protocol.swarmoo

/** MO3 PRO notification datagrams, from the device captures supplied in HyperEars issue #97. */
object SwarmooMo3WireCodec {
    const val SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
    const val MODE_UUID = "0000ffa1-0000-1000-8000-00805f9b34fb"
    const val BATTERY_UUID = "00002a19-0000-1000-8000-00805f9b34fb"

    val query: ByteArray get() = byteArrayOf(0x55, 0xAA.toByte(), 0x0A, 0x00, 0xFF.toByte())

    data class ModeState(val main: Int, val sub: Int)
    data class BatteryState(val left: Int?, val right: Int?, val case: Int?)

    fun setMain(mode: Int): ByteArray {
        require(mode in 0..2)
        return command(1, mode)
    }

    fun setSub(mode: Int): ByteArray {
        require(mode in 1..2) // No captured write establishes a submode-0 command.
        return command(2, mode)
    }

    fun parseMode(bytes: ByteArray): ModeState? {
        if (!isReport(bytes, 23)) return null
        val main = bytes[5].unsigned()
        val sub = bytes[6].unsigned()
        if (main !in 0..2 || sub !in 0..2) return null
        return ModeState(main, sub)
    }

    fun parseBattery(bytes: ByteArray): BatteryState? {
        if (!isReport(bytes, 7)) return null
        val values = bytes.drop(4).map { it.unsigned() }
        if (values.any { it !in 0..100 && it != 0xFF }) return null
        return BatteryState(
            left = values[0].takeIf { it in 0..100 },
            right = values[1].takeIf { it in 0..100 },
            // The capture establishes nonzero box readings; 0 is ambiguous and stays unknown.
            case = values[2].takeIf { it in 1..100 },
        )
    }

    fun isKnownCommand(bytes: ByteArray): Boolean = bytes.contentEquals(query) ||
        (bytes.size == 5 && bytes[0] == 0x55.toByte() && bytes[1] == 0xAA.toByte() &&
            bytes[2] == 0x0A.toByte() && when (bytes[3].unsigned()) {
                1 -> bytes[4].unsigned() in 0..2
                2 -> bytes[4].unsigned() in 1..2
                else -> false
            })

    private fun command(kind: Int, value: Int): ByteArray =
        byteArrayOf(0x55, 0xAA.toByte(), 0x0A, kind.toByte(), value.toByte())

    private fun isReport(bytes: ByteArray, size: Int): Boolean = bytes.size == size &&
        bytes[0] == 0x55.toByte() && bytes[1] == 0xAA.toByte() &&
        bytes[2] == 0x0B.toByte() && bytes[3] == 0.toByte()

    private fun Byte.unsigned(): Int = toInt() and 0xFF
}
