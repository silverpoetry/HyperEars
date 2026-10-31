package dev.hyperears.protocol.sony

/** SonyPods 3f701ff: Table1 device information and generation-specific COMMON status. */
object SonyInformationCodec {
    sealed interface Report {
        data class Text(val kind: Int, val value: String) : Report
        data class Color(val series: Int, val color: Int) : Report
        data class Codec(val value: Int) : Report
        data class Upscaling(val generation: Int, val status: Int) : Report
    }
    fun reads(v2: Boolean): List<ByteArray> = listOf(byteArrayOf(4, 1), byteArrayOf(4, 2), byteArrayOf(4, 3)) +
        if (v2) listOf(byteArrayOf(0x12, 9), byteArrayOf(0x12, 2), byteArrayOf(0x12, 3))
        else listOf(byteArrayOf(0x18, 0), byteArrayOf(0x14, 0))
    fun parse(v2: Boolean, payload: ByteArray): Report? {
        if (payload.size < 3) return null
        val command = payload[0].toInt() and 255
        val type = payload[1].toInt() and 255
        if (command == 5 && type == 3) return if (payload.size == 4) Report.Color(payload[2].toInt() and 255, payload[3].toInt() and 255) else null
        if ((command == 5 && type in 1..2) || (v2 && command in setOf(0x13, 0x15) && type == 9)) {
            val length = payload[2].toInt() and 255
            if (length !in 1..128 || payload.size != length + 3) return null
            val value = runCatching { payload.copyOfRange(3, payload.size).decodeToString(throwOnInvalidSequence = true) }
                .getOrNull()?.trimEnd('\u0000')?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } ?: return null
            return Report.Text(type, value)
        }
        val codec = if (v2) command in setOf(0x13, 0x15) && type == 2 else command in setOf(0x19, 0x1B) && type == 0
        if (codec) {
            if (payload.size != 3) return null
            val code = payload[2].toInt() and 255
            return if (v2 && code !in setOf(0, 1, 2, 0x10, 0x20, 0x21, 0x30, 255)) null
                else Report.Codec(if (!v2 && code !in setOf(0, 1, 2, 0x10, 0x20, 0x21, 255)) 0 else code)
        }
        val effect = if (v2) command in setOf(0x13, 0x15) && type == 3 else command in setOf(0x15, 0x17) && type == 0
        if (!effect || payload.size != 4) return null
        val generation = payload[2].toInt() and 255
        val status = payload[3].toInt() and 255
        return if (generation in 0..3 && status in 0..2) Report.Upscaling(generation, status) else null
    }
}
