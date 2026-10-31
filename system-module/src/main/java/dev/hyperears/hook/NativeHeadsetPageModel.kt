package dev.hyperears.hook

import dev.hyperears.integration.*

internal object NativeHeadsetPageModel {
    fun eligible(state: EarbudState) = CoralNativePageModel.eligible(state)
    fun modes(state: EarbudState) = if (eligible(state) && state.noiseMode in RoseCoralCardPolicy.MODES) RoseCoralCardPolicy.MODES else emptyList()
    fun ready(state: EarbudState, address: String) = RoseCoralCardPolicy.ready(state, address)
    fun noiseRequest(state: EarbudState, address: String, mode: NoiseMode) = RoseCoralCardPolicy.noiseRequest(state, address, mode)
    fun support(address: String, state: EarbudState) = CoralNativePageModel.support(address, state)
    fun battery(state: EarbudState) = CoralNativePageModel.battery(state)
    fun anc(state: EarbudState) = CoralNativePageModel.anc(state)
    fun artwork(state: EarbudState) = HeadsetArtworkId("headset_rose_coral_detail")
}
