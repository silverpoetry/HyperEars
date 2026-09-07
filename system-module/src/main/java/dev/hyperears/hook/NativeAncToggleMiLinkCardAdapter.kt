package dev.hyperears.hook

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
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
import dev.hyperears.integration.ControlRequest
import dev.hyperears.integration.MiLinkCardPresentationId
import java.lang.ref.WeakReference

internal data class MiLinkToggleState(
    val checked: Boolean,
    val enabled: Boolean,
    val available: Boolean = true,
)

/** Model policy supplies state and requests; the shared host owns views and their lifecycle. */
internal interface MiLinkToggleSpec {
    val label: String
    fun render(state: EarbudState): MiLinkToggleState
    fun request(state: EarbudState, checked: Boolean): ControlRequest?
}

/** Native ANC layout and restoration shared by model-owned, protocol-confirmed toggle options. */
internal open class NativeAncToggleMiLinkCardAdapter(
    final override val presentationId: MiLinkCardPresentationId,
    private val modelLabel: String,
    private val toggles: List<MiLinkToggleSpec>,
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
        val originalTitleVisibility = title.visibility

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
        // Multiple options occupy the existing title row rather than overlapping its caption.
        if (toggles.size > 1) title.visibility = View.INVISIBLE

        val accessory = LinearLayout(root.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val controller = createControls(accessory, title, address, environment, fillWidth = toggles.size > 1)
        wrapper.addView(
            accessory,
            FrameLayout.LayoutParams(
                if (toggles.size > 1) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        parent.addView(wrapper, index)

        return TitleBinding(
            parent = parent,
            originalIndex = index,
            originalLayoutParams = originalParams,
            originalWidth = originalWidth,
            originalTitleVisibility = originalTitleVisibility,
            replacesTitle = toggles.size > 1,
            wrapper = wrapper,
            title = title,
            ancCard = ancCard,
            accessory = accessory,
            controller = controller,
        ).also {
            controller.bind()
            ModuleLog.debug(
                "MiLinkUi",
                "bound $modelLabel options=${toggles.map { it.label }} layout=${host.generation.logName}",
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
        val controller = createControls(accessory, host.styleSource, address, environment, fillWidth = true)

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
                "bound $modelLabel options=${toggles.map { it.label }} layout=embedded-original",
            )
        }
    }

    private class TitleBinding(
        parent: ViewGroup,
        private val originalIndex: Int,
        private val originalLayoutParams: ViewGroup.LayoutParams,
        private val originalWidth: Int,
        private val originalTitleVisibility: Int,
        private val replacesTitle: Boolean,
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
                if (ancCard.isVisible && (replacesTitle || title.isVisible)) View.VISIBLE else View.GONE
            controller.render(state)
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
            title.visibility = originalTitleVisibility
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
            controller.render(state)
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

    private fun createControls(
        accessory: LinearLayout,
        style: TextView,
        address: String,
        environment: MiLinkCardEnvironment,
        fillWidth: Boolean,
    ): ToggleController {
        val entries = toggles.mapIndexed { index, spec ->
            val group = LinearLayout(accessory.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (index > 0) setPadding(context.dp(LABEL_END_PADDING_DP), 0, 0, 0)
            }
            val label = TextView(accessory.context).apply {
                text = spec.label
                setTextColor(style.textColors)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, style.textSize)
                typeface = style.typeface
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(0, 0, context.dp(LABEL_END_PADDING_DP), 0)
            }
            val toggle = createHostToggle(accessory.context, environment.hostClassLoader).apply {
                contentDescription = spec.label
                isSaveEnabled = false
            }
            group.addView(label, LinearLayout.LayoutParams(
                if (fillWidth) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (fillWidth) 1f else 0f,
            ))
            group.addView(toggle)
            accessory.addView(group, LinearLayout.LayoutParams(
                if (fillWidth) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (fillWidth) 1f else 0f,
            ))
            ToggleEntry(spec, WeakReference(group), WeakReference(toggle))
        }
        return ToggleController(entries, address, environment)
    }

    private data class ToggleEntry(
        val spec: MiLinkToggleSpec,
        val group: WeakReference<View>,
        val toggle: WeakReference<CompoundButton>,
    )

    private class ToggleController(
        private val entries: List<ToggleEntry>,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) {
        private var rendering = false

        fun bind() {
            entries.forEach { entry ->
                entry.toggle.get()?.setOnCheckedChangeListener { button, checked ->
                    onToggleChanged(entry.spec, button, checked)
                }
            }
        }

        fun render(state: EarbudState) {
            rendering = true
            try {
                entries.forEach { entry ->
                    val toggle = entry.toggle.get() ?: return@forEach
                    val value = entry.spec.render(state)
                    entry.group.get()?.visibility = if (value.available) View.VISIBLE else View.GONE
                    entry.group.get()?.alpha = if (value.enabled) ENABLED_ALPHA else DISABLED_ALPHA
                    toggle.isChecked = value.checked
                    toggle.isEnabled = value.available && value.enabled
                }
            } finally {
                rendering = false
            }
        }

        fun unbind() {
            entries.forEach { it.toggle.get()?.setOnCheckedChangeListener(null) }
        }

        private fun onToggleChanged(spec: MiLinkToggleSpec, button: CompoundButton, checked: Boolean) {
            if (rendering) return
            val current = environment.stateProvider(address)
            val currentToggleState = spec.render(current)

            // A UI gesture is only a request. Restore the authoritative value until the device
            // reports the new mode through the normal protocol state pipeline.
            rendering = true
            try {
                button.isChecked = currentToggleState.checked
            } finally {
                rendering = false
            }

            if (!currentToggleState.available || !currentToggleState.enabled) return
            val request = spec.request(current, checked) ?: return
            environment.controlSender(
                address,
                request,
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
        const val LABEL_END_PADDING_DP = 8
        const val EMBEDDED_HEADER_HEIGHT_DP = 48
        const val EMBEDDED_HEADER_HORIZONTAL_PADDING_DP = 20
        const val ENABLED_ALPHA = 1.0f
        const val DISABLED_ALPHA = 0.45f
    }
}
