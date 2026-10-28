package dev.hyperears.protocol.sony

/** SonyPods 3f701ff8, Table 1 AUDIO/SYSTEM fields; caller owns the outer table routing. */
object SonyAudioSettingsCodec {
    data class Selection(val dsee: Int?, val quality: Int?, val speak: Int?, val wearing: Int? = null)
    fun functions(v2: Boolean, payload: ByteArray): Selection? {
        val p = payload.map { it.toInt() and 255 }
        if (p.size < 3 || p[0] != 7 || p[1] != 0 || p.size != 3 + p[2] * (if (v2) 2 else 1)) return null
        val codes = if (v2) p.drop(3).chunked(2).sortedBy { it[1] }.map { it[0] } else p.drop(3)
        if (!v2) return Selection(null, null, 5.takeIf { 0xF5 in codes }, 3.takeIf { 0xF3 in codes })
        return Selection(when { 0xE2 in codes -> 1; 0xED in codes -> 11; else -> null },
            when { 0xE7 in codes -> 5; 0xE1 in codes -> 0; 0xE3 in codes -> 2; else -> null },
            codes.firstNotNullOfOrNull { when (it) { 0xF2 -> 2; 0xFC -> 12; else -> null } })
    }
    fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    data class Value(val command: Int, val type: Int, val value: Int, val extra: Int? = null)
    fun audio(payload: ByteArray): Value? {
        val p = payload.map { it.toInt() and 255 }
        if (p.size < 3) return null
        val valid = when (p[0]) {
            0xE1 -> p.size == 3 && p[1] in setOf(1, 11) && p[2] in 0..3
            0xE3, 0xE5 -> p.size == 3 && p[1] in setOf(0, 2, 5, 11) && p[2] in 0..1
            0xE7, 0xE9 -> when (p[1]) {
                1, 11 -> p.size == 3 && p[2] in 0..1
                0, 2 -> p.size in 3..4 && p[2] in 0..1
                5 -> p.size in 3..4 && p[2] in 0..2
                else -> false
            }
            else -> false
        }
        return if (valid) Value(p[0], p[1], p[2], p.getOrNull(3)) else null
    }
    fun speak(v2: Boolean, payload: ByteArray): Value? {
        val p = payload.map { it.toInt() and 255 }
        if (p.size != 4 || p[1] !in (if (v2) setOf(2, 12) else setOf(5))) return null
        return when (p[0]) {
            0xF3, 0xF5 -> if (p[2] in 0..1 && p[3] in 0..1) Value(p[0], p[1], p[2], p[3]) else null
            0xF7, 0xF9 -> if (v2) {
                if (p[2] in 0..1 && p[3] == 1) Value(p[0], p[1], if (p[2] == 0) 1 else 0) else null
            } else if (p[2] == (if (p[0] == 0xF7) 0 else 1) && p[3] in 0..1) Value(p[0], p[1], p[3]) else null
            else -> null
        }
    }
    /** V1 control-by-wearing is a setting, not V2 wearing telemetry. */
    fun wearing(v2:Boolean,payload:ByteArray):Value? {
        if(v2)return null
        val p=payload.map{it.toInt() and 255}
        if(p.size<3||p[1]!=3)return null
        return when(p[0]) {
            0xF3,0xF5 -> if(p.size==3&&p[2] in 0..1)Value(p[0],3,p[2])else null
            0xF7,0xF9 -> if(p.size==4&&p[2]==0&&p[3] in 0..1)Value(p[0],3,p[3])else null
            else -> null
        }
    }

}
