package dev.hyperears.hook
import dev.hyperears.integration.*
internal object SettingsBoseButtonRows {
    fun title(value:BoseButtonSetting):String {
        val button=when(value.button){0->"降噪按钮";2->"语音助手按钮";3->"右侧快捷键";4->"左侧快捷键";16->"Action 按钮";128->"快捷键";else->"按钮 ${value.button}"}
        val event=when(value.event){1->"按下";2->"松开";3->"短按";4->"单击";5->"按住";6->"双击";7->"双击并按住";8->"三击";9->"长按";10->"超长按";11->"更长按";else->"最长按"}
        return "$button · $event"
    }
    fun label(action:Int)=when(action){0->"未配置";1->"语音助手";2->"降噪控制";3->"电量播报";4->"播放 / 暂停";5->"增加降噪强度";6->"降低降噪强度";7->"切换唤醒词";8->"切换设备";9->"对话模式";10->"下一曲";11->"上一曲";12->"读取通知";13->"风噪模式";14->"禁用";15->"应用交互";16->"Spotify Go";17->"循环声音模式";19->"空间音频模式";20->"切换线路输入";21->"连接设备";else->"耳机功能 $action"}
    fun add(host:SettingsPreferenceHost,state:EarbudState,address:String,add:(Any)->Unit,latest:()->EarbudState?,send:(ControlRequest)->Unit) {
        if(!BoseDailyCardPolicy.available(state,address))return
        state.features.get<BoseDailySettingsFeatureState>()?.buttons.orEmpty().forEach { button ->
            val choices=button.actions
            val title=title(button)
            val row=host.choice("hyperears.bose.button.${button.button}.${button.event}",title,choices.map(::label),choices.map(Int::toString),button.current.toString(),choices.isNotEmpty()) { raw -> runCatching {
                val action=raw.toIntOrNull()?:return@runCatching
                latest()?.let{BoseDailyCardPolicy.request(it,address,BoseDailyControlRequest.SetButton(button,action))}?.let(send)
            } }
            if(button.current !in choices)host.summary(row,"当前：${label(button.current)}（耳机回报）")
            add(row)
        }
    }
}
