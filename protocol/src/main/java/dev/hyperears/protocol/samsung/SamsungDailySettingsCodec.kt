package dev.hyperears.protocol.samsung

/** GalaxyBudsClient 754b6fdf wire facts. Model/revision qualifiers never fabricate missing fields. */
object SamsungDailySettingsCodec {
    const val CUSTOM_EQ_READ = 0x69
    const val CUSTOM_EQ_WRITE = 0x89
    const val CUSTOM_AMBIENT = 0x82
    const val CONVERSATION_TIMEOUT = 0x7B
    const val ANC_LEVEL = 0x83
    const val FIND_START = 0xA0
    const val FIND_STOP = 0xA1
    const val FIND_MUTE = 0xA2
    const val FIND_MUTE_REPORT = 0xA3
    data class Ambient(val enabled: Boolean, val left: Int, val right: Int, val tone: Int, val maximum: Int)
    data class State(val revision: Int, val eqEnabled: Boolean?, val eqPreset: Int?,
        val ambient: Int?, val ambientMaximum: Int, val ancLevel: Int?, val customAmbient: Ambient?,
        val locked: Boolean?, val leftHold: Int?, val rightHold: Int?, val voiceFocus: Boolean?, val conversationTimeoutSeconds: Int? = null)
    data class Curve(val presetCount: Int, val bands: List<Int>)
    fun customEqSupported(model: SamsungBudsCodec.Model) = model in setOf(
        SamsungBudsCodec.Model.BUDS3, SamsungBudsCodec.Model.BUDS3_PRO,
        SamsungBudsCodec.Model.BUDS3_FE, SamsungBudsCodec.Model.BUDS4, SamsungBudsCodec.Model.BUDS4_PRO)
    fun parse(frame: SamsungBudsCodec.Frame, model: SamsungBudsCodec.Model): State? {
        if (frame.id != 0x61 || model == SamsungBudsCodec.Model.UNKNOWN) return null
        val p = frame.payload
        val original = model.original
        val plus = model == SamsungBudsCodec.Model.BUDS_PLUS
        if (p.size < if (original || plus) 12 else 20) return null
        val revision = p[0].u()
        fun value(index: Int, range: IntRange): Int? = p.getOrNull(index)?.u()?.takeIf { it in range }
        fun bool(index: Int): Boolean? = value(index, 0..1)?.let { it == 1 }
        val eq = value(if (original || plus) 11 else 9, if (original) 0..9 else if (customEqSupported(model)) 0..7 else 0..5)?.takeIf { original || it != 6 }
        val eqEnabled = if (original) bool(10) else eq?.let { it != 0 }
        val preset = eq?.let { if (original) if (it >= 5) it - 5 else it else (it - 1).coerceAtLeast(0) }
        val extraHigh = when (model) {
            SamsungBudsCodec.Model.BUDS_PLUS -> revision >= 9 && bool(20) == true
            SamsungBudsCodec.Model.BUDS_PRO -> (revision < 3 && bool(22) == true) || (revision >= 6 && bool(30) == true)
            SamsungBudsCodec.Model.BUDS2 -> bool(26) == true
            SamsungBudsCodec.Model.BUDS2_PRO -> revision >= 13 && bool(45) == true
            SamsungBudsCodec.Model.BUDS3_PRO -> bool(44) == true
            else -> false
        }
        val maximum = when (model) {
            SamsungBudsCodec.Model.BUDS -> 4
            SamsungBudsCodec.Model.BUDS_PRO -> 3
            SamsungBudsCodec.Model.BUDS4_PRO -> 4
            else -> 2
        } + if (extraHigh) 1 else 0
        val ambient = if (!model.ambient) null else value(if (original || plus) 9 else 23, 0..maximum)
        val customOffset = when {
            model == SamsungBudsCodec.Model.BUDS_PRO && revision >= 8 -> 33
            model == SamsungBudsCodec.Model.BUDS2 && revision >= 5 -> 29
            model in setOf(SamsungBudsCodec.Model.BUDS2_PRO, SamsungBudsCodec.Model.BUDS_FE,
                SamsungBudsCodec.Model.BUDS3, SamsungBudsCodec.Model.BUDS3_PRO,
                SamsungBudsCodec.Model.BUDS4, SamsungBudsCodec.Model.BUDS4_PRO) -> 29
            else -> null // Core / 3 FE upstream tail is explicitly TODO.
        }
        val customMax = if (model == SamsungBudsCodec.Model.BUDS_PRO || extraHigh) 4 else 2
        val custom = customOffset?.let { offset ->
            val enabled = bool(offset) ?: return@let null
            val volumes = p.getOrNull(offset + 1)?.u() ?: return@let null
            val left = volumes ushr 4; val right = volumes and 15
            val tone = value(offset + 2, 0..4) ?: return@let null
            if (left !in 0..customMax || right !in 0..customMax) return@let null
            Ambient(enabled, left, right, tone, customMax)
        }?.takeIf { model.ambient }
        val anc = if (model in setOf(SamsungBudsCodec.Model.BUDS_PRO, SamsungBudsCodec.Model.BUDS_FE)) value(24, 0..1) else null
        val hold = p.getOrNull(if (original || plus) 13 else 11)?.u()
        val holdRange = if (original) 0..4 else 1..4
        return State(revision, eqEnabled, preset, ambient, maximum, anc, custom,
            if (original || plus) bool(12) else null,
            hold?.ushr(4)?.takeIf { it in holdRange }, hold?.and(15)?.takeIf { it in holdRange },
            if (original) value(8, 0..1)?.let { it == 1 } else null,
            if (model == SamsungBudsCodec.Model.BUDS2_PRO) value(27,0..2)?.let { (it+1)*5 } else null)
    }
    fun equalizer(model: SamsungBudsCodec.Model, enabled: Boolean, preset: Int): ByteArray {
        require(preset in 0..4)
        return SamsungBudsCodec.packet(0x86, if (model.original)
            byteArrayOf(if (enabled) 1 else 0, (preset + 5).toByte())
            else byteArrayOf(if (enabled) (preset + 1).toByte() else 0), model)
    }
    fun customAmbient(model: SamsungBudsCodec.Model, enabled: Boolean, left: Int, right: Int, tone: Int): ByteArray {
        require(left in 0..4 && right in 0..4 && tone in 0..4)
        return SamsungBudsCodec.packet(CUSTOM_AMBIENT, byteArrayOf(if (enabled) 1 else 0,
            left.toByte(), right.toByte(), tone.toByte()), model)
    }
    fun customEqualizer(model: SamsungBudsCodec.Model, gains: List<Int>): List<ByteArray> {
        require(customEqSupported(model) && gains.size == 9 && gains.all { it in -10..10 })
        return listOf(SamsungBudsCodec.packet(CUSTOM_EQ_WRITE, byteArrayOf(9) + gains.map(Int::toByte), model),
            SamsungBudsCodec.packet(0x86, byteArrayOf(7), model))
    }
    fun curve(frame: SamsungBudsCodec.Frame): Curve? {
        val p = frame.payload
        if (frame.id != CUSTOM_EQ_READ || p.size < 2 || p[0].u() !in 1..16 || p[1].u() != 9) return null
        val count = p[0].u(); val bands = p[1].u()
        if (p.size != 2 + count * bands) return null
        val gains = p.takeLast(bands).map(Byte::toInt)
        if (gains.any { it !in -10..10 }) return null
        return Curve(count, gains)
    }
    private fun Byte.u() = toInt() and 255
}
