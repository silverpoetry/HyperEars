package dev.hyperears.hook

import dev.hyperears.integration.*
import dev.hyperears.protocol.rose.RoseZtWireCodec

internal object SettingsRoseZtRows {
    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        latest: () -> EarbudState?, send: (ControlRequest) -> Unit, confirm: (String, () -> Unit) -> Unit) {
        if (!RoseZtSettingsPolicy.available(state, address)) return
        val settings = state.features.get<RoseZtSettingsFeatureState>() ?: return
        val profile = settings.profile ?: return
        for ((setting, selected) in settings.values) {
            val kind = RoseZtWireCodec.Setting.entries.singleOrNull { it.name == setting } ?: continue
            val values = kind.values(profile).toList()
            if (selected !in values) continue
            add(host.choice("hyperears.zt.${setting.lowercase(java.util.Locale.ROOT)}", RoseZtLabels.title(setting),
                RoseZtLabels.labels(setting, profile), values.map(Int::toString), selected.toString(), true) { raw -> runCatching {
                val value = raw.toIntOrNull()?.takeIf { it in values && it != selected } ?: return@runCatching
                val request = RoseZtControlRequest.SetValue(settings.model, setting, value)
                val current = latest() ?: return@runCatching
                if (RoseZtSettingsPolicy.request(current, address, request) == null) return@runCatching
                if (setting in setOf("AUDIO", "MULTIPOINT")) {
                    if (current.features.get<RoseZtSettingsFeatureState>()?.values?.get(setting) != selected) return@runCatching
                    confirm("切换音频或双设备模式可能中断连接。确认切换？") { runCatching {
                        val fresh = latest() ?: return@runCatching
                        if (fresh.features.get<RoseZtSettingsFeatureState>()?.values?.get(setting) != selected) return@runCatching
                        RoseZtSettingsPolicy.request(fresh, address, request)?.let(send)
                    } }
                } else RoseZtSettingsPolicy.request(current, address, request)?.let(send)
            } })
        }
    }
}
