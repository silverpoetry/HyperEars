package dev.hyperears.hook
import dev.hyperears.integration.*
internal object SettingsBoseAudioRows {
    val spatialLabels=listOf("关闭","固定声场","头部跟踪")
    fun add(host:SettingsPreferenceHost,state:EarbudState,address:String,add:(Any)->Unit,latest:()->EarbudState?,send:(ControlRequest)->Unit) {
        if(!BoseDailyCardPolicy.available(state,address))return
        val settings=state.features.get<BoseDailySettingsFeatureState>()?:return
        settings.liveAudio?.let { snapshot ->
            if(dev.hyperears.protocol.bose.BoseDailySettingsCodec.validAudioSettings(snapshot.raw)) {
                add(host.choice("hyperears.bose.spatial","沉浸式音频",spatialLabels,listOf("0","1","2"),snapshot.raw[2].toString(),true) { raw -> runCatching {
                    val mode=raw.toIntOrNull()?:return@runCatching
                    latest()?.let{BoseDailyCardPolicy.request(it,address,BoseDailyControlRequest.SetSpatial(snapshot,mode))}?.let(send)
                } })
                add(host.toggle("hyperears.bose.wind","抗风噪",snapshot.raw[3]==1,true) { enabled -> runCatching {
                    latest()?.let{BoseDailyCardPolicy.request(it,address,BoseDailyControlRequest.SetWind(snapshot,enabled))}?.let(send)
                } })
                add(host.row("hyperears.bose.audio_refresh","重新读取耳机设置").also { row ->
                    host.onClick(row) {runCatching {
                        val fresh=latest()?:return@runCatching
                        if(BoseDailyCardPolicy.available(fresh,address) && fresh.features.get<BoseDailySettingsFeatureState>()?.liveAudio?.productId==snapshot.productId)
                            send(StandardControlRequest.Refresh)
                    }}
                })
            }
        }
        settings.deviceName?.let { name ->
            add(host.textInput("hyperears.bose.device_name","耳机内保存的名称",name.name,true,
                {dev.hyperears.protocol.bose.BoseDailySettingsCodec.encodeDeviceName(it)!=null},
                "请输入有效名称，长度不超过 31 个 UTF-8 字节。") { value -> runCatching {
                    latest()?.let{BoseDailyCardPolicy.request(it,address,BoseDailyControlRequest.Rename(name,value))}?.let(send)
                } })
        }
    }
}
