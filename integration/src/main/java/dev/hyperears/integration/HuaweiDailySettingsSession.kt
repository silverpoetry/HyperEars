package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiDailySettingsCodec as Wire
import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec

/** Session-owned authoritative settings. Read commands only are accepted as state, never write ACKs. */
internal class HuaweiDailySettingsSession(private val profile: HuaweiDailyProfile) {
    var state = HuaweiDailySettingsFeatureState()
        private set
    fun reset() { state = HuaweiDailySettingsFeatureState() }
    fun lowLatencyQuery(): List<ByteArray> = if (profile.lowLatency) listOf(Wire.query(Wire.LOW_LATENCY, 2)) else emptyList()
    fun queries(): List<ByteArray> = buildList {
        addAll(lowLatencyQuery())
        if (profile.soundQuality) add(Wire.query(Wire.SOUND_QUALITY_READ, 1))
        if (profile.wear) add(Wire.query(Wire.WEAR_READ, 1))
        if (profile.multipoint) add(Wire.query(Wire.MULTIPOINT_READ, 1))
        if (profile.presets.isNotEmpty() || profile.custom) add(Wire.queryEqualizer())
        profile.gestures.keys.forEach { add(Wire.queryGesture(it.wire())) }
    }
    fun readback(request: HuaweiDailyControlRequest): List<ByteArray> {
        if (request is HuaweiDailyControlRequest.SetLowLatency) return emptyList()
        return listOf(when (request) {
        is HuaweiDailyControlRequest.SetLowLatency -> error("delayed readback handled above")
        is HuaweiDailyControlRequest.SetSoundQualityPreference -> Wire.query(Wire.SOUND_QUALITY_READ, 1)
        is HuaweiDailyControlRequest.SetWearDetection -> Wire.query(Wire.WEAR_READ, 1)
        is HuaweiDailyControlRequest.SetMultipoint -> Wire.query(Wire.MULTIPOINT_READ, 1)
        is HuaweiDailyControlRequest.SetEqualizer, is HuaweiDailyControlRequest.SetCustomEqualizer -> Wire.queryEqualizer()
        is HuaweiDailyControlRequest.SetGesture -> Wire.queryGesture(request.kind.wire())
    })
    }
    fun encode(request: HuaweiDailyControlRequest): ByteArray? {
        if (!state.supports(request)) return null
        return when (request) {
            is HuaweiDailyControlRequest.SetLowLatency -> Wire.setBoolean(Wire.LOW_LATENCY, request.enabled)
            is HuaweiDailyControlRequest.SetSoundQualityPreference -> Wire.setBoolean(Wire.SOUND_QUALITY_WRITE, request.preferQuality)
            is HuaweiDailyControlRequest.SetWearDetection -> Wire.setBoolean(Wire.WEAR_WRITE, request.enabled)
            is HuaweiDailyControlRequest.SetMultipoint -> Wire.setBoolean(Wire.MULTIPOINT_WRITE, request.enabled)
            is HuaweiDailyControlRequest.SetEqualizer -> {
                val custom = state.equalizer?.custom?.singleOrNull { it.id == request.preset }
                if (custom == null) Wire.setPreset(request.preset) else
                    Wire.setCustom(Wire.CustomPreset(custom.id, custom.name, custom.gains))
            }
            is HuaweiDailyControlRequest.SetCustomEqualizer -> {
                val original = state.equalizer?.custom?.singleOrNull { it.id == request.id && it == request.expectedSlot } ?: return null
                Wire.setCustom(Wire.CustomPreset(original.id, original.name, request.gains))
            }
            is HuaweiDailyControlRequest.SetGesture -> Wire.setGesture(request.kind.wire(), request.side.field, request.value)
        }
    }
    fun offer(frame: HuaweiFreebudsSppCodec.Frame): HuaweiDailySettingsFeatureState? {
        val next = when {
            profile.lowLatency && frame.command == Wire.LOW_LATENCY ->
                Wire.parsePreference(frame, Wire.LOW_LATENCY)?.let { state.copy(lowLatency = it) }
            profile.soundQuality && frame.command == Wire.SOUND_QUALITY_READ ->
                Wire.parsePreference(frame, Wire.SOUND_QUALITY_READ)?.let { state.copy(preferSoundQuality = it) }
            profile.wear && frame.command == Wire.WEAR_READ ->
                Wire.parseBoolean(frame, Wire.WEAR_READ)?.let { state.copy(wearDetection = it) }
            profile.multipoint && frame.command == Wire.MULTIPOINT_READ ->
                Wire.parseBoolean(frame, Wire.MULTIPOINT_READ)?.let { state.copy(multipoint = it) }
            (profile.presets.isNotEmpty() || profile.custom) && frame.command == Wire.EQ_READ ->
                Wire.parseEqualizer(frame)?.let { report ->
                    val advertised = report.advertised
                    val builtIn = if (profile.presets.isEmpty()) advertised.orEmpty().filter { it in 0..99 }
                        else profile.presets.filter { advertised == null || it in advertised }
                    val custom = if (profile.custom) report.custom.map { HuaweiCustomEqualizer(it.id, it.name, it.gains) } else emptyList()
                    state.copy(equalizer = HuaweiEqualizerState(report.selected, builtIn + custom.map { it.id }, custom, profile.custom))
                }
            else -> profile.gestures.entries.firstOrNull { it.key.wire().read == frame.command }?.let { (kind, config) ->
                val report = Wire.parseGesture(frame, kind.wire()) ?: return null
                if (report.left != null && report.left !in config.values ||
                    config.right && report.right != null && report.right !in config.values ||
                    config.call && report.call != null && report.call !in listOf(-1, 0)) return null
                val advertised = report.advertised
                val available = config.values.filter { advertised == null || it in advertised }
                val gesture = HuaweiGestureState(kind, report.left, report.right.takeIf { config.right },
                    report.call.takeIf { config.call }, available)
                state.copy(gestures = state.gestures.filterNot { it.kind == kind } + gesture)
            }
        } ?: return null
        state = next
        return next
    }
    private fun HuaweiGestureKind.wire() = Wire.Gesture.valueOf(name)
}
