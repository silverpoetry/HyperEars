package dev.hyperears.integration

import dev.hyperears.protocol.oppo.OppoSettingsCodec
import dev.hyperears.protocol.oppo.OppoWireCodec

/** Optional settings evidence belongs to the same physical OPPO conversation. */
internal class OppoSettingsSession {
    private var state = OppoSettingsFeatureState()
    private var reported = false
    val initialQueries: List<ByteArray> get() = listOf(OppoSettingsCodec.querySwitches(),
        OppoSettingsCodec.queryCurrentEq, OppoSettingsCodec.queryEqualizers, OppoWireCodec.queryFrameBudget)

    fun identify(productId: Int?): OppoSettingsFeatureState? {
        val builtIns = OppoBuiltInEqIds.byProduct[productId]
        state = state.copy(editableEqualizers = if (builtIns == null) emptySet() else
            state.equalizers.orEmpty().filter { it.id !in builtIns &&
                OppoSettingsCodec.canUpdate(it.wire(), it.gains, state.maximumEqualizerPayload()) }.map { it.id }.toSet())
        return state.takeIf { reported }
    }
    fun offer(frame: OppoWireCodec.Frame, productId: Int?): OppoSettingsFeatureState? {
        var changed = false
        OppoWireCodec.parseFrameBudget(frame)?.let {
            state = state.copy(equalizerWriteBudget = it)
            // Transport evidence alone is not a user setting and must not create an empty card.
            changed = reported
        }
        // Every switch request asks for the same complete four-field set, so a successful
        // empty or reduced snapshot revokes stale controls. Malformed frames leave evidence alone.
        OppoSettingsCodec.parseSwitches(frame)?.let { values ->
            state = state.copy(toggles = values.mapKeys { entry ->
                OppoToggle.entries.single { it.featureId == entry.key }
            })
            changed = true
        }
        OppoSettingsCodec.parseCurrentEq(frame)?.let {
            state = state.copy(currentEqualizer = it); changed = true
        }
        OppoSettingsCodec.parseEqualizers(frame)?.let { entries ->
            state = state.copy(canonicalLongFrames = state.canonicalLongFrames || frame.canonicalLongLength, equalizers = entries.map {
                OppoEqualizer(it.id, it.name, it.minimum, it.maximum, it.frequencies, it.gains)
            }, currentEqualizer = entries.firstOrNull { it.selected }?.id)
            changed = true
        }
        if (!changed) return null
        reported = true
        return identify(productId)
    }
    fun encode(request: OppoControlRequest): List<ByteArray> {
        if (!state.supports(request)) return emptyList()
        if (request is OppoControlRequest.UpdateEqualizer && state.canonicalLongFrames && state.equalizerWriteBudget != null) {
            return OppoSettingsCodec.updateEqualizerPackets(
                state.equalizers!!.single { it.id == request.id }.wire(), request.gains, state.equalizerWriteBudget!!)
        }
        return listOf(when (request) {
            is OppoControlRequest.SetToggle -> OppoSettingsCodec.setSwitch(request.setting.featureId, request.enabled)
            is OppoControlRequest.SelectEqualizer -> OppoSettingsCodec.selectEqualizer(request.id)
            is OppoControlRequest.UpdateEqualizer -> OppoSettingsCodec.updateEqualizer(
                state.equalizers!!.single { it.id == request.id }.wire(), request.gains)
        })
    }
    fun readback(request: OppoControlRequest): List<ByteArray> = when (request) {
        is OppoControlRequest.SetToggle -> listOf(OppoSettingsCodec.querySwitches())
        is OppoControlRequest.SelectEqualizer -> listOf(OppoSettingsCodec.queryCurrentEq, OppoSettingsCodec.queryEqualizers)
        is OppoControlRequest.UpdateEqualizer -> listOf(OppoSettingsCodec.queryEqualizers)
    }
    fun reset() { state = OppoSettingsFeatureState(); reported = false }
}
