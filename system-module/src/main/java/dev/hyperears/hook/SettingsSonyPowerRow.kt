package dev.hyperears.hook
import dev.hyperears.integration.*
internal object SettingsSonyPowerRow {
    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        latest: () -> EarbudState?, send: (ControlRequest) -> Unit, confirm: (String, () -> Unit) -> Unit) {
        val feature = state.features.get<SonyPowerFeatureState>()?.takeIf { it.available } ?: return
        val request = SonyControlRequest.PowerOff(feature.version)
        val row = host.row("hyperears.sony.power_off", "关闭耳机")
        host.summary(row, "关闭后将断开蓝牙连接")
        host.enable(row, SonyCardPolicy.request(state, address, request) != null)
        host.onClick(row) {
            if (latest()?.let { SonyCardPolicy.request(it, address, request) } != null)
                confirm("确定关闭耳机？关闭后将断开当前蓝牙连接。") {
                    latest()?.let { SonyCardPolicy.request(it, address, request) }?.let(send)
                }
        }
        add(row)
    }
}
