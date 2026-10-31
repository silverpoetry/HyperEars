package dev.hyperears.hook
import dev.hyperears.integration.*

internal object SettingsSonyDevicesRows {
    fun add(host:SettingsPreferenceHost,state:EarbudState,address:String,add:(Any)->Unit,
        latest:()->EarbudState?,send:(ControlRequest)->Unit,confirm:((String,()->Unit)->Unit)?) {
        val feature=state.features.get<SonyDevicesFeatureState>()?.takeIf{it.hasSettings} ?: return
        fun dispatch(request:SonyControlRequest.ManageDevices,message:String?=null) {
            val operation:()->Unit={latest()?.let{SonyCardPolicy.request(it,address,request)}?.let(send);Unit}
            if(message!=null)confirm?.invoke(message,operation)else operation()
        }
        fun toggle(key:String,title:String,value:Boolean?,action:SonyDeviceAction,confirmation:String?=null) {
            if(value==null)return
            val request=SonyControlRequest.ManageDevices(feature,action,enabled=!value)
            add(host.toggle("hyperears.sony.devices.$key",title,value,
                (confirmation==null || confirm!=null) && SonyCardPolicy.request(state,address,request)!=null) {target->
                dispatch(request.copy(enabled=target),confirmation)
            })
        }
        toggle("pairing","配对新设备",feature.pairing,SonyDeviceAction.PAIRING,"更改耳机配对状态可能影响当前连接，是否继续？")
        toggle("source_keep","固定当前播放设备",feature.sourceKeep,SonyDeviceAction.SOURCE_KEEP)
        toggle("handover","允许其他设备接续播放",feature.musicHandover,SonyDeviceAction.MUSIC_HANDOVER)
        feature.devices.orEmpty().forEach { device ->
            val title=device.name.ifBlank{device.address}
            val actions=linkedMapOf(SonyDeviceAction.CONNECT to "连接",SonyDeviceAction.PLAYBACK_SOURCE to "切换到此设备播放",
                SonyDeviceAction.DISCONNECT to "断开连接",SonyDeviceAction.FORGET to "忘记设备")
                .filterKeys{SonyCardPolicy.request(state,address,SonyControlRequest.ManageDevices(feature,it,device.address))!=null}
            val row=host.choice("hyperears.sony.devices.peer.${device.address}",title,
                actions.values.toList(),actions.keys.map{it.name},null,
                confirm!=null && actions.isNotEmpty() && SonyCardPolicy.ready(state,address)) {raw->
                val action=actions.keys.singleOrNull{it.name==raw} ?: return@choice
                val message=when(action) {
                    SonyDeviceAction.FORGET->"从耳机中忘记“$title”？下次使用需要重新配对。"
                    SonyDeviceAction.DISCONNECT->"断开“$title”与耳机的连接？如果是本机，音频会断开。"
                    SonyDeviceAction.CONNECT->"让耳机连接“$title”？"
                    else->"切换到“$title”播放？"
                }
                dispatch(SonyControlRequest.ManageDevices(feature,action,device.address),message)
            }
            host.summary(row,when{device.order==0->"已配对";device.order==feature.playbackRight->"当前播放设备";else->"已连接"})
            add(row)
        }
    }
}
