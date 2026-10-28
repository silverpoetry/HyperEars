package dev.hyperears.hook
import dev.hyperears.integration.*
internal object HuaweiPairedLabels {
    fun action(action:HuaweiPairedAction)=when(action){HuaweiPairedAction.PREFER->"设为首选设备";HuaweiPairedAction.CONNECT->"连接";HuaweiPairedAction.DISCONNECT->"断开连接";HuaweiPairedAction.FORGET->"忘记设备";HuaweiPairedAction.ENABLE_AUTO->"开启自动连接";HuaweiPairedAction.DISABLE_AUTO->"关闭自动连接"}
    fun status(row:HuaweiPairedDevice)=listOf(if(row.playing)"正在播放"else if(row.connected)"已连接"else"未连接",if(row.preferred)"首选设备"else"非首选设备").joinToString(" · ")
    fun destructive(action:HuaweiPairedAction)=action in setOf(HuaweiPairedAction.DISCONNECT,HuaweiPairedAction.FORGET)
}
internal object SettingsHuaweiPairedRows {
    fun add(host:SettingsPreferenceHost,state:EarbudState,address:String,add:(Any)->Unit,latest:()->EarbudState?,send:(ControlRequest)->Unit,confirm:(String,()->Unit)->Unit) {
        if(!HuaweiDailySettingsPolicy.available(state,address))return
        val snapshot=state.features.get<HuaweiDailySettingsFeatureState>()?.pairedDevices?:return
        snapshot.devices.forEach { device ->
            val row=host.row("hyperears.huawei.device.${device.mac}",device.name.ifBlank{device.mac})
            host.summary(row,HuaweiPairedLabels.status(device));add(row)
            HuaweiPairedAction.entries.filter{snapshot.supports(device.mac,it)}.forEach { action ->
                val request=HuaweiDailyControlRequest.ManageDevice(snapshot,device.mac,action)
                val control=host.row("hyperears.huawei.device.${device.mac}.${action.name}",HuaweiPairedLabels.action(action))
                host.onClick(control) { runCatching {
                    if(latest()?.let{HuaweiDailySettingsPolicy.request(it,address,request)}==null)return@runCatching
                    if(HuaweiPairedLabels.destructive(action)){var consumed=false;confirm("${HuaweiPairedLabels.action(action)}：${device.name.ifBlank{device.mac}}？") {
                        if(!consumed){consumed=true;latest()?.let{HuaweiDailySettingsPolicy.request(it,address,request)}?.let(send)}
                    }} else latest()?.let{HuaweiDailySettingsPolicy.request(it,address,request)}?.let(send)
                } };add(control)
            }
        }
    }
}
