package dev.hyperears.integration

import dev.hyperears.protocol.oppo.OppoSettingsCodec
import dev.hyperears.protocol.oppo.OppoWireCodec

/** Optional settings evidence belongs to the same physical OPPO conversation. */
internal class OppoSettingsSession {
    private var state = OppoSettingsFeatureState()
    private var reported = false
    val noise: OppoNoiseSelection? get() = state.noise
    private var product: Int? = null
    fun spatialQueries(): List<ByteArray> = if (product in OppoLegacySpatialCatalog.products) listOf(switches()) else emptyList()
    private fun switches() = OppoSettingsCodec.querySwitches(OppoSettingsCodec.switchIds +
        if (product in OppoLegacySpatialCatalog.products) setOf(0x1B) else emptySet())
    val initialQueries: List<ByteArray> get() = listOf(switches(), OppoSettingsCodec.queryPromptVolume,
        OppoSettingsCodec.queryCurrentEq, OppoSettingsCodec.queryEqualizers, OppoWireCodec.queryFrameBudget)

    fun identify(productId: Int?): OppoSettingsFeatureState? {
        if (product != productId) state = state.copy(spatialSound=null,spatialProductId=null)
        product = productId
        val builtIns = OppoBuiltInEqIds.byProduct[productId]
        state = state.copy(noise = state.noise?.takeIf { it.definition.productId == productId }, editableEqualizers = if (builtIns == null) emptySet() else
            state.equalizers.orEmpty().filter { it.id !in builtIns &&
                OppoSettingsCodec.canUpdate(it.wire(), it.gains, state.maximumEqualizerPayload()) }.map { it.id }.toSet())
        return state.takeIf { reported }
    }
    fun offer(frame: OppoWireCodec.Frame, productId: Int?): OppoSettingsFeatureState? {
        var changed = false
        OppoSettingsCodec.parsePromptVolume(frame)?.let { state=state.copy(promptVolume=it);changed=true }
        OppoNoiseCatalog.find(productId)?.let { definition ->
            dev.hyperears.protocol.oppo.OppoNoiseCodec.reportIndex(frame)?.let { index ->
                state = state.copy(noise = definition.option(index)?.let { OppoNoiseSelection(definition,index) })
                changed = true
            }
        }
        OppoWireCodec.parseFrameBudget(frame)?.let {
            state = state.copy(equalizerWriteBudget = it)
            // Transport evidence alone is not a user setting and must not create an empty card.
            changed = reported
        }
        // Every switch request asks for the same complete four-field set, so a successful
        // empty or reduced snapshot revokes stale controls. Malformed frames leave evidence alone.
        OppoSettingsCodec.parseSwitches(frame)?.let { values ->
            state = state.copy(spatialSound = values[0x1B].takeIf { productId in OppoLegacySpatialCatalog.products },
                spatialProductId = productId.takeIf { productId in OppoLegacySpatialCatalog.products },
                toggles = values.filterKeys { it in OppoSettingsCodec.switchIds }.mapKeys { entry ->
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
            is OppoControlRequest.SetPromptVolume -> OppoSettingsCodec.setPromptVolume(request.level)
            is OppoControlRequest.SetSpatialSound -> OppoSettingsCodec.setSwitch(0x1B,request.enabled)
            is OppoControlRequest.SelectNoiseOption -> dev.hyperears.protocol.oppo.OppoNoiseCodec.setIndex(request.index)
            is OppoControlRequest.SetToggle -> OppoSettingsCodec.setSwitch(request.setting.featureId, request.enabled)
            is OppoControlRequest.SelectEqualizer -> OppoSettingsCodec.selectEqualizer(request.id)
            is OppoControlRequest.UpdateEqualizer -> OppoSettingsCodec.updateEqualizer(
                state.equalizers!!.single { it.id == request.id }.wire(), request.gains)
        })
    }
    fun readback(request: OppoControlRequest): List<ByteArray> = when (request) {
        is OppoControlRequest.SetPromptVolume -> listOf(OppoSettingsCodec.queryPromptVolume)
        is OppoControlRequest.SetSpatialSound -> listOf(switches())
        is OppoControlRequest.SelectNoiseOption -> listOf(OppoWireCodec.queryAnc)
        is OppoControlRequest.SetToggle -> listOf(switches())
        is OppoControlRequest.SelectEqualizer -> listOf(OppoSettingsCodec.queryCurrentEq, OppoSettingsCodec.queryEqualizers)
        is OppoControlRequest.UpdateEqualizer -> listOf(OppoSettingsCodec.queryEqualizers)
    }
    fun reset() { state = OppoSettingsFeatureState(); reported = false; product=null }
}
