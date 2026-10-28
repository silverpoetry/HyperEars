package dev.hyperears.hook

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.hyperears.integration.*
import java.lang.ref.WeakReference

/** Native noise row plus model settings; original children restored on teardown. */
internal object RoseRsV2MiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId = RoseRsV2PresentationIds.SETTINGS
    override val nativeSurface = MiLinkNativeCardSurface.ANC_THREE_STATE
    override fun nativeSurfaceNoiseMode(state: EarbudState) = state.noiseMode ?: NoiseMode.OFF
    private val modes = listOf(NoiseMode.TRANSPARENCY, NoiseMode.ANC, NoiseMode.OFF, NoiseMode.WIND)
    override fun bind(root: View, address: String, environment: MiLinkCardEnvironment): MiLinkCardBinding? {
        val row = NativeAncModeRow.resolve(root) ?: return null
        val title = root.findMiLinkView(row.layout.titleId) as? TextView
        val host = NativeAncModeHost(title, row)
        val labels = listOf("通透", "降噪", "关闭", "抗风噪", "高级设置")
        val items = labels.mapIndexed { i, label ->
            host.createItem(root, host.sources[i % 3], label, null) ?: return null
        }
        items[3].id = View.generateViewId()
        items.last().id = View.generateViewId()
        items.forEach { it.isEnabled = false }
        if (!host.install("耳机设置", items)) return null
        return object : MiLinkCardBinding {
            private var bound = true
            private var token = environment.sessionTokenProvider(address)
            private var details: MiLinkToggleDetails? = null
            private val views = items.map(::WeakReference)
            private val detach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { details?.dismiss(); details = null }
            }
            init {
                items.last().addOnAttachStateChangeListener(detach)
                items.forEachIndexed { i, item -> item.setOnClickListener { guarded {
                    val state = environment.stateProvider(address)
                    if (!bound || token == null || token != environment.sessionTokenProvider(address) ||
                        !RoseRsV2SettingsPolicy.available(state, address)) return@guarded
                    if (i == modes.size) {
                        if (state.features.get<RoseRsV2SettingsFeatureState>()?.hasSettings != true) return@guarded
                        details?.dismiss()
                        details = RoseRsV2SettingsDetails.open(item, address, environment)
                    } else if (state.noiseMode != null && modes[i] in state.adapter?.supportedNoiseModes.orEmpty()) {
                        environment.controlSender(address, StandardControlRequest.SetNoiseMode(modes[i]))
                    }
                } } }
            }
            private inline fun guarded(action: () -> Unit) {
                try { action() } catch (error: Exception) {
                    ModuleLog.warn("MiLinkUi", "ROSE v2 card callback failed: ${error.javaClass.simpleName}")
                    details?.dismiss(); details = null
                    views.forEach { it.get()?.isEnabled = false }
                }
            }
            override fun render(state: EarbudState) = guarded { renderAccepted(state) }
            private fun renderAccepted(state: EarbudState) {
                if (!bound) return
                val current = environment.sessionTokenProvider(address)
                if (token != current) { details?.dismiss(); details = null; token = current }
                val enabled = token != null && RoseRsV2SettingsPolicy.available(state, address)
                if (!enabled) { details?.dismiss(); details = null } else details?.render(state)
                views.forEachIndexed { i, ref -> ref.get()?.apply {
                    val visible = if (i == modes.size) state.features.get<RoseRsV2SettingsFeatureState>()?.hasSettings == true
                        else state.noiseMode != null && modes[i] in state.adapter?.supportedNoiseModes.orEmpty()
                    visibility = if (visible) View.VISIBLE else View.GONE
                    isEnabled = enabled && visible
                    alpha = if (isEnabled) 1f else 0.45f
                    selectTree(i < modes.size && modes[i] == state.noiseMode)
                } }
            }
            override fun unbind() {
                if (!bound) return
                bound = false
                details?.dismiss(); details = null
                views.forEach { it.get()?.setOnClickListener(null) }
                views.last().get()?.removeOnAttachStateChangeListener(detach)
                host.restore()
            }
        }
    }
    private fun View.selectTree(value: Boolean) {
        isSelected = value
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).selectTree(value)
    }
}

internal object RoseRsV2SettingsPolicy {
    fun available(state: EarbudState, address: String): Boolean =
        state.address.equals(address, true) && state.adapter?.presentationId == RoseRsV2PresentationIds.SETTINGS &&
            state.sessionActive && state.privateChannelConnected && state.handshakeAccepted &&
            state.lifecycle.controlOwnership == ControlOwnership.MODULE &&
            (state.features.get<RoseRsV2SettingsFeatureState>()?.hasSettings == true ||
                (state.noiseMode != null && state.adapter?.supportedNoiseModes?.isNotEmpty() == true))
    fun request(state: EarbudState, address: String, request: RoseRsV2ControlRequest): RoseRsV2ControlRequest? =
        request.takeIf { available(state, address) &&
            state.features.get<RoseRsV2SettingsFeatureState>()?.supports(request,state.noiseMode) == true }
}
