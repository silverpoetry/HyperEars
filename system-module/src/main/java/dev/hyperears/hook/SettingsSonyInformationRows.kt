package dev.hyperears.hook

import dev.hyperears.integration.*

internal object SettingsSonyInformationRows {
    fun codecName(code: Int?): String? = when (code) {
        0 -> "暂未确定"; 1 -> "SBC"; 2 -> "AAC"; 0x10 -> "LDAC"; 0x20 -> "aptX"; 0x21 -> "aptX HD"; 0x30 -> "LC3"; 255 -> "其他"; else -> null
    }
    fun add(host: SettingsPreferenceHost, state: EarbudState, firmware: Boolean, add: (Any) -> Unit) {
        val info = state.features.get<SonyInformationFeatureState>() ?: return
        fun row(key: String, title: String, value: String?) {
            if (value == null) return
            val preference = host.row("hyperears.sony.info.$key", title)
            host.summary(preference, value); host.enable(preference, false); add(preference)
        }
        if (firmware) { row("firmware", "固件版本", info.displayFirmware ?: info.firmware); return }
        row("codec", "当前音频编码", codecName(info.codec))
        val generation = when (info.upscalingGeneration) { 0 -> "DSEE HX"; 1 -> "DSEE"; 2 -> "DSEE Extreme"; 3 -> "DSEE Ultimate"; else -> null }
        val effect = when (info.upscalingStatus) { 0 -> "未启用"; 1 -> "正在生效"; 2 -> "当前未生效"; else -> null }
        if (generation != null && effect != null) row("processing", "当前音频处理", "$generation · $effect")
    }
}
