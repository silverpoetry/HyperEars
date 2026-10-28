package dev.hyperears.hook

import dev.hyperears.integration.*

internal object SettingsLegacyRows {
    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        latest: () -> EarbudState?, send: (ControlRequest) -> Unit,
        confirm: (String, () -> Unit) -> Unit) {
        if (!RoseLegacySettingsPolicy.available(state, address)) return
        val feature = state.features.get<RoseLegacySettingsFeatureState>() ?: return
        for ((setting, selected) in feature.values) {
            if (!feature.supports(RoseLegacyControlRequest.SetValue(feature.model,setting, selected), state.noiseMode)) continue
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
        feature.touch?.let { snapshot ->
            val choices=RoseLegacyTouch.choices(feature.profile)
            RoseLegacyTouch.slots(feature.profile).forEachIndexed { index,slot ->
                val row=host.choice("hyperears.legacy.touch.$slot",RoseLegacySettingsPolicy.touchTitle(slot),
                    choices.map{RoseLegacySettingsPolicy.touchLabel(feature.model,it)},choices.map(Int::toString),
                    RoseLegacyTouch.action(snapshot,index,feature.profile).toString(),true) { raw -> runCatching {
                    val value=raw.toIntOrNull()?:return@runCatching
                    val request=RoseLegacyControlRequest.SetTouch(feature.model,snapshot,slot,value)
                    latest()?.let{RoseLegacySettingsPolicy.request(it,address,request)}?.let(send)
                } }
                if(RoseLegacyTouch.action(snapshot,index,feature.profile) !in choices)host.summary(row,RoseLegacySettingsPolicy.touchCaption(feature.model,RoseLegacyTouch.action(snapshot,index,feature.profile)))
                add(row)
            }
        }

    }
}
