package dev.hyperears.integration

import kotlinx.serialization.Serializable

@Serializable
enum class OppoNoiseMode(val title: String, val domain: NoiseMode) {
    OFF("关闭",NoiseMode.OFF), TRANSPARENCY("标准通透",NoiseMode.TRANSPARENCY),
    ANC("降噪",NoiseMode.ANC), LIGHT("轻度降噪",NoiseMode.ANC), MEDIUM("中度降噪",NoiseMode.ANC),
    DEEP("深度降噪",NoiseMode.ANC), SMART("智能降噪",NoiseMode.ANC), ADAPTIVE("自适应",NoiseMode.ANC),
    LISTEN("聆听",NoiseMode.TRANSPARENCY), VOICE("强化人声",NoiseMode.TRANSPARENCY),
}
@Serializable
data class OppoNoiseOption(val mode: OppoNoiseMode, val index: Int, val writable: Boolean)
@Serializable
data class OppoNoiseDefinition(val productId: Int, val options: List<OppoNoiseOption>) {
    fun option(index: Int) = options.singleOrNull { it.index == index }
    fun primary(mode: NoiseMode): OppoNoiseOption? {
        if (mode == NoiseMode.WIND) return null
        val target = when (mode) {
            NoiseMode.OFF -> OppoNoiseMode.OFF
            NoiseMode.TRANSPARENCY -> OppoNoiseMode.TRANSPARENCY
            else -> OppoNoiseMode.ANC
        }
        return options.firstOrNull { it.writable && it.mode == target }
            ?: options.firstOrNull { mode == NoiseMode.ANC && it.writable && it.mode == OppoNoiseMode.SMART }
            ?: options.firstOrNull { mode == NoiseMode.ANC && it.writable && it.mode.domain == NoiseMode.ANC }
    }
}
@Serializable
data class OppoNoiseSelection(val definition: OppoNoiseDefinition, val reportedIndex: Int) {
    val selected: OppoNoiseOption? get() = definition.option(reportedIndex)
}

/** Leaf 929d813f device_models.json: mappings only, activated by product ID + current-mode report.
 * Same-name child entries are report aliases; decideByEarDevice entries are not writable.
 */
internal object OppoNoiseCatalog {
    private fun o(mode: OppoNoiseMode, index: Int, writable: Boolean = true) = OppoNoiseOption(mode,index,writable)
    private val entries = mapOf(
        0x06F010 to listOf(o(OppoNoiseMode.ANC,0), o(OppoNoiseMode.OFF,1), o(OppoNoiseMode.TRANSPARENCY,2)),
        0x067414 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x06E810 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x06C810 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x067014 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x06D810 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x06C410 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x066414 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x066814 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x061410 to listOf(o(OppoNoiseMode.DEEP,3), o(OppoNoiseMode.LIGHT,2), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.TRANSPARENCY,1)),
        0x062010 to listOf(o(OppoNoiseMode.ANC,0), o(OppoNoiseMode.OFF,1), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.LISTEN,3,false)),
        0x060C14 to listOf(o(OppoNoiseMode.SMART,4), o(OppoNoiseMode.DEEP,3), o(OppoNoiseMode.LIGHT,2), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.TRANSPARENCY,1)),
        0x062014 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x063014 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x063414 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x065C10 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x066010 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x061014 to listOf(o(OppoNoiseMode.DEEP,3), o(OppoNoiseMode.LIGHT,2), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.TRANSPARENCY,1)),
        0x064010 to listOf(o(OppoNoiseMode.ANC,0), o(OppoNoiseMode.OFF,1), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.LISTEN,3,false)),
        0x063C10 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false), o(OppoNoiseMode.VOICE,9)),
        0x063810 to listOf(o(OppoNoiseMode.ANC,0), o(OppoNoiseMode.OFF,1), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.LISTEN,3,false)),
        0x065010 to listOf(o(OppoNoiseMode.ANC,0), o(OppoNoiseMode.OFF,1), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.LISTEN,3,false)),
        0x062414 to listOf(o(OppoNoiseMode.ANC,0), o(OppoNoiseMode.OFF,1), o(OppoNoiseMode.TRANSPARENCY,2)),
        0x062814 to listOf(o(OppoNoiseMode.ANC,0), o(OppoNoiseMode.OFF,1), o(OppoNoiseMode.TRANSPARENCY,2)),
        0x050C10 to listOf(o(OppoNoiseMode.ANC,0), o(OppoNoiseMode.OFF,1), o(OppoNoiseMode.TRANSPARENCY,2)),
        0x050C14 to listOf(o(OppoNoiseMode.ANC,0), o(OppoNoiseMode.OFF,1), o(OppoNoiseMode.TRANSPARENCY,2)),
        0x063C14 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x066810 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x067410 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false), o(OppoNoiseMode.VOICE,9)),
        0x064014 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x067C10 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x064414 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x069010 to listOf(o(OppoNoiseMode.ANC,0), o(OppoNoiseMode.OFF,1), o(OppoNoiseMode.TRANSPARENCY,2)),
        0x065014 to listOf(o(OppoNoiseMode.ANC,0), o(OppoNoiseMode.OFF,1), o(OppoNoiseMode.TRANSPARENCY,2)),
        0x069C10 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x06A010 to listOf(o(OppoNoiseMode.ANC,0), o(OppoNoiseMode.OFF,1), o(OppoNoiseMode.TRANSPARENCY,2)),
        0x064C14 to listOf(o(OppoNoiseMode.ANC,0), o(OppoNoiseMode.OFF,1), o(OppoNoiseMode.TRANSPARENCY,2)),
        0x068C10 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.ADAPTIVE,11), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false)),
        0x06C010 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.ADAPTIVE,11), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false)),
        0x06BC10 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.ADAPTIVE,11), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false)),
        0x065414 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.ADAPTIVE,11), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false)),
        0x06EC10 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.ADAPTIVE,11), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
        0x067814 to listOf(o(OppoNoiseMode.ANC,1), o(OppoNoiseMode.SMART,7), o(OppoNoiseMode.DEEP,4), o(OppoNoiseMode.MEDIUM,5), o(OppoNoiseMode.LIGHT,6), o(OppoNoiseMode.ADAPTIVE,11), o(OppoNoiseMode.OFF,0), o(OppoNoiseMode.OFF,3,false), o(OppoNoiseMode.TRANSPARENCY,2), o(OppoNoiseMode.TRANSPARENCY,8,false)),
    )
    fun find(productId: Int?): OppoNoiseDefinition? = entries[productId]?.let { OppoNoiseDefinition(productId!!,it) }
    val size: Int get() = entries.size
}
