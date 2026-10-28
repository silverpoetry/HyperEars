package dev.hyperears.hook
import dev.hyperears.integration.*
internal object SettingsSonyAssignableRows {
    fun title(id:Int)=when(id){0->"左侧操作预设";1->"右侧操作预设";2->"自定义按键预设";3->"C 按键预设";else->"降噪按键预设"}
    fun label(preset:Int)=when(preset){0->"环境声音控制";16->"音量控制";32->"播放控制";33->"切换曲目";34->"播放控制（语音助手受限）";48->"语音识别";49->"Google 助理";50->"Amazon Alexa";51->"腾讯小微";53->"环境声音与快速访问";54->"快速访问";255->"无功能";else->"耳机预设 $preset"}
    fun add(host:SettingsPreferenceHost,state:EarbudState,address:String,add:(Any)->Unit,latest:()->EarbudState?,send:(ControlRequest)->Unit) {
        if(!SonyCardPolicy.available(state,address))return
        val feature=state.features.get<SonyAssignableFeatureState>()?.takeIf{it.hasSettings}?:return
        val definition=feature.definition?:return
        val values=feature.current?:return
        definition.keys.forEachIndexed { index,key ->
            add(host.choice("hyperears.sony.assignable.${key.id}",title(key.id),key.presets.map(::label),key.presets.map(Int::toString),values[index].toString(),feature.available?.getOrNull(index)==true) { raw -> runCatching {
                val value=raw.toIntOrNull()?:return@runCatching
                val request=SonyControlRequest.SetAssignablePreset(definition,values,index,value)
                latest()?.let {SonyCardPolicy.request(it,address,request)}?.let(send)
            } })
        }
    }
}
