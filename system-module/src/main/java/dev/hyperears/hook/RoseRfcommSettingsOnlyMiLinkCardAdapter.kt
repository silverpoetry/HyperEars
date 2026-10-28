package dev.hyperears.hook

import android.view.View
import android.widget.TextView
import dev.hyperears.integration.RoseBudsFeelProtocolFamilyAdapter
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.NoiseMode
import java.lang.ref.WeakReference

/** Reuses the existing stock carrier like FitClip, without asserting real noise capability. */
internal object RoseRfcommSettingsOnlyMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId = RoseBudsFeelProtocolFamilyAdapter.SETTINGS_ONLY_PRESENTATION_ID
    override val nativeSurface = MiLinkNativeCardSurface.ANC_THREE_STATE
    override fun nativeSurfaceNoiseMode(state: EarbudState) = NoiseMode.OFF

    override fun bind(root: View, address: String, environment: MiLinkCardEnvironment): MiLinkCardBinding? {
        val row = NativeAncModeRow.resolve(root) ?: return null
        val host = NativeAncModeHost(root.findMiLinkView(row.layout.titleId) as? TextView, row)
        val item = host.createItem(root, host.sources.first(), "高级设置",
            root.context.getDrawable(android.R.drawable.ic_menu_preferences)) ?: return null
        val binding = Binding(host, item, address, environment)
        item.isEnabled = false
        item.setOnClickListener { binding.open() }
        if (!host.install("耳机设置", listOf(item))) {
            binding.unbind()
            return null
        }
        return binding
    }

    private class Binding(
        private val host: NativeAncModeHost,
        item: View,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) : MiLinkCardBinding {
        private val item = WeakReference(item)
        private var bound = true
        private var token = environment.sessionTokenProvider(address)
        private var details: MiLinkToggleDetails? = null
        private val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { closeDetails() }
        }
        init { item.addOnAttachStateChangeListener(detachListener) }

        override fun render(state: EarbudState) {
            if (!bound) return
            val currentToken = environment.sessionTokenProvider(address)
            if (currentToken != token) { closeDetails(); token = currentToken }
            val enabled = RoseRfcommCardPolicy.available(state, address) && token != null
            item.get()?.apply { isEnabled = enabled; alpha = if (enabled) 1f else 0.45f }
            if (enabled) details?.render(state) else closeDetails()
        }

        fun open() {
            if (!bound) return
            try {
                val anchor = item.get() ?: return
                val state = environment.stateProvider(address)
                if (!RoseRfcommCardPolicy.available(state, address) ||
                    token == null || token != environment.sessionTokenProvider(address)) { closeDetails(); return }
                closeDetails()
                details = RoseRfcommSettingsDetails.open(anchor, address, environment)
            } catch (_: Exception) { closeDetails() }
        }

        private fun closeDetails() { details?.dismiss(); details = null }
        override fun unbind() {
            if (!bound) return
            bound = false
            closeDetails()
            item.get()?.apply {
                setOnClickListener(null)
                removeOnAttachStateChangeListener(detachListener)
            }
            host.restore()
        }
    }
}
