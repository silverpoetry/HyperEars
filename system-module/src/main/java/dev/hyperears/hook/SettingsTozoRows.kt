package dev.hyperears.hook

import dev.hyperears.integration.*

/** Uses the same native preference host as Coral/Sony; state remains device-authoritative. */
internal object SettingsTozoRows {
    private val labels = mapOf(
        TozoNoise.OFF to "关闭", TozoNoise.ANC to "降噪", TozoNoise.TRANSPARENCY to "通透",
        TozoNoise.WIND to "抗风噪", TozoNoise.LEISURE to "休闲", TozoNoise.ADAPTIVE to "自适应",
    )
    private fun ready(state: EarbudState, address: String) = NativeHeadsetPageModel.tozo(state) &&
        state.address.equals(address, true) && state.sessionActive && state.privateChannelConnected &&
        state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE

    fun controls(host: SettingsPreferenceHost, state: EarbudState, address: String,
        add: (Any) -> Unit, latest: () -> EarbudState?, send: (ControlRequest) -> Unit) {
        if (!NativeHeadsetPageModel.tozo(state)) return
        val feature = state.features.get<TozoNc20ProFeatureState>() ?: return
        val modes = labels.keys.filter { it in feature.supportedModes }
        if (modes.isEmpty()) return
        add(host.choice("hyperears.tozo.noise", "噪声控制", modes.map { labels.getValue(it) },
            modes.map { it.name }, feature.noise?.name, ready(state, address)) { selection ->
            val mode = modes.find { it.name == selection } ?: return@choice
            val fresh = latest()?.takeIf { ready(it, address) } ?: return@choice
            val current = fresh.features.get<TozoNc20ProFeatureState>() ?: return@choice
            if (current.noise == feature.noise && mode != current.noise && mode in current.supportedModes)
                send(TozoControlRequest.SetNoise(mode))
        })
    }

    private val dailyLabels = mapOf(
        TozoDailySetting.GAME to "游戏模式", TozoDailySetting.WEAR to "佩戴检测",
        TozoDailySetting.MULTIPOINT to "双设备连接", TozoDailySetting.LDAC to "LDAC 高音质",
    )

    fun daily(host: SettingsPreferenceHost, state: EarbudState, address: String,
        add: (Any) -> Unit, latest: () -> EarbudState?, send: (ControlRequest) -> Unit,
        confirm: (String, () -> Unit) -> Unit) {
        if (!NativeHeadsetPageModel.tozo(state)) return
        val feature = state.features.get<TozoDailyFeatureState>() ?: return
        for (setting in TozoDailySetting.entries) {
            val value = feature.values[setting] ?: continue
            add(host.toggle("hyperears.tozo.daily.${setting.name.lowercase()}", dailyLabels.getValue(setting),
                value, ready(state,address) && feature.canChange(setting)) { enabled ->
                val request = feature.request(setting,enabled) ?: return@toggle
                val checkedSend: () -> Unit = checked@ {
                    val fresh = latest()?.takeIf { ready(it,address) } ?: return@checked
                    if (fresh.features.get<TozoDailyFeatureState>()?.supports(request) == true) send(request)
                }
                val message = when {
                    setting == TozoDailySetting.LDAC && enabled && request.expectedPeer == true ->
                        "开启 LDAC 会关闭双设备连接并重启耳机。是否继续？"
                    setting == TozoDailySetting.LDAC ->
                        "${if(enabled) "开启" else "关闭"} LDAC 会重启耳机。是否继续？"
                    setting == TozoDailySetting.MULTIPOINT && enabled && request.expectedPeer == true ->
                        "开启双设备连接会关闭 LDAC 并重启耳机。是否继续？"
                    else -> null
                }
                if (message == null) checkedSend() else confirm(message,checkedSend)
            })
        }
    }

    private val gestureLabels = mapOf(
        TozoTouchSlot.LEFT_SINGLE to "左耳单击", TozoTouchSlot.LEFT_DOUBLE to "左耳双击",
        TozoTouchSlot.LEFT_TRIPLE to "左耳三击", TozoTouchSlot.LEFT_HOLD to "左耳长按",
        TozoTouchSlot.RIGHT_SINGLE to "右耳单击", TozoTouchSlot.RIGHT_DOUBLE to "右耳双击",
        TozoTouchSlot.RIGHT_TRIPLE to "右耳三击", TozoTouchSlot.RIGHT_HOLD to "右耳长按",
    )
    private val actionLabels = mapOf(
        TozoTouchAction.NONE to "无操作", TozoTouchAction.PLAY_PAUSE to "播放/暂停",
        TozoTouchAction.NEXT to "下一曲", TozoTouchAction.PREVIOUS to "上一曲",
        TozoTouchAction.ASSISTANT to "语音助手", TozoTouchAction.VOLUME_UP to "音量增加",
        TozoTouchAction.VOLUME_DOWN to "音量降低", TozoTouchAction.NOISE_CYCLE to "切换噪声模式",
        TozoTouchAction.ANC_OFF_CYCLE to "降噪/关闭切换",
    )

    fun touch(host: SettingsPreferenceHost, state: EarbudState, address: String,
        add: (Any) -> Unit, latest: () -> EarbudState?, send: (ControlRequest) -> Unit) {
        if (!NativeHeadsetPageModel.tozo(state)) return
        val feature = state.features.get<TozoTouchFeatureState>()?.takeIf { it.valid } ?: return
        for (slot in TozoTouchSlot.entries) {
            val actions = feature.actions(slot)
            add(host.choice("hyperears.tozo.touch.${slot.name.lowercase()}", gestureLabels.getValue(slot),
                actions.map { actionLabels.getValue(it) }, actions.map { it.name },
                feature.action(slot)?.name, ready(state, address) && !feature.awaitingReadback) { selected ->
                val action = actions.find { it.name == selected } ?: return@choice
                val fresh = latest()?.takeIf { ready(it, address) } ?: return@choice
                val current = fresh.features.get<TozoTouchFeatureState>() ?: return@choice
                val request = TozoControlRequest.SetTouch(feature, slot, action)
                if (current.supports(request)) send(request)
            })
        }
    }

    fun firmware(host: SettingsPreferenceHost, state: EarbudState, add: (Any) -> Unit) {
        if (!NativeHeadsetPageModel.tozo(state)) return
        val feature = state.features.get<TozoNc20ProFeatureState>() ?: return
        val left = feature.firmwareLeft ?: return
        val right = feature.firmwareRight
        val row = host.row("hyperears.tozo.firmware", "固件版本")
        host.summary(row, if (right == null || left == right) left else "左耳 $left · 右耳 $right")
        host.enable(row, false)
        add(row)
    }
}
