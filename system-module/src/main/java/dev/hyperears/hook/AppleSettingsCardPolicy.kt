package dev.hyperears.hook

import dev.hyperears.integration.*

/** UI choices map only to the existing typed protocol contract, never inferred defaults. */
internal enum class AppleSetting(val label: String, val range: IntRange, val choices: List<String> = emptyList()) {
    MICROPHONE("麦克风", 0..2, listOf("自动", "右耳", "左耳")),
    ONE_BUD_ANC("单耳降噪", 0..1),
    CONVERSATION_AWARENESS("对话感知", 0..1),
    PERSONALIZED_VOLUME("个性化音量", 0..1),
    WEARING_DETECTION("佩戴检测", 0..1),
    VOLUME_SWIPE("滑动调节音量", 0..1),
    DOUBLE_PRESS_INTERVAL("双按间隔", 0..2, listOf("级别 0", "级别 1", "级别 2")),
    HOLD_INTERVAL("长按间隔", 0..2, listOf("级别 0", "级别 1", "级别 2")),
    SWIPE_INTERVAL("滑动间隔", 0..2, listOf("级别 0", "级别 1", "级别 2")),
    ADAPTIVE_STRENGTH("自适应强度", 0..100),
    CALL_MAPPING("通话按键映射", 0..1, listOf("默认", "交换")),
    ALLOW_OFF("允许关闭噪声控制", 0..1),
    CHIME_VOLUME("提示音音量", 15..100),
    SLEEP_PAUSE("入睡暂停", 0..1);

    val isToggle get() = range == 0..1 && choices.isEmpty()
    fun value(state: AppleSettingsFeatureState): Int? = when (this) {
        MICROPHONE -> state.microphone?.ordinal
        ONE_BUD_ANC -> state.oneBudAnc?.number()
        CONVERSATION_AWARENESS -> state.conversationAwareness?.number()
        PERSONALIZED_VOLUME -> state.personalizedVolume?.number()
        WEARING_DETECTION -> state.wearingDetection?.number()
        VOLUME_SWIPE -> state.volumeSwipe?.number()
        DOUBLE_PRESS_INTERVAL -> state.doublePressInterval?.ordinal
        HOLD_INTERVAL -> state.holdInterval?.ordinal
        SWIPE_INTERVAL -> state.swipeInterval?.ordinal
        ADAPTIVE_STRENGTH -> state.adaptiveStrength
        CALL_MAPPING -> state.callMapping?.ordinal
        ALLOW_OFF -> state.allowOff?.number()
        CHIME_VOLUME -> state.chimeVolume
        SLEEP_PAUSE -> state.sleepPause?.number()
    }?.takeIf { it in range }

    fun request(value: Int): AppleControlRequest = when (this) {
        MICROPHONE -> AppleControlRequest.SetMicrophone(AppleMicrophone.entries[value])
        ONE_BUD_ANC -> AppleControlRequest.SetOneBudAnc(value == 1)
        CONVERSATION_AWARENESS -> AppleControlRequest.SetConversationAwareness(value == 1)
        PERSONALIZED_VOLUME -> AppleControlRequest.SetPersonalizedVolume(value == 1)
        WEARING_DETECTION -> AppleControlRequest.SetWearingDetection(value == 1)
        VOLUME_SWIPE -> AppleControlRequest.SetVolumeSwipe(value == 1)
        DOUBLE_PRESS_INTERVAL -> AppleControlRequest.SetDoublePressInterval(AppleIntervalLevel.entries[value])
        HOLD_INTERVAL -> AppleControlRequest.SetHoldInterval(AppleIntervalLevel.entries[value])
        SWIPE_INTERVAL -> AppleControlRequest.SetSwipeInterval(AppleIntervalLevel.entries[value])
        ADAPTIVE_STRENGTH -> AppleControlRequest.SetAdaptiveStrength(value)
        CALL_MAPPING -> AppleControlRequest.SetCallMapping(AppleCallMapping.entries[value])
        ALLOW_OFF -> AppleControlRequest.SetAllowOff(value == 1)
        CHIME_VOLUME -> AppleControlRequest.SetChimeVolume(value)
        SLEEP_PAUSE -> AppleControlRequest.SetSleepPause(value == 1)
    }

    private fun Boolean.number() = if (this) 1 else 0
}

internal object AppleSettingsCardPolicy {
    fun available(state: EarbudState, address: String): Boolean =
        state.address.equals(address, ignoreCase = true) && state.sessionActive &&
            state.privateChannelConnected && state.handshakeAccepted &&
            state.lifecycle.controlOwnership == ControlOwnership.MODULE &&
            state.features.get<AppleSettingsFeatureState>()?.let { feature ->
                AppleSetting.entries.any { it.value(feature) != null }
            } == true

    fun enabled(state: EarbudState, setting: AppleSetting): Boolean =
        setting != AppleSetting.ADAPTIVE_STRENGTH ||
            state.features.get<AppleListeningModeFeatureState>()?.mode == AppleListeningMode.ADAPTIVE

    fun request(state: EarbudState, address: String, setting: AppleSetting, value: Int): AppleControlRequest? {
        if (!available(state, address) || value !in setting.range || !enabled(state, setting)) return null
        val feature = state.features.get<AppleSettingsFeatureState>() ?: return null
        val previous = setting.value(feature) ?: return null
        return if (previous == value) null else setting.request(value)
    }
}
