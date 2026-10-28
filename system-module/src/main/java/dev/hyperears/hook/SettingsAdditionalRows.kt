package dev.hyperears.hook

import dev.hyperears.integration.*

/** Remaining finite controls reuse model contracts; EQ curves and paired edits stay in details. */
internal object SettingsAdditionalRows {
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
        state.features.get<OppoSettingsFeatureState>()?.let { feature ->
            feature.promptVolume?.let { range("oppo.prompt","提示音音量",1..10,it) { latest,value -> OppoCardPolicy.request(latest,address,OppoControlRequest.SetPromptVolume(value)) } }
            feature.spatialProductId?.let { id -> feature.spatialSound?.let { value -> toggle("oppo.spatial","空间音效",value) { latest,next -> OppoCardPolicy.request(latest,address,OppoControlRequest.SetSpatialSound(next,id)) } } }
        }
        state.features.get<HuaweiAncLevelFeatureState>()?.takeIf { it.hasCurrentControls(state.noiseMode) }?.let { feature ->
            val levels=feature.supported.filter { it.domainNoiseMode() == state.noiseMode }
            val labels=mapOf(HuaweiAncLevel.NORMAL to "均衡",HuaweiAncLevel.COMFORT to "轻度",HuaweiAncLevel.ULTRA to "深度",HuaweiAncLevel.DYNAMIC to "动态",HuaweiAncLevel.VOICE_BOOST to "人声增强",HuaweiAncLevel.TRANS_NORMAL to "标准通透")
            choice("huawei.level","噪声强度",levels.associate { it.ordinal to labels.getValue(it) },requireNotNull(feature.current).ordinal) { latest,value ->
                val fresh=latest.features.get<HuaweiAncLevelFeatureState>()
                val level=levels.singleOrNull { it.ordinal == value }
                if(level != null && fresh?.hasCurrentControls(latest.noiseMode) == true && fresh.supported == feature.supported && latest.noiseMode == state.noiseMode && level != fresh.current)
                    HuaweiControlRequest.SetAncLevel(level) else null
            }
        }
        state.features.get<SamsungDailySettingsFeatureState>()?.let { feature ->
            fun qualified(latest:EarbudState)=latest.features.get<SamsungDailySettingsFeatureState>()?.let { it.model == feature.model && it.revision == feature.revision } == true
            feature.conversationTimeoutSeconds?.let { value ->
                choice("samsung.timeout","语音检测结束等待时间",mapOf(5 to "5 秒",10 to "10 秒",15 to "15 秒"),value,
                    feature.supports(SamsungDailyControlRequest.SetConversationTimeout(value))) { latest,next ->
                    if(qualified(latest)) SamsungDailySettingsPolicy.request(latest,address,SamsungDailyControlRequest.SetConversationTimeout(next)) else null
                }
            }
            if(feature.equalizerEnabled != null && feature.equalizerPreset != null) {
                val selected=if(feature.equalizerEnabled == false) 0 else requireNotNull(feature.equalizerPreset) + 1
                choice("samsung.eq","音效",mapOf(0 to "正常",1 to "低音增强",2 to "柔和",3 to "动感",4 to "清晰",5 to "高音增强"),selected) { latest,value ->
                    if(qualified(latest)) SamsungDailySettingsPolicy.request(latest,address,SamsungDailyControlRequest.SetEqualizer(value != 0,(value-1).coerceAtLeast(0))) else null
                }
            }
            val options:List<Triple<String,String,(Boolean)->SamsungControlRequest>> = listOf(
                Triple("onebud","单耳噪声控制",SamsungControlRequest::SetNoiseControlsWithOneEarbud),
                Triple("seamless","无缝连接",SamsungControlRequest::SetSeamlessConnection),
                Triple("sidetone","通话侧音",SamsungControlRequest::SetSidetone),
                Triple("extraambient","额外通透音量",SamsungControlRequest::SetExtraHighAmbient))
            options.forEach { (key,label,request) -> SamsungDailySettingsPolicy.optionalValue(state,request(false))?.let { value ->
                toggle("samsung.$key",label,value) { latest,next -> if(qualified(latest)) SamsungDailySettingsPolicy.request(latest,address,request(next)) else null }
            } }
            state.features.get<SamsungBudsSettingsFeatureState>()?.takeIf { it.voiceDetectSupported }?.let { settings ->
                toggle("samsung.voiceDetect","语音检测",settings.voiceDetectEnabled) { latest,next ->
                    if(qualified(latest) && latest.features.get<SamsungBudsSettingsFeatureState>()?.voiceDetectSupported == true) SamsungControlRequest.SetVoiceDetect(next) else null
                }
            }
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
