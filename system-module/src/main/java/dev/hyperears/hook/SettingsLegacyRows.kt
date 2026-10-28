package dev.hyperears.hook

import dev.hyperears.integration.*

internal object SettingsLegacyRows {
    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        latest: () -> EarbudState?, send: (ControlRequest) -> Unit,
        confirm: (String, () -> Unit) -> Unit) {
        if (!RoseLegacySettingsPolicy.available(state, address)) return
        val feature = state.features.get<RoseLegacySettingsFeatureState>() ?: return
        for ((setting, selected) in feature.values) {
            if (!feature.supports(RoseLegacyControlRequest.SetValue(feature.model,setting, selected))) continue
            add(host.choice("hyperears.legacy.${setting.name.lowercase(java.util.Locale.ROOT)}",
                RoseLegacySettingsPolicy.title(setting), RoseLegacySettingsPolicy.labels(setting,feature),
                requireNotNull(feature.profile).allowed(setting).map(Int::toString), selected.toString(), true) { raw -> runCatching {
                val value = raw.toIntOrNull() ?: return@runCatching
                val request = RoseLegacyControlRequest.SetValue(feature.model,setting, value)
                val current = latest() ?: return@runCatching
                if (RoseLegacySettingsPolicy.request(current, address, request) == null) return@runCatching
                if (setting == RoseLegacySetting.AUDIO) {
                    if (current.features.get<RoseLegacySettingsFeatureState>()?.values?.get(setting) != selected) return@runCatching
                    confirm("切换音频与双设备模式可能影响音频连接。确认切换？") { runCatching {
                        val fresh = latest() ?: return@runCatching
                        if (fresh.features.get<RoseLegacySettingsFeatureState>()?.values?.get(setting) != selected) return@runCatching
                        RoseLegacySettingsPolicy.request(fresh, address, request)?.let(send)
                    } }
                } else RoseLegacySettingsPolicy.request(current, address, request)?.let(send)
            } })
        }
    }
}
