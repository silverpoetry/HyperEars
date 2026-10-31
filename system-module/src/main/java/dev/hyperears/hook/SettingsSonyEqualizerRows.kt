package dev.hyperears.hook

import dev.hyperears.integration.*

/** Remaining finite controls reuse model contracts; EQ curves and paired edits stay in details. */
internal object SettingsSonyEqualizerRows {
    fun add(host: SettingsPreferenceHost,state: EarbudState,address: String,add: (Any)->Unit,current: ()->EarbudState?,send: (ControlRequest)->Unit) {
        val ready = state.sessionActive && state.privateChannelConnected && state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE
        fun range(key:String,title:String,values:IntRange,value:Int,enabled:Boolean = true,request:(EarbudState,Int)->ControlRequest?) {
            add(host.range("hyperears.$key",title,values,value,ready && enabled) { next -> runCatching { current()?.let { request(it,next) }?.let(send) } })
        }
        fun choice(key:String,title:String,values:Map<Int,String>,value:Int,enabled:Boolean = true,request:(EarbudState,Int)->ControlRequest?) {
            add(host.choice("hyperears.$key",title,values.values.toList(),values.keys.map(Int::toString),value.toString(),ready && enabled) { next -> runCatching {
                val number=next.toIntOrNull() ?: return@runCatching
                current()?.let { request(it,number) }?.let(send)
            } })
        }
        fun toggle(key:String,title:String,value:Boolean,enabled:Boolean = true,request:(EarbudState,Boolean)->ControlRequest?) {
            add(host.toggle("hyperears.$key",title,value,ready && enabled) { next -> runCatching { current()?.let { request(it,next) }?.let(send) } })
        }
        state.features.get<SonyEqFeatureState>()?.let { feature ->
            fun qualified(latest:EarbudState)=latest.features.get<SonyEqFeatureState>()?.let { it.definition == feature.definition && it.version == feature.version && it.parameterType == feature.parameterType } == true
            feature.preset?.let { preset -> choice("sony.preset","均衡器预设",linkedMapOf(0 to "关闭",16 to "明亮",17 to "激昂",18 to "醇厚",19 to "放松",20 to "人声",21 to "高音增强",22 to "低音增强",23 to "语音",160 to "自定义",161 to "用户 1",162 to "用户 2"),preset,feature.writablePreset) { latest,value ->
                if(qualified(latest)) SonyCardPolicy.request(latest,address,SonyControlRequest.SetEqPreset(value)) else null
            } }
            feature.clearBass?.let { bass -> range("sony.bass","Clear Bass",-10..10,bass,feature.writableBass) { latest,value ->
                if(qualified(latest)) SonyCardPolicy.request(latest,address,SonyControlRequest.SetClearBass(value)) else null
            } }
        }
        state.features.get<SonyAmbientFeatureState>()?.takeIf { it.level in 1..20 }?.let { feature ->
            range("sony.ambient","环境声强度",1..20,feature.level,!feature.adaptive) { latest,value ->
                val fresh=latest.features.get<SonyAmbientFeatureState>()
                if(fresh != null && fresh.voiceWritable == feature.voiceWritable) SonyCardPolicy.request(latest,address,SonyControlRequest.SetAmbient(value,fresh.voice)) else null
            }
            if(feature.voiceWritable) toggle("sony.ambientVoice","环境声人声模式",feature.voice,!feature.adaptive) { latest,value ->
                val fresh=latest.features.get<SonyAmbientFeatureState>()
                if(fresh?.voiceWritable == true) SonyCardPolicy.request(latest,address,SonyControlRequest.SetAmbient(fresh.level,value)) else null
            }
        }
    }
}
