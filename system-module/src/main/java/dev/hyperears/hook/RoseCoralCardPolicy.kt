package dev.hyperears.hook

import dev.hyperears.integration.*

internal enum class RoseCoralSetting(val label: String, val range: IntRange) {
    GAME("游戏模式", 0..1), MULTIPOINT("双设备连接", 0..1), BASS("低音增强", 0..2),
    PROMPT("提示音音量", 1..5), TOUCH("触控开关", 0..1);
    val isToggle get() = range == 0..1
    fun value(state: RoseCoralSettingsFeatureState): Int? = when (this) {
        GAME -> state.gameMode?.number()
        MULTIPOINT -> state.multipoint?.number()
        BASS -> state.bassLevel
        PROMPT -> state.promptVolume
        TOUCH -> state.touchEnabled?.number()
    }?.takeIf { it in range }
    fun request(value: Int): RoseCoralControlRequest = when (this) {
        GAME -> RoseCoralControlRequest.SetGameMode(value == 1)
        MULTIPOINT -> RoseCoralControlRequest.SetMultipoint(value == 1)
        BASS -> RoseCoralControlRequest.SetBassLevel(value)
        PROMPT -> RoseCoralControlRequest.SetPromptVolume(value)
        TOUCH -> RoseCoralControlRequest.SetTouchEnabled(value == 1)
    }
    private fun Boolean.number() = if (this) 1 else 0
}

internal object RoseCoralCardPolicy {
    fun ready(state: EarbudState, address: String) = state.address.equals(address, true) &&
        state.sessionActive && state.privateChannelConnected && state.handshakeAccepted &&
        state.lifecycle.controlOwnership == ControlOwnership.MODULE
    fun available(state: EarbudState, address: String) = ready(state, address) && (
        state.features.get<RoseCoralSettingsFeatureState>()?.let { feature -> RoseCoralSetting.entries.any { it.value(feature) != null } } == true ||
        state.features.get<RoseCoralTouchFeatureState>() != null || state.features.get<RoseCoralFindFeatureState>() != null ||
        state.features.get<RoseCoralInfoFeatureState>() != null || state.features.get<RoseCoralAudioFeatureState>() != null)
    fun request(state: EarbudState, address: String, setting: RoseCoralSetting, value: Int): RoseCoralControlRequest? {
        if (!ready(state, address) || value !in setting.range) return null
        val previous = state.features.get<RoseCoralSettingsFeatureState>()?.let(setting::value) ?: return null
        return if (previous == value) null else setting.request(value)
    }
    fun touchRequest(state: EarbudState, address: String, slot: RoseCoralTouchSlot, action: RoseCoralTouchAction): RoseCoralControlRequest? {
        if (!ready(state, address)) return null
        val previous = state.features.get<RoseCoralTouchFeatureState>()?.action(slot) ?: return null
        return if (previous == action) null else RoseCoralControlRequest.SetTouch(slot, action)
    }
    fun findRequest(state: EarbudState, address: String, target: RoseCoralFindTarget): RoseCoralControlRequest? {
        if (!ready(state, address) || state.features.get<RoseCoralFindFeatureState>() == null) return null
        // Find is an explicit command: STOP must remain available even with incomplete readback.
        return RoseCoralControlRequest.SetFind(target)
    }
    fun audioRequest(state: EarbudState, address: String, mode: RoseCoralAudioMode): RoseCoralControlRequest.SetAudioMode? {
        if (!ready(state, address)) return null
        val current = state.features.get<RoseCoralAudioFeatureState>()?.mode ?: return null
        return if (current == mode) null else RoseCoralControlRequest.SetAudioMode(mode)
    }
    fun noiseRequest(state: EarbudState, address: String, mode: NoiseMode): StandardControlRequest.SetNoiseMode? {
        if (!ready(state, address) || state.noiseMode !in MODES || mode !in MODES || mode == state.noiseMode) return null
        return StandardControlRequest.SetNoiseMode(mode)
    }
    val MODES = listOf(NoiseMode.ANC, NoiseMode.OFF)
}
