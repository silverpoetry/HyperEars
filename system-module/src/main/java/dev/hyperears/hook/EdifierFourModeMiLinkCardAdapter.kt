package dev.hyperears.hook

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.EdifierMiLinkPresentationIds
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.StandardControlRequest
import java.lang.ref.WeakReference

/**
 * Presents a protocol-confirmed four-mode headset through MiLink's native ANC row.
 *
 * MiLink transports WIND through its ANC branch, so leaving the visible stock ANC item attached
 * would select ANC and WIND at the same time. Following the native-slot pattern used by the other
 * model presentations, this adapter replaces only that conflicting visible slot with an instance
 * of MiLink's stable native item class and appends one native WIND item. Transparency and Off stay
 * entirely native. One binding renders all four selections from [EarbudState]; it performs no
 * polling or Bluetooth work.
 */
internal object EdifierFourModeMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId = EdifierMiLinkPresentationIds.FOUR_MODE

    override fun bind(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        val ancCard = root.findMiLinkView(ANC_CARD_ID) as? LinearLayout ?: return null
        val transparency = root.findMiLinkView(ANC_TRANSPARENCY_ID) ?: return null
        val originalAnc = root.findMiLinkView(ANC_NOISE_CANCELLATION_ID) ?: return null
        val off = root.findMiLinkView(ANC_OFF_ID) ?: return null
        if (listOf(transparency, originalAnc, off).any { it.parent !== ancCard }) return null

        val originalAncIndex = ancCard.indexOfChild(originalAnc).takeIf { it >= 0 } ?: return null
        val visibleAnc = createNativeItem(
            root = root,
            environment = environment,
            template = originalAnc,
            id = originalAnc.id,
        ) ?: return null
        val wind = createNativeItem(
            root = root,
            environment = environment,
            template = originalAnc,
            id = View.generateViewId(),
            titleOverride = WIND_LABEL,
        ) ?: return null

        ancCard.removeViewAt(originalAncIndex)
        ancCard.addView(visibleAnc, originalAncIndex)
        ancCard.addView(wind)

        val binding = Binding(
            parent = ancCard,
            originalAnc = originalAnc,
            originalAncIndex = originalAncIndex,
            transparency = transparency,
            visibleAnc = visibleAnc,
            off = off,
            wind = wind,
            address = address,
            environment = environment,
        )
        visibleAnc.setOnClickListener { binding.onOwnedModeClick(NoiseMode.ANC) }
        wind.setOnClickListener { binding.onOwnedModeClick(NoiseMode.WIND) }
        ModuleLog.debug("MiLinkUi", "bound Edifier native four-mode presentation")
        return binding
    }

    private class Binding(
        parent: LinearLayout,
        originalAnc: View,
        private val originalAncIndex: Int,
        transparency: View,
        visibleAnc: View,
        off: View,
        wind: View,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) : MiLinkCardBinding {
        private val parent = WeakReference(parent)
        private val originalAnc = WeakReference(originalAnc)
        private val transparency = WeakReference(transparency)
        private val visibleAnc = WeakReference(visibleAnc)
        private val off = WeakReference(off)
        private val wind = WeakReference(wind)

        override fun render(state: EarbudState) {
            transparency.get()?.setSelectedTree(state.noiseMode == NoiseMode.TRANSPARENCY)
            visibleAnc.get()?.applyOwnedState(state, NoiseMode.ANC)
            off.get()?.setSelectedTree(state.noiseMode == NoiseMode.OFF)
            wind.get()?.applyOwnedState(state, NoiseMode.WIND)
        }

        fun onOwnedModeClick(requestedMode: NoiseMode) {
            val current = environment.stateProvider(address)
            val target = EdifierFourModeControlPolicy.request(current, requestedMode) ?: return
            environment.controlSender(
                address,
                StandardControlRequest.SetNoiseMode(target),
            )
        }

        override fun unbind() {
            val parent = parent.get() ?: return
            val originalAnc = originalAnc.get() ?: return
            listOf(visibleAnc.get(), wind.get()).filterNotNull().forEach { view ->
                view.setOnClickListener(null)
                if (view.parent === parent) parent.removeView(view)
            }
            if (originalAnc.parent == null) {
                parent.addView(originalAnc, originalAncIndex.coerceAtMost(parent.childCount))
            }
        }

        private fun View.applyOwnedState(state: EarbudState, mode: NoiseMode) {
            setSelectedTree(state.noiseMode == mode)
            val enabled = state.sessionActive && state.connected
            isEnabled = enabled
            isClickable = enabled
            alpha = if (enabled) ENABLED_ALPHA else DISABLED_ALPHA
        }
    }

    private fun createNativeItem(
        root: View,
        environment: MiLinkCardEnvironment,
        template: View,
        id: Int,
        titleOverride: String? = null,
    ): View? {
        val item = createNativeMiLinkAncItem(
            context = root.context,
            hostClassLoader = environment.hostClassLoader,
            layoutTemplate = template,
        ) ?: return null
        val sourceTitle = template.findMiLinkView(ANC_TITLE_ID) as? TextView ?: return null
        val sourceIcon = template.findMiLinkView(ANC_ICON_ID) as? ImageView ?: return null
        val itemTitle = item.findMiLinkView(ANC_TITLE_ID) as? TextView ?: return null
        val itemIcon = item.findMiLinkView(ANC_ICON_ID) as? ImageView ?: return null

        item.id = id
        itemTitle.text = titleOverride ?: sourceTitle.text
        itemIcon.setImageDrawable(sourceIcon.drawable.copyFor(root))
        item.contentDescription = titleOverride ?: template.contentDescription ?: sourceTitle.text
        item.visibility = template.visibility
        item.isSaveEnabled = false
        item.isFocusable = true
        return item
    }

    private fun Drawable?.copyFor(root: View): Drawable? =
        this?.constantState?.newDrawable(root.resources)?.mutate() ?: this

    private fun View.setSelectedTree(selected: Boolean) {
        isSelected = selected
        if (this !is ViewGroup) return
        for (index in 0 until childCount) getChildAt(index).setSelectedTree(selected)
    }

    private const val ANC_CARD_ID = "anc_card"
    private const val ANC_TRANSPARENCY_ID = "anc_clear"
    private const val ANC_NOISE_CANCELLATION_ID = "anc_noise_cancel"
    private const val ANC_OFF_ID = "anc_off"
    private const val ANC_TITLE_ID = "anc_title"
    private const val ANC_ICON_ID = "anc_icon"
    private const val WIND_LABEL = "抗风噪"
    private const val ENABLED_ALPHA = 1.0f
    private const val DISABLED_ALPHA = 0.45f
}

/** Pure request policy for owned four-mode items; the binding retains no independent state. */
internal object EdifierFourModeControlPolicy {
    private val supportedModes = setOf(
        NoiseMode.ANC,
        NoiseMode.WIND,
        NoiseMode.TRANSPARENCY,
        NoiseMode.OFF,
    )

    fun request(state: EarbudState, requestedMode: NoiseMode): NoiseMode? {
        if (!state.sessionActive || !state.connected) return null
        if (requestedMode !in supportedModes || requestedMode == state.noiseMode) return null
        return requestedMode
    }
}
