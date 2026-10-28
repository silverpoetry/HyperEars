package dev.hyperears.hook
import dev.hyperears.integration.*
internal object SettingsSonyAssignableRows {
    fun title(id:Int)=when(id){0->"左侧操作预设";1->"右侧操作预设";2->"自定义按键预设";3->"C 按键预设";else->"降噪按键预设"}
    fun label(preset:Int)=when(preset){0->"环境声音控制";16->"音量控制";32->"播放控制";33->"切换曲目";34->"播放控制（语音助手受限）";48->"语音识别";49->"Google 助理";50->"Amazon Alexa";51->"腾讯小微";53->"环境声音与快速访问";54->"快速访问";255->"无功能";else->"耳机预设 $preset"}
    fun actionLabel(value:Int)=when(value){0->"单击";1->"双击";2->"三击";3->"连续点击";16->"单击并长按";17->"双击并长按";33->"按住以启动";34->"启动期间按住";else->"操作 $value"}
    fun functionLabel(value:Int)=when(value){0->"无功能";1->"降噪 / 环境声 / 关闭";2->"降噪 / 环境声";3->"降噪 / 关闭";4->"环境声 / 关闭";16->"快速注意";17->"降噪优化";32->"播放 / 暂停";33->"下一曲";34->"上一曲";35->"音量增加";36->"音量降低";48->"语音识别";49->"读取通知";50->"启动 Google 助理";51->"停止 Google 助理";52->"取消语音输入";53->"启动腾讯小微";54->"取消语音识别";55->"启动 Alexa 语音输入";56->"取消 Alexa";57->"取消腾讯小微";58->"下一曲并停止 Gemini Live";59->"上一曲并停止 Gemini Live";64->"启动 MLP";65->"与 MLP 对话";66->"Spotify 一触即听";67->"快速访问 1";68->"快速访问 2";69->"腾讯小微 / 取消";70->"Q MSC 一触即听";71->"Teams";72->"Teams 语音技能";80->"降噪 / NCSS / 环境声 / 关闭";81->"降噪 / NCSS / 环境声";82->"降噪 / NCSS / 关闭";83->"NCSS / 环境声 / 关闭";84->"降噪 / NCSS";85->"NCSS / 环境声";86->"NCSS / 关闭";87->"环境声设置";88->"标准人声";89->"聆听模式";112->"麦克风静音";113->"提高游戏音量";114->"提高聊天音量";else->"耳机功能 $value"}
    fun add(host:SettingsPreferenceHost,state:EarbudState,address:String,add:(Any)->Unit,latest:()->EarbudState?,send:(ControlRequest)->Unit) {
        if(!SonyCardPolicy.available(state,address))return
        val feature=state.features.get<SonyAssignableFeatureState>()?.takeIf{it.hasSettings}?:return
        val definition=feature.definition?:return
        val values=feature.current?:return
        definition.keys.forEachIndexed { index,key ->
            if(feature.completeMappings) {
                val mappings=feature.mappings!!
                key.actions[values[index]].orEmpty().filter{it.functions.size>1}.forEach { action ->
                    val selected=mappings[index].actions.single{it.action==action.id}.function
                    add(host.choice("hyperears.sony.assignable.${key.id}.action.${action.id}",title(key.id)+" · "+actionLabel(action.id),action.functions.map(::functionLabel),action.functions.map(Int::toString),selected.toString(),feature.available?.getOrNull(index)==true) { raw -> runCatching {
                        val value=raw.toIntOrNull()?:return@runCatching
                        val request=SonyControlRequest.SetAssignableAction(definition,values,mappings,index,action.id,value)
                        latest()?.let{SonyCardPolicy.request(it,address,request)}?.let(send)
                    } })
                }
            }
            add(host.choice("hyperears.sony.assignable.${key.id}",title(key.id),key.presets.map(::label),key.presets.map(Int::toString),values[index].toString(),feature.available?.getOrNull(index)==true) { raw -> runCatching {
                val value=raw.toIntOrNull()?:return@runCatching
                val request=SonyControlRequest.SetAssignablePreset(definition,values,index,value)
                latest()?.let {SonyCardPolicy.request(it,address,request)}?.let(send)
            } })
        }
    }
}
