package dev.hyperears.hook

import dev.hyperears.integration.*

/** Labels are model-aware where the same EQ byte has different meanings. */
internal object HuaweiDailyChoices {
    data class Choice(val value: Int, val label: String, val request: HuaweiDailyControlRequest)
    data class Row(val key: String, val label: String, val selected: Int, val choices: List<Choice>, val currentLabel: String? = null)
    fun rows(state: EarbudState): List<Row> {
        val feature = state.features.get<HuaweiDailySettingsFeatureState>() ?: return emptyList()
        return buildList {
            feature.lowLatency?.let { value -> add(Row("lowLatency", "低延迟模式", if(value) 1 else 0,
                listOf(false,true).map { Choice(if(it) 1 else 0,if(it) "开启" else "关闭",HuaweiDailyControlRequest.SetLowLatency(it)) })) }
            feature.preferSoundQuality?.let { value -> add(Row("soundQuality", "音质偏好", if(value) 1 else 0,
                listOf(Choice(0,"连接稳定优先",HuaweiDailyControlRequest.SetSoundQualityPreference(false)),
                    Choice(1,"音质优先",HuaweiDailyControlRequest.SetSoundQualityPreference(true))))) }

            feature.wearDetection?.let { value -> add(Row("wear", "佩戴检测 / 摘下暂停", if (value) 1 else 0,
                listOf(false, true).map { Choice(if (it) 1 else 0, if (it) "开启" else "关闭", HuaweiDailyControlRequest.SetWearDetection(it)) })) }
            feature.multipoint?.let { value -> add(Row("multipoint", "双设备连接", if (value) 1 else 0,
                listOf(false, true).map { Choice(if (it) 1 else 0, if (it) "开启" else "关闭", HuaweiDailyControlRequest.SetMultipoint(it)) })) }
            feature.equalizer?.let { eq ->
                val inventory=feature.eqInventory
                val choices=if(feature.eqManaged) {
                    if(inventory==null || feature.eqPending)emptyList()else
                        (inventory.builtIns+inventory.slots.map{it.id}).map { id -> Choice(id,
                            inventory.slots.singleOrNull{it.id==id}?.name?:presetLabel(state.modelId,id),
                            HuaweiDailyControlRequest.ManageEqualizer(inventory,HuaweiEqAction.SELECT,id)) }
                } else eq.presets.map { id -> Choice(id,eq.custom.singleOrNull{it.id==id}?.name?:presetLabel(state.modelId,id),HuaweiDailyControlRequest.SetEqualizer(id)) }
                add(Row("eq","音效",eq.selected,choices,currentLabel=if(feature.eqManaged)
                    eq.custom.singleOrNull{it.id==eq.selected}?.name?:presetLabel(state.modelId,eq.selected) else null))
            }
            feature.gestures.forEach { gesture ->
                HuaweiGestureSide.entries.forEach sideLoop@ { side ->
                    val current = gesture.value(side) ?: return@sideLoop
                    val complete = gesture.modelId == null || gesture.raw != null && gesture.left != null && gesture.right != null
                    val values = if (!complete) emptyList() else if (side == HuaweiGestureSide.CALL) listOf(-1, 0) else gesture.available
                    val prefix = if (state.modelId == "huawei-freelace-pro") {
                        if (gesture.kind == HuaweiGestureKind.DOUBLE) "电源键" else "双耳"
                    } else when (side) { HuaweiGestureSide.LEFT -> "左耳"; HuaweiGestureSide.RIGHT -> "右耳"; HuaweiGestureSide.CALL -> "通话" }
                    val kind = when (gesture.kind) {
                        HuaweiGestureKind.DOUBLE -> "双击"; HuaweiGestureKind.TRIPLE -> "三击"
                        HuaweiGestureKind.HOLD -> "长按"; HuaweiGestureKind.CYCLE -> "长按噪声循环"; HuaweiGestureKind.SWIPE -> "滑动"
                    }
                    add(Row("${gesture.kind}.${side}", (if (gesture.kind == HuaweiGestureKind.SWIPE && gesture.modelId == null) "双耳" else prefix) + kind,
                        current, values.map { Choice(it, gestureLabel(state.modelId, gesture.kind, side, it), HuaweiDailyControlRequest.SetGesture(gesture.kind, side, it,
                            gesture.takeIf { it.modelId != null })) },
                        currentLabel = if (gesture.modelId != null) {
                            if (current in gesture.available) gestureLabel(state.modelId, gesture.kind, side, current)
                            else "未知值 " + (current and 255)
                        } else null))
                }
            }
        }
    }
    private fun presetLabel(model: String?, value: Int): String {
        if (model in setOf("huawei-freebuds-pro3", "huawei-freebuds-pro4", "huawei-freebuds-pro5", "huawei-freeclip")) return when (value) {
            5 -> "默认"; 1 -> "低音增强"; 2 -> "高音增强"; 9 -> "人声"; else -> "预设 $value"
        }
        if (model == "huawei-freebuds-6" && value == 0) return "默认"
        if (model == "huawei-freebuds-7") return when (value) {
            5 -> "均衡"; 9 -> "人声"; 2 -> "低音"; 13 -> "电影"; 14 -> "游戏"; 15 -> "播客"; 16 -> "运动"; else -> "预设 $value"
        }
        if (model == "huawei-freelace-pro2") return "预设 $value"
        return when (value) { 1 -> "默认"; 2 -> "低音增强"; 3 -> "高音增强"; 9 -> "人声"; 10 -> "运动增强"; else -> "预设 $value" }
    }
    private fun gestureLabel(model: String?, kind: HuaweiGestureKind, side: HuaweiGestureSide, value: Int): String {
        if (model == "huawei-freelace-pro") return when (kind) {
            HuaweiGestureKind.DOUBLE -> when (value) { -1 -> "关闭"; 12 -> "切换设备"; else -> "未知" }
            HuaweiGestureKind.HOLD -> when (value) {
                -1 -> "关闭"; 3 -> "关闭 / 降噪"; 5 -> "关闭 / 降噪 / 通透"
                6 -> "降噪 / 通透"; 9 -> "组合模式 9"; else -> "未知"
            }
            else -> "未知"
        }
        if (model == "huawei-freebuds-3" && kind == HuaweiGestureKind.DOUBLE) return when (value) {
            -1 -> "关闭"; 0 -> "语音助手"; 1 -> "播放 / 暂停"; 3 -> "切换降噪"; 4 -> "下一首"; else -> "未知"
        }
        if (model in setOf("huawei-freebuds-4", "huawei-freebuds-4e") && kind == HuaweiGestureKind.HOLD) return when (value) {
            3 -> "切换降噪"; 14 -> "听歌识曲"; -1 -> "关闭"; else -> "未知"
        }
        if (model == "huawei-freeclip-2" && kind == HuaweiGestureKind.DOUBLE && value == 7) return "切换空间音频"
        if (side == HuaweiGestureSide.CALL) return if (value == -1) "关闭" else "接听电话"
        if (kind == HuaweiGestureKind.CYCLE) return when (value) {
            1 -> "关闭 / 降噪"; 2 -> "关闭 / 降噪 / 通透"; 3 -> "降噪 / 通透"; 4 -> "关闭 / 通透"; else -> "未知"
        }
        if (kind == HuaweiGestureKind.SWIPE) return when (value) {
            -1 -> "关闭"; 0 -> "调节音量"; 1 -> "切换曲目"; else -> "未知值 " + (value and 255)
        }
        return when (value) {
            -1 -> "关闭"; 0 -> "语音助手"; 1 -> "播放 / 暂停"; 2 -> "下一首"; 7 -> "上一首"
            10 -> "切换噪声模式"; 18 -> "增大音量"; 19 -> "减小音量"; else -> "未知"
        }
    }
}
