package dev.hyperears.hook

import dev.hyperears.integration.*

internal object SettingsCoralRows {
    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        current: () -> EarbudState?, send: (ControlRequest) -> Unit, confirm: (String, () -> Unit) -> Unit) {
        state.features.get<RoseCoralSettingsFeatureState>()?.let { feature ->
            RoseCoralSetting.entries.forEach { setting ->
                val selected = setting.value(feature) ?: return@forEach
                fun change(value: Int) { runCatching {
                    val latest = current() ?: return@runCatching
                    RoseCoralCardPolicy.request(latest, address, setting, value)?.let(send)
                } }
                val key = "hyperears.coral.${setting.name.lowercase(java.util.Locale.ROOT)}"
                val enabled = RoseCoralCardPolicy.ready(state, address)
                add(if (setting.isToggle) host.toggle(key, setting.label, selected == 1, enabled) { change(if (it) 1 else 0) }
                    else host.choice(key, setting.label, setting.range.map(Int::toString), setting.range.map(Int::toString), selected.toString(), enabled) { value -> value.toIntOrNull()?.let(::change) })
            }
        }
        val audio = state.features.get<RoseCoralAudioFeatureState>()?.mode ?: return
        val choices = RoseCoralAudioMode.entries
        val labels = listOf("AAC/SBC", "LDAC", "LHDC", "LC3")
        add(host.choice("hyperears.coral.audio", "音频编码", labels, choices.map { it.name }, audio.name,
            RoseCoralCardPolicy.ready(state, address)) { value -> runCatching {
            val mode = choices.singleOrNull { it.name == value } ?: return@runCatching
            val latest = current() ?: return@runCatching
            if (RoseCoralCardPolicy.audioRequest(latest, address, mode) == null) return@runCatching
            confirm("切换音频编码可能影响音频连接。确认切换？") { runCatching {
                val fresh = current() ?: return@runCatching
                if (fresh.features.get<RoseCoralAudioFeatureState>()?.mode != audio) return@runCatching
                RoseCoralCardPolicy.audioRequest(fresh, address, mode)?.let(send)
            } }
        } })
    }
}
