package dev.hyperears.protocol.sony

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** SonyPods 3f701ff8 V2 Table1 GS boolean metadata and fixed-message confirmation. */
object SonyMultipointCodec {
    data class Capability(val slot: Int, val settingType: Int, val format: Int, val title: String, val description: String) {
        val multipoint get() = settingType == 0 && format == 1 && title == "MULTIPOINT_SETTING"
    }
    fun bytes(vararg p: Int) = p.map(Int::toByte).toByteArray()
    fun slots(v2: Boolean, payload: ByteArray): Set<Int>? {
        if (!v2) return null
        val p = payload.u()
        if (p.size < 3 || p[0] != 7 || p[1] != 0 || p.size != 3 + 2 * p[2]) return null
        return p.drop(3).chunked(2).map { it[0] }.filter { it in 0xD1..0xD4 }.toSet()
    }
    fun capability(payload: ByteArray): Capability? {
        val p = payload.u()
        if (p.size < 6 || p[0] != 0xD1 || p[1] !in 0xD1..0xD4 || p[2] !in 0..1 || p[3] !in 0..1) return null
        val end = 5 + p[4]
        if (end >= p.size || p.size != end + 1 + p[end]) return null
        fun decode(start: Int, finish: Int) = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload.copyOfRange(start, finish))).toString()
        }.getOrNull()
        return Capability(p[1], p[2], p[3], decode(5, end) ?: return null, decode(end + 1, p.size) ?: return null)
    }
    fun status(payload: ByteArray): Pair<Int, Boolean>? {
        val p = payload.u()
        return if (p.size == 3 && p[0] in setOf(0xD3, 0xD5) && p[1] in 0xD1..0xD4 && p[2] in 0..1)
            p[1] to (p[2] == 0) else null
    }
    fun value(payload: ByteArray): Pair<Int, Boolean>? {
        val p = payload.u()
        return if (p.size == 4 && p[0] in setOf(0xD7, 0xD9) && p[1] in 0xD1..0xD4 && p[2] == 0 && p[3] in 0..1)
            p[1] to (p[3] == 0) else null
    }
    /** Only fixture-proven fixed confirmation action type 00; unrelated alert workflows stay unhandled. */
    fun alert(payload: ByteArray): Int? {
        val p = payload.u()
        return if (p.size == 4 && p[0] == 0x99 && p[1] == 0 && p[2] in setOf(6, 7, 112) && p[3] == 0) p[2] else null
    }
    private fun ByteArray.u() = map { it.toInt() and 255 }
}
