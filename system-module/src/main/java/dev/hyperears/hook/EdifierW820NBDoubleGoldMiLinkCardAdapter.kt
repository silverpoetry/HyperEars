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
import dev.hyperears.integration.EdifierControlRequest
import dev.hyperears.integration.EdifierMiLinkPresentationIds
import dev.hyperears.integration.GameModeFeatureState
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.NoiseMode
import java.lang.ref.WeakReference

/**
 * W820NB 双金标版 MiLink card presentation.
 *
 * Two responsibilities, both rendered only from authoritative [EarbudState]:
 *
 *  1. A "游戏模式" switch beside the native ANC title (verified protocol: query `0x08`, set `0x09`).
 *  2. Current noise-mode highlight on the stock three-state card. The ROM renders the native
 *     buttons but has no state for private-channel devices, so the active button is projected
 *     from the module's confirmed noise mode instead of the ROM's own model.
 *
 * The native transparency / ANC / off actions stay untouched; only their selection visuals are
 * driven from module state.
 */
internal object EdifierW820NBDoubleGoldMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId: MiLinkCardPresentationId =
        EdifierMiLinkPresentationIds.W820NB

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
            text = GAME_MODE_LABEL
            setTextColor(title.currentTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, title.textSize)
            typeface = title.typeface
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(0, 0, root.context.dp(LABEL_END_PADDING_DP), 0)
        }
        val toggle = createHostToggle(root.context, environment.hostClassLoader).apply {
            contentDescription = GAME_MODE_LABEL
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

        val controller = GameToggleController(toggle, address, environment)
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
            nativeItems = resolveNativeItems(host),
        ).also {
            controller.bind()
            ModuleLog.debug(
                "MiLinkUi",
                "bound W820NB 双金标版 game-mode switch layout=${host.generation.logName}",
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
            contentDescription = GAME_MODE_LABEL
            isSaveEnabled = false
        }
        val label = TextView(root.context).apply {
            text = GAME_MODE_LABEL
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

        val controller = GameToggleController(toggle, address, environment)
        return EmbeddedBinding(
            parent = parent,
            originalIndex = originalIndex,
            originalLayoutParams = originalLayoutParams,
            originalBackground = originalBackground,
            wrapper = wrapper,
            ancCard = host.container,
            accessory = accessory,
            controller = controller,
            nativeItems = resolveNativeItems(host),
        ).also {
            controller.bind()
            ModuleLog.debug(
                "MiLinkUi",
                "bound W820NB 双金标版 game-mode switch layout=embedded-original",
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
        private val controller: GameToggleController,
        private val nativeItems: NativeAncItems,
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
            nativeItems.render(state)
        }

        override fun unbind() {
            val parent = parent.get() ?: return
            val wrapper = wrapper.get() ?: return
            val title = title.get() ?: return
            controller.unbind()
            nativeItems.clear()
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
        private val originalBackground: android.graphics.drawable.Drawable?,
        wrapper: ViewGroup,
        ancCard: LinearLayout,
        accessory: View,
        private val controller: GameToggleController,
        private val nativeItems: NativeAncItems,
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
            nativeItems.render(state)
        }

        override fun unbind() {
            controller.unbind()
            nativeItems.clear()
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

    private class GameToggleController(
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
            val toggleState = EdifierGameModeControlPolicy.render(state)
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
            if (!EdifierGameModeControlPolicy.render(current).enabled) return

            // A UI gesture is only a request. Restore the authoritative value until the device
            // reports the new state through the normal protocol state pipeline.
            rendering = true
            try {
                button.isChecked = EdifierGameModeControlPolicy.render(current).checked
            } finally {
                rendering = false
            }
            environment.controlSender(address, EdifierControlRequest.SetGameMode(checked))
        }
    }

    /** Current-mode highlight projection onto the stock three-state items (best effort). */
    /**
     * Current-mode highlight projection onto the stock three-state items.
     *
     * The ROM renders the native buttons but has no state for private-channel devices, and its
     * item views do not visibly react to `isSelected`/`isActivated`. The active mode is therefore
     * shown with a universal alpha contrast: the selected item stays at full alpha while the other
     * two are dimmed. Works regardless of the ROM's selector drawables.
     */
    private class NativeAncItems(
        private val items: List<Pair<View, NoiseMode>>,
    ) {
        fun render(state: EarbudState) {
            val current = state.noiseMode?.let { mode ->
                when (mode) {
                    NoiseMode.ANC, NoiseMode.WIND -> NoiseMode.ANC
                    else -> mode
                }
            }
            items.forEach { (view, itemMode) ->
                val selected = current == itemMode
                view.isSelected = selected
                view.isActivated = selected
                view.alpha = if (selected) SELECTED_ALPHA else UNSELECTED_ALPHA
            }
        }

        fun clear() {
            items.forEach { (view, _) ->
                view.isSelected = false
                view.isActivated = false
                view.alpha = 1.0f
            }
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

    private fun resolveNativeItems(host: TitleAncCard): NativeAncItems = when (host.generation) {
        NativeAncCardGeneration.ORIGINAL -> nativeItemsFor(host.container)
        NativeAncCardGeneration.SELECT_CARD ->
            selectNativeItemsFor(host.container as? LinearLayout)
    }

    private fun resolveNativeItems(host: EmbeddedAncCard): NativeAncItems =
        nativeItemsFor(host.container)

    private fun nativeItemsFor(container: View): NativeAncItems {
        val noiseCancellation = container.findMiLinkView(ORIGINAL_ANC_NOISE_CANCELLATION_ID)
        val transparency = container.findMiLinkView(ORIGINAL_ANC_TRANSPARENCY_ID)
        val off = container.findMiLinkView(ORIGINAL_ANC_OFF_ID)
        return NativeAncItems(
            buildList {
                noiseCancellation?.let { add(it to NoiseMode.ANC) }
                transparency?.let { add(it to NoiseMode.TRANSPARENCY) }
                off?.let { add(it to NoiseMode.OFF) }
            },
        )
    }

    /** Select-card items carry no stable ids; match their visible labels instead. */
    private fun selectNativeItemsFor(container: LinearLayout?): NativeAncItems {
        if (container == null) return NativeAncItems(emptyList())
        val items = mutableListOf<Pair<View, NoiseMode>>()
        for (index in 0 until container.childCount) {
            val item = container.getChildAt(index)
            val text = findDescendantText(item) ?: continue
            val mode = when {
                text.contains(NOISE_CANCELLATION_TEXT) -> NoiseMode.ANC
                text.contains(TRANSPARENCY_TEXT) -> NoiseMode.TRANSPARENCY
                text.contains(OFF_TEXT) || text.contains(STANDARD_TEXT) -> NoiseMode.OFF
                else -> null
            } ?: continue
            items += item to mode
        }
        return NativeAncItems(items)
    }

    private fun findDescendantText(view: View): String? {
        if (view is TextView) return view.text?.toString()?.trim().orEmpty().takeIf { it.isNotEmpty() }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findDescendantText(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

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

    private const val ORIGINAL_ANC_CARD_TITLE_ID = "anc_card_title"
    private const val ORIGINAL_ANC_CARD_ID = "anc_card"
    private const val ORIGINAL_ANC_TRANSPARENCY_ID = "anc_clear"
    private const val ORIGINAL_ANC_NOISE_CANCELLATION_ID = "anc_noise_cancel"
    private const val ORIGINAL_ANC_OFF_ID = "anc_off"
    private const val ORIGINAL_ANC_ITEM_TITLE_ID = "anc_title"
    private const val ORIGINAL_ANC_ITEM_CLASS =
        "com.miui.circulate.world.headset.ui.HeadsetControlAncItemView"
    private const val SELECT_ANC_CARD_TITLE_ID = "anc_card_text"
    private const val SELECT_ANC_CARD_ID = "anc_select_card"
    private const val SELECT_ANC_CARD_CLASS =
        "com.miui.circulate.world.headset.ui.HeadsetSelectCardView"
    private const val SELECT_ANC_ITEM_CLASS =
        "com.miui.circulate.world.headset.ui.HeadsetSelectItemView"
    private const val NATIVE_MODE_COUNT = 3
    private const val MIUIX_SLIDING_BUTTON = "miuix.slidingwidget.widget.SlidingButton"
    private const val GAME_MODE_LABEL = "游戏模式"
    private const val LABEL_END_PADDING_DP = 8
    private const val EMBEDDED_HEADER_HEIGHT_DP = 48
    private const val EMBEDDED_HEADER_HORIZONTAL_PADDING_DP = 20
    private const val ENABLED_ALPHA = 1.0f
    private const val DISABLED_ALPHA = 0.45f
    private const val SELECTED_ALPHA = 1.0f
    private const val UNSELECTED_ALPHA = 0.45f
    private const val NOISE_CANCELLATION_TEXT = "降噪"
    private const val TRANSPARENCY_TEXT = "通透"
    private const val OFF_TEXT = "关闭"
    private const val STANDARD_TEXT = "标准"
}

/** Pure game-mode-to-switch policy; UI code contains no independent mode state. */
internal object EdifierGameModeControlPolicy {
    data class ToggleState(
        val checked: Boolean,
        val enabled: Boolean,
    )

    fun render(state: EarbudState): ToggleState {
        val gameState = state.features.get<GameModeFeatureState>()
        return ToggleState(
            checked = gameState?.enabled == true,
            enabled = state.sessionActive && state.connected && gameState != null,
        )
    }
}
