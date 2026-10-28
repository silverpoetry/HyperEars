package dev.hyperears.protocol.sony

/** Independently organized wire facts from SonyPods 3f701ff8 (GPL-3.0), Table 1 only. */
object SonyDailyControlsCodec {
    data class Functions(val eqTypes: Set<Int>, val ambientTypes: Set<Int>)
    data class Band(val kind: Int, val value: Int)
    data class Eq(val type: Int, val preset: Int?, val ult: Int?, val steps: List<Int>, val bass: Int? = null)
    data class Ambient(val type: Int, val level: Int, val voice: Boolean, val voiceWritable: Boolean,
        val adaptive: Boolean, val payload: List<Int>)

    // Real EqPresetId bytes, deliberately excluding app-only SoundEffect/ULT vocabulary.
    val presets = (0..7).toSet() + (0x10..0x17) + (0x20..0x23) + (0x30..0x33) + (0xA0..0xA5)
    fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    fun functions(v2: Boolean, payload: ByteArray): Functions? {
        val p = payload.u()
        if (p.size < 3 || p[0] != 7 || p[1] != 0 || p.size != 3 + p[2] * (if (v2) 2 else 1)) return null
        val codes = p.drop(3).chunked(if (v2) 2 else 1).map { it[0] }
        val eq = if (v2) mapOf(0x50 to 0, 0x51 to 1, 0x52 to 2, 0x53 to 3, 0x55 to 0x31, 0x57 to 4)
            else mapOf(0x51 to 1, 0x52 to 2, 0x53 to 3)
        val ambient = if (v2) mapOf(0x64 to 0x13, 0x65 to 0x14, 0x67 to 0x22,
            0x68 to 0x15, 0x6A to 0x16, 0x6B to 0x17, 0x6C to 0x18, 0x6D to 0x19)
            else mapOf(0x62 to 2, 0x63 to 2)
        return Functions(codes.mapNotNull(eq::get).toSet(), codes.mapNotNull(ambient::get).toSet())
    }
    fun geometry(payload: ByteArray): Pair<Int, List<Band>>? {
        val p = payload.u()
        if (p.size < 3 || p[0] != 0x5B || p[2] !in 1..16 || p.size != 3 + p[2] * 3) return null
        val bands = p.drop(3).chunked(3).map { Band(it[0], (it[1] shl 8) or it[2]) }
        if (bands.any { it.kind !in setOf(1, 2, 0x10) || (it.kind != 0x10 && it.value == 0) }) return null
        // Source standard arrays place Clear Bass first. Do not silently move/relabel a slot.
        if (bands.count { it.kind == 0x10 } > 1 || bands.drop(1).any { it.kind == 0x10 }) return null
        return p[1] to bands
    }
    fun status(payload: ByteArray): Pair<Int, Boolean>? {
        val p = payload.u()
        if (p.size != 3 || p[0] !in setOf(0x53, 0x55) || p[2] !in 0..1) return null
        return p[1] to (p[2] == 0)
    }
    fun equalizer(v2: Boolean, payload: ByteArray): Eq? {
        val p = payload.u()
        if (p.size < 3 || p[0] !in setOf(0x57, 0x59)) return null
        val type = p[1]
        if (type == if (v2) 1 else 2) {
            if (p.size == 3) return p[2].toByte().toInt().takeIf { it in -10..10 }?.let { Eq(type, null, null, emptyList(), it) }
            // V2 EBB may carry a preset plus band array rather than a scalar.
            if (!v2 || p.size < 5 || p.size != 4 + p[3] || p[3] == 0) return null
            if (p.drop(4).any { it !in 0..20 }) return null
            return Eq(type, p[2].takeIf { it in presets || it == 255 } ?: return null, null, p.drop(4))
        }
        val countIndex = when {
            !v2 && type in setOf(1, 3) -> 3
            v2 && type == 0x31 -> 2
            v2 && type == 3 -> 4
            v2 && type in setOf(0, 2, 4) -> 3
            else -> return null
        }
        if (p.size <= countIndex || p[countIndex] > 16 || p.size != countIndex + 1 + p[countIndex]) return null
        val preset = if (v2 && type == 0x31) null else p[2].takeIf { it in presets || it == 255 } ?: return null
        val ult = if (v2 && type == 3) p[3].takeIf { it in 0..2 } ?: return null else null
        val steps = p.drop(countIndex + 1)
        if (steps.any { it !in 0..20 }) return null
        return Eq(type, preset, ult, steps)
    }
    fun setEq(v2: Boolean, type: Int, preset: Int, steps: List<Int>, ult: Int? = null): ByteArray {
        require(steps.size <= 16 && steps.all { it in 0..20 })
        require(preset in presets || preset == 255)
        val header = when {
            v2 && type == 0x31 -> listOf(0x58, type, steps.size)
            v2 && type == 3 -> listOf(0x58, type, preset, requireNotNull(ult).also { require(it in 0..2) }, steps.size)
            else -> listOf(0x58, type, preset, steps.size)
        }
        return (header + steps).map(Int::toByte).toByteArray()
    }
    fun ambient(v2: Boolean, payload: ByteArray): Ambient? {
        val p = payload.u()
        if (p.size < 3 || p[0] !in setOf(0x67, 0x69)) return null
        val type = p[1]
        val offsets = if (!v2) {
            if (type != 2 || p.size != 8 || p[2] !in setOf(0, 1, 0x11) || p[3] !in setOf(0, 2) ||
                p[4] !in 0..2 || p[5] != 1) return null
            6 to 7
        } else {
            val size = when (type) { 0x13, 0x14, 0x17, 0x18 -> 7; 0x15, 0x16 -> 8; 0x19 -> 9; 0x22 -> 6; else -> return null }
            if (p.size != size || p[2] !in 0..1 || p[3] !in 0..1) return null
            if (type in setOf(0x15, 0x16, 0x17, 0x19) && p[4] !in 0..1) return null
            if (type in setOf(0x13, 0x14, 0x18) && p[4] !in 0..2) return null
            if (type in setOf(0x15, 0x16) && p[5] !in 0..5) return null
            if (type == 0x19 && (p[7] !in 0..1 || p[8] !in 0..2)) return null
            when (type) { 0x15, 0x16 -> 6 to 7; 0x22 -> 4 to 5; else -> 5 to 6 }
        }
        if (p[offsets.first] !in 0..1 || p[offsets.second] !in 0..20) return null
        return Ambient(type, p[offsets.second], p[offsets.first] == 1,
            !v2 || type in setOf(0x14, 0x17, 0x19), v2 && type == 0x19 && p[7] == 1, p)
    }
    fun setAmbient(v2: Boolean, report: Ambient, level: Int, voice: Boolean): ByteArray {
        require(level in 1..20 && !report.adaptive && (report.voiceWritable || voice == report.voice))
        val p = report.payload.toMutableList()
        p[0] = 0x68
        if (v2) p[2] = 1 else if (p[2] != 0) p[2] = 0x11
        val voiceIndex = if (!v2) 6 else when (report.type) { 0x15, 0x16 -> 6; 0x22 -> 4; else -> 5 }
        p[voiceIndex] = if (voice) 1 else 0
        p[voiceIndex + 1] = level
        return p.map(Int::toByte).toByteArray()
    }
    private fun ByteArray.u() = map { it.toInt() and 255 }
}
