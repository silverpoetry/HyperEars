package dev.hyperears.hook

import android.view.View
import androidx.core.view.isVisible
import android.view.ViewGroup
import android.widget.TextView
import dev.hyperears.integration.*
import java.lang.ref.WeakReference

/** Sony report-confirmed noise controls and a separately gated advanced panel. */
internal object SonyAdvancedMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId = SonyMiLinkPresentationIds.ADVANCED
    override val nativeSurface = MiLinkNativeCardSurface.ANC_THREE_STATE
    override fun nativeSurfaceNoiseMode(state: EarbudState) = state.noiseMode?.let { if (it == NoiseMode.WIND) NoiseMode.ANC else it }
    override fun bind(root: View, address: String, environment: MiLinkCardEnvironment): MiLinkCardBinding? {
        val row = NativeAncModeRow.resolve(root) ?: return null
        val title = root.findMiLinkView(row.layout.titleId) as? TextView
        val host = NativeAncModeHost(title, row)
        val items = listOf(
            host.createItem(root, host.sources[0], "通透", null) ?: return null,
            host.createItem(root, host.sources[1], "降噪", null) ?: return null,
            host.createItem(root, host.sources[2], "关闭", null) ?: return null,
        )
        val binding = Binding(host, items, address, environment)
        items.forEachIndexed { index, item ->
            item.isEnabled = false
            item.setOnClickListener { binding.send(SonyCardPolicy.MODES[index]) }
        }
        if (!host.install("噪声控制", items)) { binding.unbind(); return null }
        binding.entry = AppleSettingsCardEntry.bind(title, row.container, address, environment,
            SonyCardPolicy::available, SonySettingsDetails::open)
        return binding
    }
    private class Binding(
        private val host: NativeAncModeHost, items: List<View>, private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) : MiLinkCardBinding {
        private val items = items.map(::WeakReference)
        private var bound = true
        private var token = environment.sessionTokenProvider(address)
        var entry: AppleSettingsCardEntry? = null
        override fun render(state: EarbudState) {
            if (!bound) return
            token = environment.sessionTokenProvider(address)
            entry?.render(state)
            val ready = token != null && SonyCardPolicy.ready(state, address) && state.noiseMode != null
            items.forEachIndexed { index, reference -> reference.get()?.apply {
                visibility = if (SonyCardPolicy.MODES[index] in state.adapter?.supportedNoiseModes.orEmpty()) View.VISIBLE else View.GONE
                isEnabled = ready && isVisible
                alpha = if (ready) 1f else 0.45f
                selectTree(this, (if (state.noiseMode == NoiseMode.WIND) NoiseMode.ANC else state.noiseMode) == SonyCardPolicy.MODES[index])
            } }
        }
        fun send(mode: NoiseMode) {
            if (!bound) return
            runCatching {
                if (token == null || token != environment.sessionTokenProvider(address)) return
                val state = environment.stateProvider(address)
                SonyCardPolicy.noiseRequest(state, address, mode)?.let { environment.controlSender(address, it) }
            }.onFailure {
                ModuleLog.warn("MiLinkUi", "Sony mode send failed (${it.javaClass.simpleName})")
            }
        }
        override fun unbind() {
            if (!bound) return
            bound = false
            entry?.unbind(); entry = null
            items.mapNotNull { it.get() }.forEach { it.setOnClickListener(null) }
            host.restore()
        }
        private fun selectTree(view: View, selected: Boolean) {
            view.isSelected = selected
            if (view is ViewGroup) for (index in 0 until view.childCount) selectTree(view.getChildAt(index), selected)
        }
    }
}
