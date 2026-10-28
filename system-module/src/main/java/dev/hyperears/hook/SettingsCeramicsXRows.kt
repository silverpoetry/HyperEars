package dev.hyperears.hook

import dev.hyperears.integration.*

internal object SettingsCeramicsXRows {
    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        latest: () -> EarbudState?, send: (ControlRequest) -> Unit,
        confirm: (String, () -> Unit) -> Unit) {
        if (!RoseCeramicsXSettingsPolicy.available(state, address)) return
        val feature = state.features.get<RoseCeramicsXSettingsFeatureState>() ?: return
        for ((setting, selected) in feature.values) {
            if (!feature.supports(RoseCeramicsXControlRequest.SetValue(setting, selected), state.features.get<NoiseModeFeatureState>()?.mode)) continue
            add(host.choice("hyperears.ceramicsx.${setting.name.lowercase(java.util.Locale.ROOT)}",
                RoseCeramicsXSettingsPolicy.title(setting), RoseCeramicsXSettingsPolicy.labels(setting),
                setting.values.map(Int::toString), selected.toString(), true) { raw -> runCatching {
                val value = raw.toIntOrNull() ?: return@runCatching
                val request = RoseCeramicsXControlRequest.SetValue(setting, value)
                val current = latest() ?: return@runCatching
                if (RoseCeramicsXSettingsPolicy.request(current, address, request) == null) return@runCatching
                if (setting == RoseCeramicsXSetting.AUDIO) {
                    if (current.features.get<RoseCeramicsXSettingsFeatureState>()?.values?.get(setting) != selected) return@runCatching
                    confirm("切换音频与双设备模式可能影响音频连接。确认切换？") { runCatching {
                        val fresh = latest() ?: return@runCatching
                        if (fresh.features.get<RoseCeramicsXSettingsFeatureState>()?.values?.get(setting) != selected) return@runCatching
                        RoseCeramicsXSettingsPolicy.request(fresh, address, request)?.let(send)
                    } }
                } else RoseCeramicsXSettingsPolicy.request(current, address, request)?.let(send)
            } })
        }
    }
}
