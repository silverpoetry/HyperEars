package dev.hyperears.hook

import android.view.View
import dev.hyperears.integration.*

/** One actual Settings preference screen, one address; no device I/O or timer belongs here. */
internal class SettingsHeadsetBinding(
    private val host: SettingsPreferenceHost,
    private val anchor: View,
    private val address: String,
    private val currentAddress: () -> String?,
    private val record: () -> SettingsHeadsetState.Record?,
    private val environment: MiLinkCardEnvironment,
    private val canReturn: (SettingsHeadsetState.Record) -> Boolean = { false },
) {
    private var category: Any? = null
    private val rows = mutableListOf<Any>()
    private var details: MiLinkToggleDetails? = null
    private var confirmation: android.content.DialogInterface? = null
    private var confirmationLease: Any? = null
    private var token: String? = null
    private var closed = false
    fun render() {
        if (closed) return
        try {
            val accepted = current()
            if (accepted == null) { remove(); return }
            if (token != accepted.token) { details?.dismiss(); details = null }
            token = accepted.token
            confirmationLease = null; confirmation?.dismiss(); confirmation = null
            details?.render(accepted.state)
            removeRows()
            val group = host.category("hyperears.headset", "耳机设置")
            category = group
            // PreferenceGroup requires its inherited PreferenceManager before adding children.
            host.add(host.screen, group)
            val state = accepted.state
            if (state.lifecycle.controlOwnership != ControlOwnership.MODULE) {
                details?.dismiss(); details = null
                if (canReturn(accepted)) addControllerRow(group, accepted, returning = true)
                if (rows.isEmpty()) { host.detach(group); category = null }
                return
            }
            val battery = state.battery
            (if (battery.overall.available) listOf("耳机" to battery.overall, "充电盒" to battery.case)
                else listOf("左耳" to battery.left, "右耳" to battery.right, "充电盒" to battery.case))
                .filter { it.second.available }.forEachIndexed { index, (label, value) ->
                    val row = host.row("hyperears.battery.$index", label)
                    host.summary(row, "${value.percent}%${if (value.charging) " · 充电中" else ""}")
                    host.enable(row, false); add(group, row)
                }
            val modes = state.adapter?.supportedNoiseModes.orEmpty().filter { it != NoiseMode.WIND || state.adapter?.capabilities?.windNoiseControl == true }
            val listening = state.features.get<AppleListeningModeFeatureState>()
            if (listening != null && listening.supportedModes.isNotEmpty()) {
                val choices = listening.supportedModes.toList()
                add(group, host.choice("hyperears.apple.listening", "聆听模式", choices.map { when (it) {
                    AppleListeningMode.OFF -> "关闭"; AppleListeningMode.ANC -> "降噪"
                    AppleListeningMode.TRANSPARENCY -> "通透"; AppleListeningMode.ADAPTIVE -> "自适应"
                } }, choices.map { it.name }, listening.mode?.name, AppleListeningCardPolicy.render(state).enabled) { value ->
                    val latest = runCatching { currentFor(accepted.token) }.getOrNull() ?: return@choice
                    AppleListeningCardPolicy.request(latest.state, AppleListeningMode.valueOf(value))?.let { send(it, accepted.token) }
                })
            }
            if (listening == null && state.noiseMode != null && modes.isNotEmpty()) add(group, host.choice("hyperears.noise", "噪声控制",
                modes.map(::noiseLabel), modes.map { it.name }, state.noiseMode?.name, writable(state)) { value ->
                val latest = runCatching { currentFor(accepted.token) }.getOrNull() ?: return@choice
                val mode = NoiseMode.valueOf(value)
                if (writable(latest.state) && mode != latest.state.noiseMode && mode in latest.state.adapter?.supportedNoiseModes.orEmpty())
                    send(StandardControlRequest.SetNoiseMode(mode), accepted.token)
            })
            state.features.get<HonorAncDepthFeatureState>()?.takeIf { it.supported.isNotEmpty() }?.let { depth ->
                val values = depth.supported.toList()
                add(group, host.choice("hyperears.honor.depth", "降噪强度", values.map(HonorAncDepthDetails::label),
                    values.map { it.name }, depth.current?.name,
                    writable(state) && state.noiseMode == NoiseMode.ANC && depth.current in depth.supported) { value ->
                    val latest = runCatching { currentFor(accepted.token) }.getOrNull() ?: return@choice
                    HonorAncDepthPolicy.request(latest.state, address, HonorAncDepth.valueOf(value))?.let { send(it, accepted.token) }
                })
            }
            val inlineCurrent = {
                if (closed) null else runCatching { currentFor(accepted.token)?.state?.takeIf {
                    it.adapter?.presentationId == state.adapter?.presentationId && it.address.equals(address, true) && it.sessionActive && writable(it)
                } }.getOrNull()
            }
            val inlineSend: (ControlRequest) -> Unit = { send(it, accepted.token) }
            SettingsVendorRows.add(host, state, address, { add(group, it) }, inlineCurrent, inlineSend)
            SettingsAppleRows.add(host, state, address, { add(group, it) }, inlineCurrent, inlineSend)
            val confirmInline: (String, () -> Unit) -> Unit = { message, confirmed ->
                guarded {
                    confirmationLease = null; confirmation?.dismiss()
                    val lease = Any(); confirmationLease = lease
                    confirmation = host.confirm(message, { if (confirmationLease === lease) confirmationLease = null }) { guarded {
                        if (confirmationLease !== lease) return@guarded
                        confirmationLease = null
                        confirmed()
                    } }
                }
            }
            SettingsLegacyRows.add(host, state, address, { add(group, it) }, inlineCurrent, inlineSend, confirmInline)
            SettingsRoseZtRows.add(host, state, address, { add(group, it) }, inlineCurrent, inlineSend, confirmInline)
            SettingsCoralRows.add(host, state, address, { add(group, it) }, inlineCurrent, inlineSend, confirmInline)
            SettingsRoseRows.add(host, state, address, { add(group, it) }, inlineCurrent, inlineSend, confirmInline)
            SettingsCeramicsXRows.add(host, state, address, { add(group, it) }, inlineCurrent, inlineSend, confirmInline)
            SettingsSonyAssignableRows.add(host, state, address, { add(group, it) }, inlineCurrent, inlineSend)
            SettingsAdditionalRows.add(host, state, address, { add(group, it) }, inlineCurrent, inlineSend)
            SettingsBoseButtonRows.add(host, state, address, { add(group, it) }, inlineCurrent, inlineSend)
            SettingsRoseRsV2Rows.add(host, state, address, { add(group, it) }, inlineCurrent, inlineSend)
            SettingsAdvancedEditors.forState(state)?.takeIf { it.available(state, address) }?.let { editor ->
                val row = host.row("hyperears.advanced", "高级设置")
                host.summary(row, "均衡器、手势及耳机已确认的扩展设置")
                host.enable(row, writable(state))
                host.onClick(row) {
                    guarded {
                        val latest = currentFor(accepted.token) ?: return@guarded
                        val currentEditor = SettingsAdvancedEditors.forState(latest.state) ?: return@guarded
                        if (latest.state.adapter?.presentationId != state.adapter?.presentationId || !writable(latest.state) || !currentEditor.available(latest.state, address)) return@guarded
                        details?.dismiss()
                        details = currentEditor.open(anchor, address, environment)
                    }
                }
                add(group, row)
            }
            if (state.adapter?.controlApps?.isNotEmpty() == true) addControllerRow(group, accepted, returning = false)
            if (rows.isEmpty()) { host.detach(group); category = null }
        } catch (error: Exception) {
            ModuleLog.warn("Settings", "native preference binding failed; removed module group", error)
            close()
        }
    }
    private fun addControllerRow(group: Any, accepted: SettingsHeadsetState.Record, returning: Boolean) {
        val row = host.row(if (returning) "hyperears.controller.return" else "hyperears.controller.open",
            if (returning) "返回 HyperEars 控制" else "打开控制 App 并让出控制")
        host.summary(row, if (returning) "结束本次交接；仍遵循运行时退避设置" else
            accepted.state.adapter?.controlApps.orEmpty().joinToString(" / ") { it.displayName })
        host.enable(row, if (returning) canReturn(accepted) else writable(accepted.state))
        host.onClick(row) { guarded {
            val latest = currentFor(accepted.token) ?: return@guarded
            val result = if (returning) {
                if (!canReturn(latest)) return@guarded
                dev.hyperears.bridge.VendorHandoffLauncher.returnControl(address,
                    environment.sessionTokenProvider, environment.controlSender)
            } else {
                if (!writable(latest.state)) return@guarded
                dev.hyperears.bridge.VendorHandoffLauncher.open(anchor.context, address,
                    latest.state.adapter?.controlApps.orEmpty(), environment.stateProvider,
                    environment.sessionTokenProvider, environment.observeState, environment.controlSender)
            }
            if (!result) host.summary(row, "未能完成交接，请确认控制 App 已安装且设备仍连接")
        } }
        add(group, row)
    }

    /** Host layout reconstruction may reparent our category; reattach only our own object. */
    fun reconcile() = render()
    private fun current() = if (!currentAddress().equals(address, true)) null else record()
    private fun currentFor(expected: String) = current()?.takeIf { it.token == expected }
    private fun writable(state: EarbudState) = state.privateChannelConnected && state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE
    private fun send(request: ControlRequest, expected: String) = guarded {
        if (currentFor(expected)?.state?.let(::writable) != true) return@guarded
        environment.controlSender(address, request)
        render()
    }
    private fun guarded(action: () -> Unit) { if (!closed) runCatching(action).onFailure { close() } }
    private fun add(group: Any, row: Any) { rows += row; host.add(group, row) }
    private fun removeRows() {
        rows.forEach { runCatching { host.clearListeners(it) } }; rows.clear()
        category?.let { runCatching { host.detach(it) } }; category = null
    }
    private fun remove() { confirmationLease = null; confirmation?.dismiss(); confirmation = null; details?.dismiss(); details = null; token = null; removeRows() }
    fun close() { if (closed) return; closed = true; remove() }
    private fun noiseLabel(mode: NoiseMode) = when (mode) {
        NoiseMode.ANC -> "降噪"; NoiseMode.OFF -> "关闭"; NoiseMode.TRANSPARENCY -> "通透"; NoiseMode.WIND -> "抗风噪"
    }
}
