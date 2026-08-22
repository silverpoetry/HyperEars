package dev.hyperears.hook

import android.content.Context
import android.graphics.drawable.Drawable
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
        resolveTitleAncCard(root)?.let { host ->
            return bindTitleAccessory(root, host, address, environment)
        }
        resolveEmbeddedAncCard(root)?.let { host ->
            return bindEmbeddedAccessory(root, host, address, environment)
        }
        return null
    }

    private fun bindTitleAccessory(
        root: View,
        host: TitleAncCard,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
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

        val controller = ToggleController(toggle, address, environment)
        return TitleBinding(
            parent = parent,
            originalIndex = index,
            originalLayoutParams = originalParams,
            originalWidth = originalWidth,
            wrapper = wrapper,
            title = title,
            ancCard = ancCard,
            accessory = accessory,
            controller = controller,
        ).also {
            controller.bind()
            ModuleLog.debug(
                "MiLinkUi",
                "bound $modelLabel wind-noise switch layout=${host.generation.logName}",
            )
        }
    }

    private fun bindEmbeddedAccessory(
        root: View,
        host: EmbeddedAncCard,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        val parent = host.container.parent as? ViewGroup ?: return null
        val originalIndex = parent.indexOfChild(host.container).takeIf { it >= 0 } ?: return null
        val originalLayoutParams = host.container.layoutParams
        val originalBackground = host.container.background

        val accessory = LinearLayout(root.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setPadding(
                root.context.dp(EMBEDDED_HEADER_HORIZONTAL_PADDING_DP),
                0,
                root.context.dp(EMBEDDED_HEADER_HORIZONTAL_PADDING_DP),
                0,
            )
        }
        val toggle = createHostToggle(root.context, environment.hostClassLoader).apply {
            contentDescription = WIND_LABEL
            isSaveEnabled = false
        }
        val label = TextView(root.context).apply {
            text = WIND_LABEL
            setTextColor(host.styleSource.textColors)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, host.styleSource.textSize)
            typeface = host.styleSource.typeface
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        accessory.addView(
            label,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        accessory.addView(
            toggle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val wrapper = LinearLayout(root.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = originalLayoutParams
            background = originalBackground
        }
        parent.removeViewAt(originalIndex)
        host.container.background = null
        host.container.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        wrapper.addView(
            accessory,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                root.context.dp(EMBEDDED_HEADER_HEIGHT_DP),
            ),
        )
        wrapper.addView(host.container)
        parent.addView(wrapper, originalIndex)

        val controller = ToggleController(toggle, address, environment)
        return EmbeddedBinding(
            parent = parent,
            originalIndex = originalIndex,
            originalLayoutParams = originalLayoutParams,
            originalBackground = originalBackground,
            wrapper = wrapper,
            ancCard = host.container,
            accessory = accessory,
            controller = controller,
        ).also {
            controller.bind()
            ModuleLog.debug(
                "MiLinkUi",
                "bound $modelLabel wind-noise switch layout=embedded-original",
            )
        }
    }

    private class TitleBinding(
        parent: ViewGroup,
        private val originalIndex: Int,
        private val originalLayoutParams: ViewGroup.LayoutParams,
        private val originalWidth: Int,
        wrapper: View,
        title: View,
        ancCard: View,
        accessory: View,
        private val controller: ToggleController,
    ) : MiLinkCardBinding {
        private val parent = WeakReference(parent)
        private val wrapper = WeakReference(wrapper)
        private val title = WeakReference(title)
        private val ancCard = WeakReference(ancCard)
        private val accessory = WeakReference(accessory)

        override fun render(state: EarbudState) {
            val wrapper = wrapper.get() ?: return
            val title = title.get() ?: return
            val ancCard = ancCard.get() ?: return
            val accessory = accessory.get() ?: return

            wrapper.visibility = ancCard.visibility
            accessory.visibility =
                if (ancCard.isVisible && title.isVisible) View.VISIBLE else View.GONE
            controller.render(state, accessory)
        }

        override fun unbind() {
            val parent = parent.get() ?: return
            val wrapper = wrapper.get() ?: return
            val title = title.get() ?: return
            controller.unbind()
            if (wrapper.parent !== parent) return

            (title.parent as? ViewGroup)?.removeView(title)
            parent.removeView(wrapper)
            originalLayoutParams.width = originalWidth
            title.layoutParams = originalLayoutParams
            parent.addView(title, originalIndex.coerceAtMost(parent.childCount))
        }
    }

    private class EmbeddedBinding(
        parent: ViewGroup,
        private val originalIndex: Int,
        private val originalLayoutParams: ViewGroup.LayoutParams,
        private val originalBackground: Drawable?,
        wrapper: ViewGroup,
        ancCard: LinearLayout,
        accessory: View,
        private val controller: ToggleController,
    ) : MiLinkCardBinding {
        private val parent = WeakReference(parent)
        private val wrapper = WeakReference(wrapper)
        private val ancCard = WeakReference(ancCard)
        private val accessory = WeakReference(accessory)

        override fun render(state: EarbudState) {
            val wrapper = wrapper.get() ?: return
            val ancCard = ancCard.get() ?: return
            val accessory = accessory.get() ?: return
            wrapper.visibility = ancCard.visibility
            accessory.visibility = if (ancCard.isVisible) View.VISIBLE else View.GONE
            controller.render(state, accessory)
        }

        override fun unbind() {
            controller.unbind()
            val parent = parent.get() ?: return
            val wrapper = wrapper.get() ?: return
            val ancCard = ancCard.get() ?: return
            if (wrapper.parent !== parent) return

            (ancCard.parent as? ViewGroup)?.removeView(ancCard)
            parent.removeView(wrapper)
            ancCard.background = originalBackground
            ancCard.layoutParams = originalLayoutParams
            parent.addView(ancCard, originalIndex.coerceAtMost(parent.childCount))
        }
    }

    private class ToggleController(
        toggle: CompoundButton,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) {
        private val toggle = WeakReference(toggle)
        private var rendering = false

        fun bind() {
            toggle.get()?.setOnCheckedChangeListener(::onToggleChanged)
        }

        fun render(state: EarbudState, accessory: View) {
            val toggle = toggle.get() ?: return
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

        fun unbind() {
            toggle.get()?.setOnCheckedChangeListener(null)
        }

        private fun onToggleChanged(button: CompoundButton, checked: Boolean) {
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

    private fun resolveTitleAncCard(root: View): TitleAncCard? =
        resolveSelectAncCard(root) ?: resolveOriginalTitleAncCard(root)

    private fun resolveOriginalTitleAncCard(root: View): TitleAncCard? {
        val originalTitle = root.findMiLinkView(ORIGINAL_ANC_CARD_TITLE_ID) as? TextView
        val originalCard = root.findMiLinkView(ORIGINAL_ANC_CARD_ID)
        if (originalTitle == null || originalCard == null) return null
        return TitleAncCard(
            generation = NativeAncCardGeneration.ORIGINAL,
            title = originalTitle,
            container = originalCard,
        )
    }

    private fun resolveSelectAncCard(root: View): TitleAncCard? {
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
        return TitleAncCard(
            generation = NativeAncCardGeneration.SELECT_CARD,
            title = selectTitle,
            container = selectCard,
        )
    }

    private fun resolveEmbeddedAncCard(root: View): EmbeddedAncCard? {
        val card = root.findMiLinkView(ORIGINAL_ANC_CARD_ID) as? LinearLayout ?: return null
        val transparency = root.findMiLinkView(ORIGINAL_ANC_TRANSPARENCY_ID) ?: return null
        val noiseCancellation =
            root.findMiLinkView(ORIGINAL_ANC_NOISE_CANCELLATION_ID) ?: return null
        val off = root.findMiLinkView(ORIGINAL_ANC_OFF_ID) ?: return null
        val nativeItems = listOf(transparency, noiseCancellation, off)
        if (nativeItems.any { item ->
                item.parent !== card || item.javaClass.name != ORIGINAL_ANC_ITEM_CLASS
            }
        ) {
            return null
        }
        val styleSource = noiseCancellation.findMiLinkView(ORIGINAL_ANC_ITEM_TITLE_ID)
            as? TextView
            ?: return null
        return EmbeddedAncCard(
            container = card,
            styleSource = styleSource,
        )
    }

    private data class TitleAncCard(
        val generation: NativeAncCardGeneration,
        val title: TextView,
        val container: View,
    )

    private data class EmbeddedAncCard(
        val container: LinearLayout,
        val styleSource: TextView,
    )

    private enum class NativeAncCardGeneration(val logName: String) {
        ORIGINAL("original"),
        SELECT_CARD("select-card"),
    }

    private companion object {
        const val ORIGINAL_ANC_CARD_TITLE_ID = "anc_card_title"
        const val ORIGINAL_ANC_CARD_ID = "anc_card"
        const val ORIGINAL_ANC_TRANSPARENCY_ID = "anc_clear"
        const val ORIGINAL_ANC_NOISE_CANCELLATION_ID = "anc_noise_cancel"
        const val ORIGINAL_ANC_OFF_ID = "anc_off"
        const val ORIGINAL_ANC_ITEM_TITLE_ID = "anc_title"
        const val ORIGINAL_ANC_ITEM_CLASS =
            "com.miui.circulate.world.headset.ui.HeadsetControlAncItemView"
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
        const val EMBEDDED_HEADER_HEIGHT_DP = 48
        const val EMBEDDED_HEADER_HORIZONTAL_PADDING_DP = 20
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
