package dev.hyperears.hook

import dev.hyperears.integration.*

internal object SettingsCoralRows {
    fun add(host: SettingsPreferenceHost, state: EarbudState, address: String, add: (Any) -> Unit,
        current: () -> EarbudState?, send: (ControlRequest) -> Unit, confirm: (String, () -> Unit) -> Unit,
        expanded: Boolean = false,
        sliderProjection: (RoseCoralSetting, Int) -> Pair<Int, Boolean> = { _, value -> value to true },
        submitSlider: ((RoseCoralSetting, Int, Int, ControlRequest) -> Boolean)? = null,
        openFind: ((List<String>, String, (Int) -> Unit) -> Unit)? = null) {
        state.features.get<RoseCoralSettingsFeatureState>()?.let { feature ->
            RoseCoralSetting.entries.forEach { setting ->
                val selected = setting.value(feature) ?: return@forEach
                val slider = sliderProjection(setting, selected)
                fun change(value: Int): Boolean = runCatching {
                    val latest = current() ?: return@runCatching false
                    if (expanded && !setting.isToggle && submitSlider != null) {
                        if (!RoseCoralCardPolicy.ready(latest, address) || value !in setting.range || value == slider.first) return@runCatching false
                        submitSlider(setting, slider.first, value, setting.request(value))
                    } else {
                        val request = RoseCoralCardPolicy.request(latest, address, setting, value) ?: return@runCatching false
                        send(request); true
                    }
                }.getOrDefault(false)
                val key = "hyperears.coral.${setting.name.lowercase(java.util.Locale.ROOT)}"
                val enabled = RoseCoralCardPolicy.ready(state, address)
                add(if (setting.isToggle) host.toggle(key, setting.label, selected == 1, enabled) { change(if (it) 1 else 0) }
                    else if (expanded) host.range(key, setting.label, setting.range, slider.first,
                        enabled && slider.second, awaitReadback = true, changed = ::change)
                    else host.choice(key, setting.label, setting.range.map(Int::toString), setting.range.map(Int::toString), selected.toString(), enabled) { value -> value.toIntOrNull()?.let(::change) })
            }
        }
        if (expanded) {
            state.features.get<RoseCoralTouchFeatureState>()?.let { touch ->
                val labels = listOf("无操作", "播放 / 暂停", "上一曲", "下一曲", "音量增加", "音量降低", "游戏模式", "语音助手", "切换降噪")
                val slots = listOf("左耳单击", "左耳双击", "左耳三击", "左耳长按", "右耳单击", "右耳双击", "右耳三击", "右耳长按")
                RoseCoralTouchSlot.entries.forEachIndexed { index, slot ->
                    add(host.choice("hyperears.coral.touch.${slot.name}", slots[index], labels,
                        RoseCoralTouchAction.entries.map { it.name }, touch.action(slot).name,
                        RoseCoralCardPolicy.ready(state, address) &&
                            state.features.get<RoseCoralSettingsFeatureState>()?.touchEnabled != true) { name -> runCatching {
                        val latest = current() ?: return@runCatching
                        val action = RoseCoralTouchAction.entries.singleOrNull { it.name == name } ?: return@runCatching
                        RoseCoralCardPolicy.touchRequest(latest, address, slot, action)?.let(send)
                    } })
                }
            }
            state.features.get<RoseCoralFindFeatureState>()?.let { find ->
                val targets = RoseCoralFindTarget.entries
                val labels = listOf("查找左耳", "查找右耳", "查找双耳", "停止查找")
                val choose: (String) -> Unit = { name ->
                    targets.singleOrNull { it.name == name }?.let { target ->
                        val execute = { current()?.let { fresh -> RoseCoralCardPolicy.findRequest(fresh, address, target)?.let(send) }; Unit }
                        if (target == RoseCoralFindTarget.STOP) execute()
                        else confirm("耳机会播放查找提示音，请先从耳朵取下。继续？", execute)
                    }
                }
                fun status(value: Boolean?) = when (value) { true -> "响铃中"; false -> "已停止"; null -> "未知" }
                val summary = "左耳：${status(find.left)} · 右耳：${status(find.right)}"
                val row = if (openFind != null) host.row("hyperears.coral.find", "查找耳机").also { row ->
                    host.enable(row, RoseCoralCardPolicy.ready(state, address))
                    host.onClick(row) { openFind(labels, summary) { index -> targets.getOrNull(index)?.let { choose(it.name) } } }
                } else host.choice("hyperears.coral.find", "查找耳机", labels, targets.map { it.name }, null,
                    RoseCoralCardPolicy.ready(state, address), choose)
                host.summary(row, summary)
                add(row)
            }
        }
        val audio = state.features.get<RoseCoralAudioFeatureState>()?.mode ?: return
        val choices = RoseCoralAudioMode.entries
        val labels = listOf("AAC/SBC", "LDAC", "LHDC", "LC3")
        val changeAudio: (String) -> Unit = { value -> runCatching {
            val mode = choices.singleOrNull { it.name == value } ?: return@runCatching
            val latest = current() ?: return@runCatching
            if (RoseCoralCardPolicy.audioRequest(latest, address, mode) == null) return@runCatching
            confirm("切换音频编码可能影响音频连接。确认切换？") { runCatching {
                val fresh = current() ?: return@runCatching
                if (fresh.features.get<RoseCoralAudioFeatureState>()?.mode != audio) return@runCatching
                RoseCoralCardPolicy.audioRequest(fresh, address, mode)?.let(send)
            } }
        }; Unit }
        add(host.choice("hyperears.coral.audio", "音频编码", labels, choices.map { it.name }, audio.name,
            RoseCoralCardPolicy.ready(state, address), changeAudio))
    }
}
