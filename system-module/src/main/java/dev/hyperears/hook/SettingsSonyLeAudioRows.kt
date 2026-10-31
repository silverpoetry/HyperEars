package dev.hyperears.hook

import dev.hyperears.integration.*

/** Headset connection mode and device prompts; phone policy remains in native audio settings. */
internal object SettingsSonyLeAudioRows {
    private val limitations = listOf("均衡器", "DSEE", "智能免摘", "自动音量", "语音激活", "应用控制连接",
        "LDAC", "音质优先", "Google 助理", "语音助手", "固件更新", "双设备连接", "唤醒词", "背景音乐模式",
        "电池保护", "头部追踪", "LE Audio", "沉浸音频", "自动切换连接", "自动播放", "降噪", "Sound AR",
        "语音操作", "快捷访问", "连接模式", "自动播放")

    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        latest: () -> EarbudState?, send: (ControlRequest) -> Unit,
        openBluetooth: (()->Unit)? = null,
        confirm: ((String,()->Unit)->Unit)? = null) {
        val feature = state.features.get<SonyLeAudioFeatureState>() ?: return
        val operation=state.features.get<SonyLeAudioOperation>()
        val identity=feature.identity
        val enabled=feature.enabled
        if(identity!=null && enabled!=null && confirm!=null) {
            val request=SonyControlRequest.SetLeAudio(identity,enabled,!enabled)
            val row=host.toggle("hyperears.sony.le_audio.mode","耳机 LE Audio 模式",
                if(operation?.pending==true)operation.targetEnabled else enabled,
                operation?.pending!=true && SonyCardPolicy.request(state,address,request)!=null) {target->
                val command=request.copy(enabled=target)
                if(latest()?.let {SonyCardPolicy.request(it,address,command)}!=null)
                    confirm("更改连接方式可能暂时断开耳机。若未自动重连，请在系统蓝牙列表连接耳机；若尚未配对 LE 设备，请将耳机置于配对模式后添加。") {
                        latest()?.takeIf {it.features.get<SonyLeAudioOperation>()?.pending!=true}
                            ?.let {SonyCardPolicy.request(it,address,command)}?.let(send)
                    }
            }
            val summary=when(operation?.phase) {
                SonyLeAudioPhase.WAITING_PROFILE->"正在检查手机的 LE Audio 服务"
                SonyLeAudioPhase.WAITING_HISTORY->"正在查询耳机的配对状态"
                SonyLeAudioPhase.WAITING_CONFIRMATION->"等待耳机确认；如有连接提示，请在下方选择"
                SonyLeAudioPhase.SETTING_CONFIRMED->if(operation.pairingRequired)"耳机已确认设置，请在系统蓝牙列表完成 LE 配对与连接" else "耳机已确认设置；请检查系统蓝牙连接状态"
                SonyLeAudioPhase.FAILED->when(operation.message) {
                    "LE Audio profile unavailable"->"手机的 LE Audio 服务尚未就绪，请稍后重试"
                    "Timed out waiting for device confirmation"->"等待耳机确认超时，请重试"
                    else->operation.message.ifBlank {"切换未完成，请重试"}
                }
                null->"选择耳机的蓝牙连接方式"
            }
            host.summary(row,summary);add(row)
        }
        if(openBluetooth!=null && operation?.phase in setOf(SonyLeAudioPhase.SETTING_CONFIRMED,SonyLeAudioPhase.FAILED)) {
            val row=host.row("hyperears.sony.le_audio.connection","查看蓝牙连接")
            host.summary(row,if(operation?.pairingRequired==true)"打开系统蓝牙列表，配对并连接 LE 耳机" else "打开系统蓝牙列表，检查耳机是否已连接")
            host.onClick(row) {openBluetooth()}
            add(row)
        }
        val alert = feature.alert ?: return
        val request = SonyControlRequest.ReplyLeAudioAlert(feature.alertSequence,alert,true)
        val message = buildString {
            append("耳机请求更改蓝牙连接方式，可能需要重新连接。")
            if (alert.items.isNotEmpty()) {
                append("受限功能：")
                append(alert.items.distinct().joinToString("、") { limitations.getOrNull(it) ?: "其他耳机功能" })
            }
        }
        val row = host.choice("hyperears.sony.le_audio.confirm", "LE Audio 连接确认",
            listOf("确认继续", "取消变更"),listOf("confirm","cancel"),null,
            SonyCardPolicy.request(state,address,request) != null) { selected ->
            if (selected == "confirm" || selected == "cancel") {
                latest()?.let { SonyCardPolicy.request(it,address,request.copy(confirm=selected == "confirm")) }?.let(send)
            }
        }
        host.summary(row,message)
        add(row)
    }
}
