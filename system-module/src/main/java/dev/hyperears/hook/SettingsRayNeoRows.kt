package dev.hyperears.hook

import dev.hyperears.integration.*

/** Native preferences only; device I/O and launch completion stay in the session. */
internal object SettingsRayNeoRows {
    fun ready(state: EarbudState, address: String) = NativeHeadsetPageModel.rayneo(state) &&
        state.address.equals(address, true) && state.sessionActive && state.privateChannelConnected &&
        state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE

    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String,
        add: (Any) -> Unit, latest: () -> EarbudState?, send: (ControlRequest) -> Unit,
        countryCode: String = "", password: (String, (String) -> Unit) -> Unit,
        levelProjection: (RayNeoLevelSetting, Int) -> Pair<Int, Boolean> = { _, value -> value to true },
        submitLevel: ((RayNeoLevelSetting, Int, Int, ControlRequest) -> Boolean)? = null) {
        if (!NativeHeadsetPageModel.rayneo(state)) return
        state.features.get<RayNeoSettingsFeatureState>()?.takeIf { it.verifiedX3Pro }?.let { settings ->
            fun submit(request: ControlRequest) {
                val fresh = latest()?.takeIf { ready(it,address) } ?: return
                if(fresh.features.get<RayNeoSettingsFeatureState>()?.supports(request) == true) send(request)
            }
            for(setting in RayNeoLevelSetting.entries) {
                val reported = setting.value(settings) ?: continue
                val projected = levelProjection(setting,setting.displayValue(reported))
                val enabled = ready(state,address) && projected.second
                val row = host.range("hyperears.rayneo.level.${setting.name.lowercase()}",setting.label,setting.range,
                    projected.first,enabled,awaitReadback=true) { value ->
                    val fresh = latest()?.takeIf { ready(it,address) } ?: return@range false
                    val request = RayNeoControlRequest.SetLevel(setting,reported,setting.wireValue(value))
                    if(fresh.features.get<RayNeoSettingsFeatureState>()?.supports(request) != true || value == projected.first) return@range false
                    submitLevel?.invoke(setting,projected.first,value,request) ?: false
                }
                if(setting == RayNeoLevelSetting.BRIGHTNESS) settings.automaticBrightness?.let { automatic ->
                    host.linkAutomaticBrightness(row,automatic,ready(state,address)) {
                        submit(RayNeoControlRequest.SetAutomaticBrightness(automatic,it))
                    }
                }
                add(row)
            }
            settings.wifiEnabled?.let { enabled ->
                add(host.toggle("hyperears.rayneo.wifi", "眼镜 Wi-Fi", enabled, ready(state,address)) {
                    submit(RayNeoControlRequest.SetWifi(enabled,it,countryCode))
                })
            }
            if(settings.wifiEnabled == true) {
                val scan = host.row("hyperears.rayneo.wifi.scan","刷新可用网络")
                host.enable(scan,ready(state,address))
                host.onClick(scan) {
                    val fresh = latest()?.takeIf { ready(it,address) } ?: return@onClick
                    if(fresh.features.get<RayNeoSettingsFeatureState>()?.wifiEnabled == true) send(RayNeoControlRequest.ScanWifi(countryCode))
                }
                add(scan)
                val wifi = state.features.get<RayNeoWifiFeatureState>()
                val networks = wifi?.networks.orEmpty().sortedByDescending { it.level }
                val choice = host.choice("hyperears.rayneo.wifi.network","连接网络",
                    networks.map { it.ssid + if(it.saved) "（已保存）" else if(!it.canConnect) "（暂不支持）" else "" },
                    networks.map { it.selectionKey }, null, ready(state,address) && networks.isNotEmpty()) { key ->
                    val network = networks.find { it.selectionKey == key && it.canConnect } ?: return@choice
                    fun connect(secret: String?) {
                        val fresh = latest()?.takeIf { ready(it,address) } ?: return
                        val request = RayNeoControlRequest.ConnectWifi(network,secret)
                        if(fresh.features.get<RayNeoSettingsFeatureState>()?.wifiEnabled == true &&
                            fresh.features.get<RayNeoWifiFeatureState>()?.supports(request) == true) send(request)
                    }
                    if(network.requiresPassword) password(network.ssid) { secret -> connect(secret) } else connect(null)
                }
                host.summary(choice, when(wifi?.connectionState) {
                    "CONNECTED" -> "已连接：${wifi.ssid}"
                    "CONNECTING" -> "正在连接：${wifi.ssid}"
                    "DISCONNECTED" -> "已断开"
                    else -> if(networks.isEmpty()) "请先刷新可用网络" else "选择眼镜要连接的网络"
                })
                add(choice)
            }
            settings.deepSuspend?.let { enabled ->
                add(host.toggle("hyperears.rayneo.sleep", "允许自动休眠", enabled, ready(state,address)) {
                    submit(RayNeoControlRequest.SetDeepSuspend(enabled,it))
                })
            }
            settings.autoLockSeconds?.let { seconds ->
                val times = listOf(-1,15,30,45,60)
                add(host.choice("hyperears.rayneo.sleep.time", "自动休眠时间",
                    times.map { if(it == -1) "永不" else "$it 秒" }, times.map(Int::toString), seconds.toString(),
                    ready(state,address) && settings.deepSuspend == true) { value ->
                    value.toIntOrNull()?.takeIf { it in times }?.let { submit(RayNeoControlRequest.SetAutoLock(seconds,it)) }
                })
            }
            val capture = state.features.get<RayNeoCaptureFeatureState>()
            if(settings.supportMixShot) {
                val row = host.row("hyperears.rayneo.shot","融合截屏")
                host.summary(row,capture?.shotResult?.let { if(it == 0) "截屏已保存在眼镜" else shotError(it) } ?: "截取画面并保存在眼镜")
                host.enable(row,ready(state,address) && capture?.recording != RayNeoRecordingState.COUNTDOWN)
                host.onClick(row) { submit(RayNeoControlRequest.CaptureShot) }
                add(row)
            }
            if(settings.supportMixRecord) {
                val row = host.row("hyperears.rayneo.record.start","开始融合录屏")
                host.summary(row,when(capture?.recording) {
                    RayNeoRecordingState.COUNTDOWN -> "正在倒计时"
                    RayNeoRecordingState.RECORDING -> "正在录制，视频保存在眼镜"
                    RayNeoRecordingState.STOPPED -> "录屏已完成，视频保存在眼镜"
                    null -> capture?.recordResult?.let(::recordError) ?: "录制结果保存在眼镜"
                })
                host.enable(row,ready(state,address) && capture?.recording !in setOf(RayNeoRecordingState.COUNTDOWN,RayNeoRecordingState.RECORDING))
                host.onClick(row) { submit(RayNeoControlRequest.SetRecording(true)) }
                add(row)
                val stop = host.row("hyperears.rayneo.record.stop","停止融合录屏")
                host.enable(stop,ready(state,address) && capture?.recording != RayNeoRecordingState.STOPPED)
                host.onClick(stop) { submit(RayNeoControlRequest.SetRecording(false)) }
                add(stop)
            }
        }
    }
    private fun shotError(code: Int) = when(code) {
        100 -> "相机正在使用，截屏失败"
        101 -> "眼镜屏幕已关闭"
        103 -> "存储空间不足"
        104 -> "眼镜温度过低"
        105 -> "眼镜温度过高"
        106 -> "眼镜电量不足"
        else -> "截屏失败"
    }
    private fun recordError(code: Int) = when(code) {
        100,106 -> "相机正在使用，录屏失败"
        101 -> "存储空间不足"
        102 -> "眼镜温度过高"
        103 -> "眼镜温度过低"
        104 -> "眼镜电量不足"
        105 -> "眼镜屏幕已关闭"
        else -> "录屏状态未确认，请尝试停止录屏"
    }
}
