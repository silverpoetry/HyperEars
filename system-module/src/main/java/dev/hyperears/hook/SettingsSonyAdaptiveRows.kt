package dev.hyperears.hook
import dev.hyperears.integration.*

internal object SettingsSonyAdaptiveRows {
    fun add(host:SettingsPreferenceHost,state:EarbudState,address:String,add:(Any)->Unit,
        latest:()->EarbudState?,send:(ControlRequest)->Unit) {
        val feature=state.features.get<SonyAmbientFeatureState>() ?: return
        val sensitivity=feature.adaptiveSensitivity ?: return
        val snapshot=feature.parameterSnapshot
        if(snapshot.size!=8 || snapshot.first()!=0x19)return
        fun request(enabled:Boolean,value:Int)=SonyControlRequest.SetAdaptiveAmbient(snapshot,enabled,value)
        fun dispatch(command:SonyControlRequest.SetAdaptiveAmbient) {
            latest()?.let{SonyCardPolicy.request(it,address,command)}?.let(send)
        }
        val toggle=host.toggle("hyperears.sony.auto_ambient","自动环境声",feature.adaptive,
            SonyCardPolicy.request(state,address,request(!feature.adaptive,sensitivity))!=null) { enabled->
            dispatch(request(enabled,sensitivity))
        }
        host.summary(toggle,"调整此项会切换到环境声模式；开启后由耳机自动调整强度。")
        add(toggle)
        add(host.choice("hyperears.sony.auto_ambient_sensitivity","自动环境声灵敏度",
            listOf("标准","高","低"),listOf("0","1","2"),sensitivity.toString(),
            feature.adaptive && SonyCardPolicy.ready(state,address)) {raw->
            val value=raw.toIntOrNull()?.takeIf{it in 0..2} ?: return@choice
            if(latest()?.features?.get<SonyAmbientFeatureState>()?.adaptive==true)dispatch(request(true,value))
        })
    }
}
