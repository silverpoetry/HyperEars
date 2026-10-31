package dev.hyperears.hook

import dev.hyperears.integration.*

internal object SettingsSonyMultipointRows {
    fun reply(state: EarbudState, address: String, definition: SonyMultipointDefinition,
        alert: SonyMultipointAlert, confirm: Boolean, now: Long = System.nanoTime() / 1_000_000): SonyControlRequest? {
        if (now >= alert.expiresAtMillis) return null
        return SonyCardPolicy.request(state, address, SonyControlRequest.ReplyMultipointAlert(definition, alert, confirm))
    }
    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        latest: () -> EarbudState?, send: (ControlRequest) -> Unit) {
        val feature = state.features.get<SonyMultipointFeatureState>() ?: return
        val definition = feature.definition ?: return
        val enabled = feature.enabled ?: return
        val request = SonyControlRequest.SetMultipoint(definition, !enabled)
        add(host.toggle("hyperears.sony.multipoint", "同时连接两台设备", enabled,
            SonyCardPolicy.request(state, address, request) != null) { target ->
            latest()?.let { SonyCardPolicy.request(it, address, SonyControlRequest.SetMultipoint(definition, target)) }?.let(send)
        })
        val alert = feature.alert ?: return
        val message = when (alert.message) {
            6 -> "启用双设备连接需要关闭 LDAC"
            7 -> "更改双设备连接需要断开并重新连接"
            112 -> "将同时启用双设备连接和 LDAC，音质选项可能受到限制"
            else -> return
        }
        val row = host.choice("hyperears.sony.multipoint.confirm", "双设备连接确认",
            listOf("$message，确认继续", "取消变更"), listOf("confirm", "cancel"), null,
            reply(state, address, definition, alert, true) != null) { selected ->
            latest()?.let { reply(it, address, definition, alert, selected == "confirm") }?.let(send)
        }
        host.summary(row, message)
        add(row)
    }
}
