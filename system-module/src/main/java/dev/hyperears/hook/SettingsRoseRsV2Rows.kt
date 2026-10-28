package dev.hyperears.hook

import dev.hyperears.integration.*

/** Exact source UI levels; dB values are device settings, not measured sound exposure. */
internal object RoseRsV2DailyLabels {
    fun noiseLevel(value: Int) = when(value) { 5 -> "低"; 3 -> "中"; 1 -> "高"; else -> "档位 $value" }
    val gain = listOf("档位 0", "档位 1", "档位 2")
    fun equalizer(profile: dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model, value: Int): String = when (profile) {
        dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model.CERAMICS_U_HSC_2710, dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model.CERAMICS_U_SOURCE -> if (value == 3) "经典调音" else listOf("流行","高保真","摇滚").getOrNull(value)
        dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model.EARFEEL_I7_SOURCE -> listOf("弱水经典","原声再现","古典乐器","日系甜美").getOrNull(value)
        dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model.ASTRO_X -> listOf("低音","均衡","低音增强").getOrNull(value)
        dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model.OPENFEEL_L -> listOf("低音","均衡").getOrNull(value)
        else -> listOf("流行","高保真","摇滚","轻音乐").getOrNull(value)
    } ?: "音效 $value"
    val hearingProtection = listOf("关闭", "75 dB", "80 dB", "85 dB", "90 dB", "95 dB", "100 dB")
}

internal object SettingsRoseRsV2Rows {
    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        current: () -> EarbudState?, send: (ControlRequest) -> Unit) {
        if(!RoseRsV2SettingsPolicy.available(state,address))return
        val feature = state.features.get<RoseRsV2SettingsFeatureState>() ?: return
        val profile = feature.profile ?: return
        fun choice(key: String, title: String, selected: Int?, labels: List<String>, request: (Int) -> RoseRsV2ControlRequest) {
            if (selected !in labels.indices) return
            add(host.choice("hyperears.rsv2.$key", title, labels, labels.indices.map(Int::toString), selected.toString(),
                RoseRsV2SettingsPolicy.available(state, address)) { value -> runCatching {
                val latest = current() ?: return@runCatching
                val number = value.toIntOrNull()?.takeIf { it in labels.indices } ?: return@runCatching
                RoseRsV2SettingsPolicy.request(latest, address, request(number))?.let(send)
            } })
        }
        fun depth(transparency: Boolean) {
            val values=if(transparency) profile.transparencyLevels else profile.ancLevels
            val selected=if(transparency) feature.transparencyLevel else feature.ancLevel
            if(selected !in values) return
            val key=if(transparency) "transparency_level" else "anc_level"
            val title=if(transparency) "通透强度" else "降噪深度"
            val request: (Int)->RoseRsV2ControlRequest = { RoseRsV2ControlRequest.SetNoiseLevel(feature.model,transparency,it) }
            add(host.choice("hyperears.rsv2.$key",title,values.map(RoseRsV2DailyLabels::noiseLevel),values.map(Int::toString),selected.toString(),
                RoseRsV2SettingsPolicy.request(state,address,request(values.first())) != null) { raw -> runCatching {
                val value=raw.toIntOrNull()?.takeIf { it in values } ?: return@runCatching
                val latest=current() ?: return@runCatching
                RoseRsV2SettingsPolicy.request(latest,address,request(value))?.let(send)
            } })
        }
        depth(false);depth(true)
        if (profile.languageSupported) choice("language", "耳机提示语言", feature.language, listOf("中文","English")) { RoseRsV2ControlRequest.SetLanguage(feature.model,it) }
        if (profile.wearDetectionSupported) choice("wear", "佩戴检测", feature.wearDetection?.let { if(it)1 else 0 }, listOf("关闭","开启")) { RoseRsV2ControlRequest.SetWearDetection(feature.model,it==1) }
        if (profile.gainSupported) choice("gain", "增益", feature.gain, RoseRsV2DailyLabels.gain) { RoseRsV2ControlRequest.SetGain(feature.model, it) }
        if (profile.hearingProtectionSupported) choice("hearing", "听力保护", feature.hearingProtection, RoseRsV2DailyLabels.hearingProtection) {
            RoseRsV2ControlRequest.SetHearingProtection(feature.model, it)
        }
        feature.touch?.takeIf{feature.touchRaw!=null}?.forEachIndexed { index,value ->
            val slot=RoseRsV2SettingsFeatureState.SLOTS[index]
            val choices=profile.touchActions.toList()
            val row=host.choice("hyperears.rsv2.touch.$slot",RoseRsV2TouchLabels.title(index),choices.map{RoseRsV2TouchLabels.actions[it]},
                choices.map(Int::toString),value.toString(),true) { raw -> runCatching {
                val action=raw.toIntOrNull()?:return@runCatching
                val request=RoseRsV2ControlRequest.SetTouch(slot,action,feature.model,feature.touchRaw)
                current()?.let{RoseRsV2SettingsPolicy.request(it,address,request)}?.let(send)
            } }
            if(value !in choices)host.summary(row,RoseRsV2TouchLabels.current(value))
            add(row)
        }

    }
}

internal object RoseRsV2TouchLabels {
    val actions=listOf("无操作","播放 / 暂停","上一曲","下一曲","增大音量","减小音量","游戏模式","语音助手","降噪切换")
    fun title(index:Int)=(if(index<4)"左耳"else"右耳")+listOf("单击","双击","三击","长按")[index%4]
    fun current(value:Int)="当前：${actions.getOrNull(value)?:"耳机功能 $value"}（耳机回报）"
}
