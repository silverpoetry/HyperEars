package dev.hyperears.hook

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.isVisible
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.StandardControlRequest
import java.lang.ref.WeakReference

/**
 * Adds a protocol-confirmed wind-noise option to MiLink's stock three-state ANC card.
 *
 * Transparency, ANC and Off remain entirely native MiLink actions. WIND is a device-specific
 * variant of the ANC branch and is therefore exposed as a switch beside the native ANC title,
 * not as a fourth peer mode. The switch is enabled only while the authoritative device state is
 * ANC or WIND; its checked state is always rendered from [EarbudState]. The adapter resolves the
 * original ANC row and HyperOS 4's select-card row through their stable resource and View contracts.
 */
internal open class WindNoiseToggleMiLinkCardAdapter(
    final override val presentationId: MiLinkCardPresentationId,
    private val modelLabel: String,
) : MiLinkCardAdapter {

    override fun bind(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        val host = resolveNativeAncCard(root) ?: return null
        val title = host.title
        val ancCard = host.container
        val parent = title.parent as? ViewGroup ?: return null
        val index = parent.indexOfChild(title).takeIf { it >= 0 } ?: return null
        val originalParams = title.layoutParams
        val originalWidth = originalParams.width

        parent.removeViewAt(index)
        val wrapper = FrameLayout(root.context).apply {
            layoutParams = originalParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        title.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        wrapper.addView(title)

        val accessory = LinearLayout(root.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val label = TextView(root.context).apply {
            text = WIND_LABEL
            setTextColor(title.currentTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, title.textSize)
            typeface = title.typeface
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(0, 0, root.context.dp(LABEL_END_PADDING_DP), 0)
        }
        val toggle = createHostToggle(root.context, environment.hostClassLoader).apply {
            contentDescription = WIND_LABEL
            isSaveEnabled = false
        }
        accessory.addView(label)
        accessory.addView(toggle)
        wrapper.addView(
            accessory,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        parent.addView(wrapper, index)

        return Binding(
            parent = parent,
            originalIndex = index,
            originalLayoutParams = originalParams,
            originalWidth = originalWidth,
            wrapper = wrapper,
            title = title,
            ancCard = ancCard,
            accessory = accessory,
            toggle = toggle,
            address = address,
            environment = environment,
        ).also { binding ->
            toggle.setOnCheckedChangeListener(binding::onToggleChanged)
            ModuleLog.debug(
                "MiLinkUi",
                "bound $modelLabel wind-noise switch layout=${host.generation.logName}",
            )
        }
    }

    private class Binding(
        parent: ViewGroup,
        private val originalIndex: Int,
        private val originalLayoutParams: ViewGroup.LayoutParams,
        private val originalWidth: Int,
        wrapper: View,
        title: View,
        ancCard: View,
        accessory: View,
        toggle: CompoundButton,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) : MiLinkCardBinding {
        private val parent = WeakReference(parent)
        private val wrapper = WeakReference(wrapper)
        private val title = WeakReference(title)
        private val ancCard = WeakReference(ancCard)
        private val accessory = WeakReference(accessory)
        private val toggle = WeakReference(toggle)
        private var rendering = false

        override fun render(state: EarbudState) {
            val wrapper = wrapper.get() ?: return
            val title = title.get() ?: return
            val ancCard = ancCard.get() ?: return
            val accessory = accessory.get() ?: return
            val toggle = toggle.get() ?: return

            wrapper.visibility = ancCard.visibility
            accessory.visibility =
                if (ancCard.isVisible && title.isVisible) View.VISIBLE else View.GONE

            val toggleState = WindNoiseToggleControlPolicy.render(state)
            rendering = true
            try {
                toggle.isChecked = toggleState.checked
                toggle.isEnabled = toggleState.enabled
                toggle.alpha = if (toggle.isEnabled) ENABLED_ALPHA else DISABLED_ALPHA
                accessory.alpha = if (toggle.isEnabled) ENABLED_ALPHA else DISABLED_ALPHA
            } finally {
                rendering = false
            }
        }

        fun onToggleChanged(button: CompoundButton, checked: Boolean) {
            if (rendering) return
            val current = environment.stateProvider(address)
            val currentToggleState = WindNoiseToggleControlPolicy.render(current)

            // A UI gesture is only a request. Restore the authoritative value until the device
            // reports the new mode through the normal protocol state pipeline.
            rendering = true
            try {
                button.isChecked = currentToggleState.checked
            } finally {
                rendering = false
            }

            val requestedMode = WindNoiseToggleControlPolicy.request(current, checked) ?: return
            environment.controlSender(
                address,
                StandardControlRequest.SetNoiseMode(requestedMode),
            )
        }

        override fun unbind() {
            val parent = parent.get() ?: return
            val wrapper = wrapper.get() ?: return
            val title = title.get() ?: return
            val toggle = toggle.get()
            toggle?.setOnCheckedChangeListener(null)
            if (wrapper.parent !== parent) return

            (title.parent as? ViewGroup)?.removeView(title)
            parent.removeView(wrapper)
            originalLayoutParams.width = originalWidth
            title.layoutParams = originalLayoutParams
            parent.addView(title, originalIndex.coerceAtMost(parent.childCount))
        }
    }

    private fun createHostToggle(
        context: Context,
        hostClassLoader: ClassLoader,
    ): CompoundButton = runCatching {
        Class.forName(MIUIX_SLIDING_BUTTON, true, hostClassLoader)
            .asSubclass(CompoundButton::class.java)
            .getConstructor(Context::class.java)
            .newInstance(context)
    }.getOrElse {
        @Suppress("DEPRECATION")
        Switch(context)
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun resolveNativeAncCard(root: View): NativeAncCard? {
        // Some MiLink builds keep the legacy ANC views as hidden compatibility placeholders
        // while rendering the select-card generation. Resolve both stable contracts and bind only
        // to the one that is actually presented; otherwise moving the legacy title also hides the
        // wind-noise accessory. Prefer the newer contract when both are visible.
        return listOfNotNull(
            resolveSelectAncCard(root),
            resolveOriginalAncCard(root),
        ).firstOrNull(NativeAncCard::isPresented)
    }

    private fun resolveOriginalAncCard(root: View): NativeAncCard? {
        val originalTitle = root.findMiLinkView(ORIGINAL_ANC_CARD_TITLE_ID) as? TextView
        val originalCard = root.findMiLinkView(ORIGINAL_ANC_CARD_ID)
        if (originalTitle == null || originalCard == null) return null
        return NativeAncCard(
            generation = NativeAncCardGeneration.ORIGINAL,
            title = originalTitle,
            container = originalCard,
        )
    }

    private fun resolveSelectAncCard(root: View): NativeAncCard? {
        val selectTitle = root.findMiLinkView(SELECT_ANC_CARD_TITLE_ID) as? TextView ?: return null
        val selectCard = root.findMiLinkView(SELECT_ANC_CARD_ID) as? LinearLayout ?: return null
        if (selectCard.javaClass.name != SELECT_ANC_CARD_CLASS) return null
        if (selectCard.childCount != NATIVE_MODE_COUNT) return null
        if ((0 until selectCard.childCount).any { index ->
                selectCard.getChildAt(index).javaClass.name != SELECT_ANC_ITEM_CLASS
            }
        ) {
            return null
        }
        return NativeAncCard(
            generation = NativeAncCardGeneration.SELECT_CARD,
            title = selectTitle,
            container = selectCard,
        )
    }

    private data class NativeAncCard(
        val generation: NativeAncCardGeneration,
        val title: TextView,
        val container: View,
    ) {
        fun isPresented(): Boolean = title.isShown && container.isShown
    }

    private enum class NativeAncCardGeneration(val logName: String) {
        ORIGINAL("original"),
        SELECT_CARD("select-card"),
    }

    private companion object {
        const val ORIGINAL_ANC_CARD_TITLE_ID = "anc_card_title"
        const val ORIGINAL_ANC_CARD_ID = "anc_card"
        const val SELECT_ANC_CARD_TITLE_ID = "anc_card_text"
        const val SELECT_ANC_CARD_ID = "anc_select_card"
        const val SELECT_ANC_CARD_CLASS =
            "com.miui.circulate.world.headset.ui.HeadsetSelectCardView"
        const val SELECT_ANC_ITEM_CLASS =
            "com.miui.circulate.world.headset.ui.HeadsetSelectItemView"
        const val NATIVE_MODE_COUNT = 3
        const val MIUIX_SLIDING_BUTTON = "miuix.slidingwidget.widget.SlidingButton"
        const val WIND_LABEL = "抗风噪"
        const val LABEL_END_PADDING_DP = 8
        const val ENABLED_ALPHA = 1.0f
        const val DISABLED_ALPHA = 0.45f
    }
}
/** Pure four-state-to-switch policy; UI code contains no independent mode state. */
internal object WindNoiseToggleControlPolicy {
    data class ToggleState(
        val checked: Boolean,
        val enabled: Boolean,
    )

    fun render(state: EarbudState): ToggleState {
        val inAncBranch = state.noiseMode == NoiseMode.ANC || state.noiseMode == NoiseMode.WIND
        return ToggleState(
            checked = state.noiseMode == NoiseMode.WIND,
            enabled = state.sessionActive && state.connected && inAncBranch,
        )
    }

    fun request(state: EarbudState, checked: Boolean): NoiseMode? {
        if (!render(state).enabled) return null
        return when {
            checked && state.noiseMode == NoiseMode.ANC -> NoiseMode.WIND
            !checked && state.noiseMode == NoiseMode.WIND -> NoiseMode.ANC
            else -> null
        }
    }
}
