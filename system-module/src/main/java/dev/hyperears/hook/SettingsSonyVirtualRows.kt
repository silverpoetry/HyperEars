package dev.hyperears.hook
import dev.hyperears.integration.*
internal object SonyVirtualSoundLabels {
    fun title(type:Int)=if(type==1)"环绕声（VPT）"else"声场位置"
    fun choices(type:Int):Map<Int,String> = if(type==1)linkedMapOf(0 to "关闭",1 to "户外舞台",2 to "竞技场",3 to "音乐厅",4 to "俱乐部")
        else linkedMapOf(0 to "关闭",1 to "左前方",2 to "右前方",3 to "正前方",17 to "左后方",18 to "右后方")
}
internal object SettingsSonyVirtualRows {
    fun add(host:SettingsPreferenceHost,state:EarbudState,address:String,add:(Any)->Unit,latest:()->EarbudState?,send:(ControlRequest)->Unit) {
        if(!SonyCardPolicy.available(state,address))return
        val feature=state.features.get<SonyVirtualSoundFeatureState>()?:return
        val definition=feature.definition?:return
        if(!feature.hasSettings)return
        definition.types.forEach{ type ->
            val choices=SonyVirtualSoundLabels.choices(type)
            add(host.choice("hyperears.sony.virtual.$type",SonyVirtualSoundLabels.title(type),choices.values.toList(),choices.keys.map(Int::toString),
                feature.values[type].toString(),!feature.awaitingReadback) { raw -> runCatching {
                val value=raw.toIntOrNull()?:return@runCatching
                latest()?.let{SonyCardPolicy.request(it,address,SonyControlRequest.SetVirtualSound(definition,feature.values,type,value))}?.let(send)
            } })
        }
        if(feature.awaitingReadback)add(host.row("hyperears.sony.virtual.refresh","等待耳机回报 · 重新读取声场设置").also{row ->
            host.onClick(row){runCatching {
                val current=latest()?:return@runCatching
                if(SonyCardPolicy.ready(current,address) && current.features.get<SonyVirtualSoundFeatureState>()?.definition==definition)send(StandardControlRequest.Refresh)
            }}
        })
    }
}
