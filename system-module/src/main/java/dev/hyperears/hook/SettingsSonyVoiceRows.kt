package dev.hyperears.hook
import dev.hyperears.integration.*
internal object SettingsSonyVoiceRows {
    fun add(host:SettingsPreferenceHost,state:EarbudState,address:String,add:(Any)->Unit,latest:()->EarbudState?,send:(ControlRequest)->Unit) {
        if(!SonyCardPolicy.available(state,address))return
        val feature=state.features.get<SonyVoiceGuidanceFeatureState>()?:return
        val definition=feature.definition?:return
        val value=feature.enabled?:return
        if(!feature.hasSettings)return
        add(host.toggle("hyperears.sony.voice_guidance","耳机语音提示",value,true) { next -> runCatching {
            latest()?.let{SonyCardPolicy.request(it,address,SonyControlRequest.SetVoiceGuidance(definition,value,next))}?.let(send)
        } })
    }
}
