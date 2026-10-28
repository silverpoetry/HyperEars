package dev.hyperears.hook

import dev.hyperears.integration.*

/** Native preference composition only; each model policy still owns request admission. */
internal object SettingsVendorRows {
    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        current: () -> EarbudState?, send: (ControlRequest) -> Unit) {
        fun choice(key: String, title: String, names: Map<Int,String>, selected: Int, enabled: Boolean = true, request: (EarbudState,Int) -> ControlRequest?) {
            add(host.choice("hyperears.$key", title, names.values.toList(), names.keys.map(Int::toString), selected.toString(),
                enabled && state.sessionActive && state.privateChannelConnected && state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE) { value -> runCatching {
                val latest = current() ?: return@runCatching
                val number = value.toIntOrNull()?.takeIf(names::containsKey) ?: return@runCatching
                request(latest, number)?.let(send)
            } })
        }
        fun toggle(key: String, title: String, selected: Boolean, enabled: Boolean = true, request: (EarbudState,Boolean) -> ControlRequest?) {
            add(host.toggle("hyperears.$key", title, selected,
                enabled && state.sessionActive && state.privateChannelConnected && state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE) { value -> runCatching {
                val latest = current() ?: return@runCatching
                request(latest, value)?.let(send)
            } })
        }
        HuaweiDailyChoices.rows(state).forEach { row ->
            if (row.choices.isEmpty() && row.currentLabel == null) return@forEach
            val preference = host.choice("hyperears.huawei.${row.key}", row.label,
                row.choices.map { it.label }, row.choices.map { it.value.toString() }, row.selected.toString(),
                row.choices.isNotEmpty() && state.sessionActive && state.privateChannelConnected &&
                    state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE) { value -> runCatching {
                val latest = current() ?: return@runCatching
                val number = value.toIntOrNull() ?: return@runCatching
                val fresh = HuaweiDailyChoices.rows(latest).singleOrNull { it.key == row.key }
                val request = fresh?.takeIf { it.choices == row.choices }?.choices?.singleOrNull { it.value == number }?.request
                request?.let { HuaweiDailySettingsPolicy.request(latest, address, it) }?.let(send)
            } }
            if (row.currentLabel != null && (row.choices.isEmpty() || row.choices.none { it.value == row.selected })) {
                host.summary(preference, "当前：${row.currentLabel}（耳机回报）")
            }
            add(preference)
        }
        state.features.get<NiceHckSettingsFeatureState>()?.let { feature ->
            val labels = mapOf(NiceHckSetting.NOISE to "噪声模式", NiceHckSetting.EQ to "均衡器", NiceHckSetting.GAME to "游戏模式",
                NiceHckSetting.LOW_LATENCY to "低延迟", NiceHckSetting.MULTIPOINT to "双设备连接", NiceHckSetting.WEAR_DETECTION to "入耳检测", NiceHckSetting.WIND_SUPPRESSION to "风噪抑制")
            feature.values.forEach { (setting, selected) ->
                val allowed = feature.allowed(setting)
                val names = when(setting) {
                    NiceHckSetting.NOISE -> mapOf(0 to "关闭",1 to "通透",2 to "普通降噪",3 to "深度降噪",16 to "试验性降噪",17 to "抗风噪")
                    NiceHckSetting.EQ -> listOf("悔恨之泪","均衡中正","欧美澎湃","真律还原","游戏优化","细腻佳音","温婉人声").mapIndexed { i,v -> i to v }.toMap()
                    else -> mapOf(0 to "关闭",1 to "开启")
                }.filterKeys { it in allowed }
                choice("nicehck.${setting.name}", labels.getValue(setting), names, selected) { latest, value ->
                    if (latest.features.get<NiceHckSettingsFeatureState>()?.allowed(setting) != allowed) null
                    else NiceHckCardPolicy.request(latest,address,NiceHckControlRequest.SetValue(setting,value))
                }
            }
        }
        state.features.get<PuddingSettingsFeatureState>()?.values?.forEach { (setting, selected) ->
            val names = when(setting) {
                PuddingSetting.NOISE -> mapOf(0 to "关闭",1 to "自适应降噪",2 to "通透",3 to "抗风噪",4 to "基础降噪")
                PuddingSetting.GAIN -> mapOf(2 to "低增益",1 to "中增益",0 to "高增益")
                PuddingSetting.LED -> mapOf(0 to "关闭",1 to "开启")
            }
            choice("pudding.${setting.name}", when(setting) { PuddingSetting.NOISE -> "噪声模式"; PuddingSetting.GAIN -> "增益"; PuddingSetting.LED -> "指示灯" }, names, selected) { latest,value ->
                PuddingCardPolicy.request(latest,address,PuddingControlRequest.SetValue(setting,value))
            }
        }
        state.features.get<OppoSettingsFeatureState>()?.let { feature ->
            val labels = mapOf(OppoToggle.AUTO_PLAY_PAUSE to "自动播放／暂停", OppoToggle.LOW_LATENCY to "低延迟", OppoToggle.MULTIPOINT to "双设备连接", OppoToggle.GAME_MODE to "游戏模式")
            feature.toggles.forEach { (setting,value) -> toggle("oppo.${setting.name}",labels.getValue(setting),value) { latest,next -> OppoCardPolicy.request(latest,address,OppoControlRequest.SetToggle(setting,next)) } }
            feature.noise?.let { noise -> choice("oppo.noise", "降噪与通透", noise.definition.options.filter { it.writable }.associate { it.index to it.mode.title },noise.reportedIndex) { latest,value ->
                OppoCardPolicy.request(latest,address,OppoControlRequest.SelectNoiseOption(noise.definition,value))
            } }
            feature.currentEqualizer?.let { selected -> feature.equalizers?.takeIf { it.isNotEmpty() }?.let { eq ->
                choice("oppo.eq", "音效",eq.associate { it.id to it.name },selected) { latest,value ->
                    if (latest.features.get<OppoSettingsFeatureState>()?.equalizers?.map { it.definition() } != eq.map { it.definition() }) null
                    else OppoCardPolicy.request(latest,address,OppoControlRequest.SelectEqualizer(value))
                }
            } }
        }
        state.features.get<QcySettingsFeatureState>()?.let { feature ->
            val labels = listOf("低延迟模式","睡眠模式","LDAC","双设备连接","动态 EQ","自适应音量")
            feature.toggles.forEach { (setting,value) -> toggle("qcy.${setting.name}",labels[setting.ordinal],value) { latest,next -> QcyCardPolicy.request(latest,address,QcyControlRequest.SetToggle(setting,next)) } }
            feature.spatialMode?.let { choice("qcy.spatial","空间音效",mapOf(0 to "关闭",1 to "模式 1",2 to "模式 2"),it) { latest,value -> QcyCardPolicy.request(latest,address,QcyControlRequest.SetSpatialMode(value)) } }
            feature.eqPreset?.let { choice("qcy.eq","耳机均衡器预设",mapOf(1 to "默认",2 to "流行",3 to "低音",4 to "摇滚",5 to "柔和",6 to "古典",10 to "空间",0 to "自定义"),it) { latest,value -> QcyCardPolicy.request(latest,address,QcyControlRequest.SetEqPreset(value)) } }
        }
        state.features.get<VivoTws3eFeatureState>()?.let { feature ->
            feature.wearDetection?.let { toggle("vivo.wear","佩戴检测",it) { latest,value -> VivoTws3eSettingsPolicy.request(latest,address,VivoTws3eControlRequest.SetWearDetection(value)) } }
            feature.deepX?.let { selected -> choice("vivo.deepx","DeepX 音效",mapOf(0 to "默认",1 to "清澈人声",2 to "超重低音",3 to "清亮高音",5 to "悠扬听书"),selected.wire) { latest,value ->
                VivoDeepX.entries.singleOrNull { it.wire == value }?.let { VivoTws3eSettingsPolicy.request(latest,address,VivoTws3eControlRequest.SetDeepX(it)) }
            } }
        }
        state.features.get<BoseDailySettingsFeatureState>()?.let { feature ->
            feature.promptsRaw?.let { toggle("bose.prompts","语音提示",it and 32 != 0) { latest,value -> BoseDailyCardPolicy.request(latest,address,BoseDailyControlRequest.SetVoicePrompts(value)) } }
            feature.multipoint?.let { toggle("bose.multipoint","双设备连接",it) { latest,value -> BoseDailyCardPolicy.request(latest,address,BoseDailyControlRequest.SetMultipoint(value)) } }
            feature.autoPause?.let { toggle("bose.autoPause","自动播放／暂停",it) { latest,value -> BoseDailyCardPolicy.request(latest,address,BoseDailyControlRequest.SetAutoPause(value)) } }
            feature.autoAnswer?.let { toggle("bose.autoAnswer","自动接听来电",it) { latest,value -> BoseDailyCardPolicy.request(latest,address,BoseDailyControlRequest.SetAutoAnswer(value)) } }
            feature.sidetone?.let { choice("bose.sidetone","通话侧音",mapOf(0 to "关闭",1 to "高",2 to "中",3 to "低"),it) { latest,value -> BoseDailyCardPolicy.request(latest,address,BoseDailyControlRequest.SetSidetone(value)) } }
            feature.anr?.let { choice("bose.anr","降噪档位",mapOf(0 to "关闭",1 to "高",2 to "抗风噪",3 to "低"),it) { latest,value -> BoseDailyCardPolicy.request(latest,address,BoseDailyControlRequest.SetAnr(value)) } }
            feature.cnc?.let { cnc -> choice("bose.cnc","调节并开启降噪",(0..10).associateWith { (10-it).toString() },cnc.rawLevel) { latest,value -> BoseDailyCardPolicy.request(latest,address,BoseDailyControlRequest.SetCncLevel(value)) } }
        }
        state.features.get<TechnicsLevelsFeatureState>()?.let { feature ->
            if (feature.mode in setOf(NoiseMode.ANC,NoiseMode.TRANSPARENCY)) {
                val ambient = feature.mode == NoiseMode.TRANSPARENCY
                add(host.range("hyperears.technics.level",if(ambient) "通透强度" else "降噪强度",0..100,
                    if(ambient) feature.ambient else feature.anc,TechnicsCardPolicy.ready(state,address)) { value -> runCatching {
                    val latest = current() ?: return@runCatching
                    TechnicsCardPolicy.request(latest,address,TechnicsControlRequest.SetLevel(ambient,value,feature.mode))?.let(send)
                } })
            }
        }
        state.features.get<SonyAudioSettingsFeatureState>()?.let { feature ->
            feature.dseeDefinition?.let { definition -> feature.dsee?.let { value ->
                toggle("sony.dsee","DSEE",value,feature.dseeWritable) { latest,next -> SonyCardPolicy.request(latest,address,SonyControlRequest.SetDsee(definition,next)) }
            } }
            feature.speakDefinition?.let { definition -> feature.speak?.let { value ->
                toggle("sony.speak","Speak-to-Chat",value,feature.speakAvailable == true) { latest,next -> SonyCardPolicy.request(latest,address,SonyControlRequest.SetSpeakToChat(definition,next)) }
            } }
            feature.qualityDefinition?.let { definition -> feature.quality?.let { value ->
                choice("sony.quality",if(value == 2) "连接质量：低延迟（耳机回报）" else "连接质量",mapOf(0 to "音质优先",1 to "连接稳定优先"),value,feature.qualityAvailable == true) { latest,next -> SonyCardPolicy.request(latest,address,SonyControlRequest.SetConnectionQuality(definition,next)) }
            } }
        }
        state.features.get<SamsungDailySettingsFeatureState>()?.let { feature ->
            fun qualified(latest: EarbudState) = latest.features.get<SamsungDailySettingsFeatureState>()?.let { it.model == feature.model && it.revision == feature.revision } == true
            feature.ambientVolume?.let { value -> choice("samsung.ambient","通透音量",(0..feature.ambientMaximum).associateWith(Int::toString),value) { latest,next ->
                if(!qualified(latest)) null else SamsungDailySettingsPolicy.request(latest,address,SamsungDailyControlRequest.SetAmbientVolume(next))
            } }
            feature.ancLevel?.let { value -> choice("samsung.anc","降噪强度",mapOf(0 to "低",1 to "高"),value) { latest,next ->
                if(!qualified(latest)) null else SamsungDailySettingsPolicy.request(latest,address,SamsungDailyControlRequest.SetAncLevel(next))
            } }
            feature.locked?.takeIf { feature.sourceModel.ambientOnly }?.let { value -> toggle("samsung.lock","锁定触控",value) { latest,next ->
                if(!qualified(latest)) null else SamsungDailySettingsPolicy.request(latest,address,SamsungDailyControlRequest.SetLegacyLock(next))
            } }
            feature.voiceFocus?.let { value -> toggle("samsung.voice","人声增强",value) { latest,next ->
                if(!qualified(latest)) null else SamsungDailySettingsPolicy.request(latest,address,SamsungDailyControlRequest.SetVoiceFocus(next))
            } }
        }
        state.features.get<RoseRfcommSettingsFeatureState>()?.values?.forEach { (setting,value) ->
            val labels = mapOf(RoseRfcommSetting.NOISE to "噪声模式",RoseRfcommSetting.GAME to "游戏模式",RoseRfcommSetting.EQ to "音效",
                RoseRfcommSetting.ANC_DEPTH to "降噪强度",RoseRfcommSetting.TRANSPARENCY_DEPTH to "通透强度",RoseRfcommSetting.PROMPT_LANGUAGE to "提示音语言",RoseRfcommSetting.PROMPT_LEVEL to "提示音音量")
            val names = when(setting) {
                RoseRfcommSetting.NOISE -> mapOf(1 to "降噪",2 to "关闭",3 to "通透",4 to "抗风噪",5 to "自适应降噪",6 to "极限降噪")
                RoseRfcommSetting.GAME -> mapOf(0 to "关闭",1 to "开启")
                RoseRfcommSetting.EQ -> mapOf(0 to "HiFi",1 to "流行",2 to "摇滚",3 to "ROSE 经典")
                RoseRfcommSetting.ANC_DEPTH -> mapOf(1 to "轻度",3 to "中度",5 to "深度")
                RoseRfcommSetting.TRANSPARENCY_DEPTH -> mapOf(1 to "标准",5 to "深度")
                RoseRfcommSetting.PROMPT_LANGUAGE -> mapOf(0 to "中文",1 to "英文")
                RoseRfcommSetting.PROMPT_LEVEL -> setting.values.associateWith(Int::toString)
            }
            choice("rose.rfcomm.${setting.name}",labels.getValue(setting),names.filterKeys { it in setting.values },value) { latest,next ->
                RoseRfcommCardPolicy.request(latest,address,RoseRfcommControlRequest.SetValue(setting,next))
            }
        }
        if (state.features.get<EdifierGameModeFeatureState>() != null &&
            state.adapter?.presentationId in setOf(EdifierMiLinkPresentationIds.W820NB, EdifierMiLinkPresentationIds.FITBUDS_TURBO, EdifierMiLinkPresentationIds.GAME_MODE)) {
            val rendered = EdifierGameModeToggleSpec.render(state)
            if (rendered.available) toggle("edifier.game","游戏模式",rendered.checked,rendered.enabled) { latest,value -> EdifierGameModeToggleSpec.request(latest,value) }
        }
        if (state.adapter?.capabilities?.windNoiseControl == true) {
            val rendered = WindNoiseToggleSpec.render(state)
            toggle("wind","抗风噪",rendered.checked,rendered.enabled) { latest,value ->
                if(latest.adapter?.capabilities?.windNoiseControl == true) WindNoiseToggleSpec.request(latest,value) else null
            }
        }
        if (state.adapter?.presentationId == FloS2MiLinkPresentationIds.SOUND_EFFECT) state.features.get<FloS2SoundEffectFeatureState>()?.let { feature ->
            choice("flos2.effect","音效",mapOf(0 to "标准",1 to "游泳",2 to "人声",3 to "增强"),feature.effect.ordinal) { latest,value ->
                FloS2SoundEffectCardPolicy.request(latest,FloS2SoundEffect.entries[value])?.let(FloS2ControlRequest::SetSoundEffect)
            }
        }
    }
}
