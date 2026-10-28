package dev.hyperears.hook

import android.view.View
import android.widget.TextView
import dev.hyperears.integration.*

/** Decorates the stock three-mode host; its original buttons and dispatch stay intact. */
internal object HonorAncDepthMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId = HonorX5sProAdapter.PRESENTATION_ID
    override val nativeSurface = MiLinkNativeCardSurface.ANC_THREE_STATE
    override fun nativeSurfaceNoiseMode(state: EarbudState) = state.noiseMode
    override fun bind(root: View, address: String, environment: MiLinkCardEnvironment): MiLinkCardBinding? {
        val row = NativeAncModeRow.resolve(root) ?: return null
        val entry = AppleSettingsCardEntry.bind(root.findMiLinkView(row.layout.titleId) as? TextView,
            row.container, address, environment, HonorAncDepthPolicy::available, HonorAncDepthDetails::open) ?: return null
        return object : MiLinkCardBinding {
            override fun render(state: EarbudState) {
                runCatching { entry.render(state) }.onFailure { entry.unbind() }
            }
            override fun unbind() = entry.unbind()
        }
    }
}

internal object HonorAncDepthPolicy {
    fun ready(state: EarbudState, address: String) = state.address.equals(address, true) && state.adapter?.presentationId == HonorX5sProAdapter.PRESENTATION_ID && state.sessionActive &&
        state.privateChannelConnected && state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE
    fun available(state: EarbudState, address: String) = ready(state, address) &&
        state.features.get<HonorAncDepthFeatureState>()?.supported?.isNotEmpty() == true
    fun request(state: EarbudState, address: String, depth: HonorAncDepth): HonorControlRequest.SetAncDepth? {
        val feature = state.features.get<HonorAncDepthFeatureState>() ?: return null
        return HonorControlRequest.SetAncDepth(depth).takeIf { available(state, address) &&
            state.noiseMode == NoiseMode.ANC && feature.current in feature.supported && depth in feature.supported && depth != feature.current }
    }
}
