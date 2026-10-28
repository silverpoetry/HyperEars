package dev.hyperears.hook

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.hyperears.integration.AppleControlRequest
import dev.hyperears.integration.AppleListeningMode
import dev.hyperears.integration.AppleListeningModeFeatureState
import dev.hyperears.integration.AppleMiLinkPresentationIds
import dev.hyperears.integration.ControlOwnership
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.NoiseMode
import java.lang.ref.WeakReference

/** Apple listening choices use the stock native row and only session-confirmed mode evidence. */
internal object AppleListeningMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId = AppleMiLinkPresentationIds.LISTENING
    override val nativeSurface = MiLinkNativeCardSurface.ANC_THREE_STATE

    // This projection keeps the stock carrier visible; actual selection is owned by this binding.
    override fun nativeSurfaceNoiseMode(state: EarbudState): NoiseMode? =
        when (state.features.get<AppleListeningModeFeatureState>()?.mode) {
            AppleListeningMode.OFF -> NoiseMode.OFF
            AppleListeningMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
            AppleListeningMode.ANC, AppleListeningMode.ADAPTIVE -> NoiseMode.ANC
            null -> null
        }

    override fun bind(root: View, address: String, environment: MiLinkCardEnvironment): MiLinkCardBinding? {
        val row = NativeAncModeRow.resolve(root) ?: return null
        val title = root.findMiLinkView(row.layout.titleId) as? TextView
        val host = NativeAncModeHost(title, row)
        // Construct every item before touching the host. The extra choice borrows the ANC icon only.
        val items = MODES.mapIndexed { index, mode ->
            val source = host.sources[if (index == 3) 1 else index]
            host.createItem(root, source, LABELS.getValue(mode), null) ?: return null
        }
        items.last().id = View.generateViewId()
        val binding = Binding(host, items, address, environment)
        items.forEachIndexed { index, item ->
            item.setOnClickListener { binding.onModeClicked(MODES[index]) }
            item.setChoiceEnabled(false)
            item.setSelectedTree(false)
        }
        items.last().visibility = View.GONE
        if (!host.install(title?.text?.toString() ?: "噪声控制", items)) {
            binding.unbind()
            return null
        }
        return binding
    }

    private class Binding(
        private val host: NativeAncModeHost,
        items: List<View>,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) : MiLinkCardBinding {
        private val items = items.map(::WeakReference)
        private var bound = true

        override fun render(state: EarbudState) {
            if (!bound) return
            val projected = AppleListeningCardPolicy.render(state)
            items.forEachIndexed { index, reference ->
                val view = reference.get() ?: return@forEachIndexed
                val mode = MODES[index]
                view.visibility = if (mode == AppleListeningMode.ADAPTIVE && mode !in projected.supportedModes) {
                    View.GONE
                } else View.VISIBLE
                view.setChoiceEnabled(projected.enabled && mode in projected.supportedModes)
                view.setSelectedTree(mode == projected.mode)
            }
        }

        fun onModeClicked(mode: AppleListeningMode) {
            if (!bound) return
            val request = AppleListeningCardPolicy.request(environment.stateProvider(address), mode) ?: return
            environment.controlSender(address, request)
        }

        override fun unbind() {
            bound = false
            items.mapNotNull(WeakReference<View>::get).forEach { it.setOnClickListener(null) }
            host.restore()
        }
    }

    private fun View.setChoiceEnabled(enabled: Boolean) {
        isEnabled = enabled
        isClickable = enabled
        alpha = if (enabled) 1f else 0.45f
    }

    private fun View.setSelectedTree(selected: Boolean) {
        isSelected = selected
        if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).setSelectedTree(selected)
    }

    private val MODES = listOf(
        AppleListeningMode.TRANSPARENCY, AppleListeningMode.ANC, AppleListeningMode.OFF, AppleListeningMode.ADAPTIVE,
    )
    private val LABELS = mapOf(
        AppleListeningMode.TRANSPARENCY to "通透",
        AppleListeningMode.ANC to "降噪",
        AppleListeningMode.OFF to "关闭",
        AppleListeningMode.ADAPTIVE to "自适应",
    )
}

internal object AppleListeningCardPolicy {
    data class CardState(
        val mode: AppleListeningMode?,
        val supportedModes: Set<AppleListeningMode>,
        val enabled: Boolean,
    )

    fun render(state: EarbudState): CardState {
        val feature = state.features.get<AppleListeningModeFeatureState>()
        return CardState(
            mode = feature?.mode,
            supportedModes = feature?.supportedModes.orEmpty(),
            enabled = state.sessionActive && state.privateChannelConnected && state.handshakeAccepted &&
                state.lifecycle.controlOwnership == ControlOwnership.MODULE && feature != null,
        )
    }

    fun request(state: EarbudState, mode: AppleListeningMode): AppleControlRequest.SetListeningMode? {
        val current = render(state)
        if (!current.enabled || current.mode == mode || mode !in current.supportedModes) return null
        return AppleControlRequest.SetListeningMode(mode)
    }
}
