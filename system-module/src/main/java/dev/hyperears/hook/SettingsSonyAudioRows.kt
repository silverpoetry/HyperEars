package dev.hyperears.hook

import dev.hyperears.integration.*

/** Native preference controls, with definition-checked requests and report-owned selection. */
internal object SettingsSonyAudioRows {
    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        latest: () -> EarbudState?, send: (ControlRequest) -> Unit) {
        val audio = state.features.get<SonyAudioSettingsFeatureState>() ?: return
        fun toggle(key: String, title: String, value: Boolean?, request: (Boolean) -> SonyControlRequest?) {
            if (value == null) return
            val allowed = request(!value)?.let { SonyCardPolicy.request(state, address, it) } != null
            add(host.toggle("hyperears.sony.$key", title, value, allowed) { target ->
                val current = latest() ?: return@toggle
                request(target)?.let { SonyCardPolicy.request(current, address, it) }?.let(send)
            })
        }
        toggle("dsee", "DSEE", audio.dsee) { value -> audio.dseeDefinition?.let { SonyControlRequest.SetDsee(it, value) } }
        toggle("speak_to_chat", "Speak-to-Chat", audio.speak) { value -> audio.speakDefinition?.let { SonyControlRequest.SetSpeakToChat(it, value) } }
        audio.speakParameters?.let { parameters ->
            val definition=audio.speakDefinition ?: return@let
            fun parameterChoice(key:String,title:String,options:Map<Int,String>,selected:Int,
                change:(Int)->SonySpeakParameters) {
                val probe=options.keys.firstOrNull {it!=selected} ?: return
                val writable=audio.speak==true && SonyCardPolicy.request(state,address,
                    SonyControlRequest.SetSpeakParameters(definition,parameters,change(probe)))!=null
                add(host.choice("hyperears.sony.$key",title,options.values.toList(),
                    options.keys.map(Int::toString),selected.toString(),writable) {raw->
                    val value=raw.toIntOrNull()?.takeIf(options::containsKey) ?: return@choice
                    val current=latest() ?: return@choice
                    if(current.features.get<SonyAudioSettingsFeatureState>()?.speak!=true)return@choice
                    SonyCardPolicy.request(current,address,
                        SonyControlRequest.SetSpeakParameters(definition,parameters,change(value)))?.let(send)
                })
            }
            parameterChoice("speak_sensitivity","说话检测灵敏度",linkedMapOf(0 to "自动",1 to "高",2 to "低"),parameters.sensitivity) {
                parameters.copy(sensitivity=it)
            }
            parameterChoice("speak_end","对话结束后恢复播放",linkedMapOf(0 to "较快",1 to "标准",2 to "较慢",3 to "不自动恢复"),parameters.modeOutTime) {
                parameters.copy(modeOutTime=it)
            }
        }
        audio.quality?.let { quality ->
            val options = linkedMapOf(0 to "音质优先", 1 to "连接稳定优先")
            if (quality == 2) options[2] = "低延迟（耳机回报）"
            val definition = audio.qualityDefinition
            val writable = definition != null && SonyCardPolicy.request(state, address, SonyControlRequest.SetConnectionQuality(definition, 0)) != null
            add(host.choice("hyperears.sony.connection_quality", "连接质量", options.values.toList(),
                options.keys.map(Int::toString), quality.toString(), writable) { target ->
                val mode = target.toIntOrNull()?.takeIf { it in 0..1 } ?: return@choice
                val current = latest() ?: return@choice
                definition?.let { SonyCardPolicy.request(current, address, SonyControlRequest.SetConnectionQuality(it, mode)) }?.let(send)
            })
        }
    }
}
