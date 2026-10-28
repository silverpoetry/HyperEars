package dev.hyperears.hook

import dev.hyperears.integration.*
import dev.hyperears.protocol.rose.RoseRsV2IdentityCodec.Model

internal object SettingsRoseRows {
    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        current: () -> EarbudState?, send: (ControlRequest) -> Unit, confirm: (String, () -> Unit) -> Unit) {
        val feature = state.features.get<RoseRsV2SettingsFeatureState>() ?: return
        val profile = feature.profile ?: return
        fun latest() = current()?.takeIf { it.features.get<RoseRsV2SettingsFeatureState>()?.profile == profile }
        fun choice(key: String, title: String, values: List<Int>, labels: List<String>, selected: Int, request: (Int) -> RoseRsV2ControlRequest) {
            add(host.choice("hyperears.rsv2.$key",title,labels,values.map(Int::toString),selected.toString(),RoseRsV2SettingsPolicy.available(state,address)) { value -> runCatching {
                val now = latest() ?: return@runCatching
                val number = value.toIntOrNull()?.takeIf { it in values } ?: return@runCatching
                if(number != selected) RoseRsV2SettingsPolicy.request(now,address,request(number))?.let(send)
            } })
        }
        feature.eq?.let { value ->
            val labels = when(profile) { Model.ASTRO_X -> listOf("低音","均衡","低音增强"); Model.OPENFEEL_L -> listOf("低音","均衡")
                else -> listOf("流行","高保真","摇滚","轻音乐").take(profile.eqPresets.size) }
            choice("eq","音效",profile.eqPresets,labels,value,RoseRsV2ControlRequest::SetEqualizer)
        }
        feature.bass?.let { choice("bass","低音增强",profile.bassLevels,profile.bassLevels.map { "级别 $it" },it,RoseRsV2ControlRequest::SetBass) }
        feature.prompt?.let { choice("prompt","提示音音量",(1..5).toList(),(1..5).map(Int::toString),it,RoseRsV2ControlRequest::SetPrompt) }
        fun toggle(key: String,title: String,value: Boolean?,request: (Boolean) -> RoseRsV2ControlRequest) {
            if(value == null) return
            add(host.toggle("hyperears.rsv2.$key",title,value,RoseRsV2SettingsPolicy.available(state,address)) { next -> runCatching {
                val now = latest() ?: return@runCatching
                RoseRsV2SettingsPolicy.request(now,address,request(next))?.let(send)
            } })
        }
        toggle("game","游戏模式",feature.game,RoseRsV2ControlRequest::SetGame)
        toggle("multi","双设备连接",feature.multipoint,RoseRsV2ControlRequest::SetMultipoint)
        if(profile.touchMaster) toggle("touch","触控开关",feature.touchEnabled,RoseRsV2ControlRequest::SetTouchEnabled)
        val audio = feature.audio ?: return
        if(!profile.audio) return
        val labels = listOf("AAC / SBC","LDAC","LHDC","LC3","aptX","aptX HD","aptX Adaptive")
        add(host.choice("hyperears.rsv2.audio","音频模式",profile.audioModes.map { labels[it] },profile.audioModes.map(Int::toString),audio.toString(),RoseRsV2SettingsPolicy.available(state,address)) { value -> runCatching {
            val mode = value.toIntOrNull()?.takeIf { it in profile.audioModes && it != audio } ?: return@runCatching
            val now = latest() ?: return@runCatching
            val request = RoseRsV2ControlRequest.SetAudio(mode)
            if(RoseRsV2SettingsPolicy.request(now,address,request) == null) return@runCatching
            confirm("切换音频模式可能影响音频连接。确认切换？") { runCatching {
                val fresh = latest() ?: return@runCatching
                if(fresh.features.get<RoseRsV2SettingsFeatureState>()?.audio != audio) return@runCatching
                RoseRsV2SettingsPolicy.request(fresh,address,request)?.let(send)
            } }
        } })
    }
}
