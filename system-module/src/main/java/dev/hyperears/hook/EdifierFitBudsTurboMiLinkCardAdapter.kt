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
import dev.hyperears.integration.EdifierControlRequest
import dev.hyperears.integration.EdifierGameModeFeatureState
import dev.hyperears.integration.EdifierMiLinkPresentationIds
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.StandardControlRequest
import java.lang.ref.WeakReference

/**
 * FitBuds Turbo-specific MiLink card: the ANC four-mode card plus a game-mode switch.
 *
 * Transparency, ANC and Off remain entirely native MiLink actions. A WIND switch sits beside the
 * native ANC title (same accessory contract as the generic [WindNoiseToggleMiLinkCardAdapter]),
 * and an additional game-mode switch is rendered next to it. Game mode is delivered through the
 * Edifier low-latency request [EdifierControlRequest.SetGameMode], exactly as the FitClip Ultra
 * game-mode card does.
 *
 * The stock section remains the sole owner of dimensions, spacing, clipping and animations; this
 * adapter only adds two accessory switches beside the native title. Original layout is restored on
 * unbind. No height override, host method, obfuscated field, or delayed layout mutation is used.
 */
internal object EdifierFitBudsTurboMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId: MiLinkCardPresentationId =
        EdifierMiLinkPresentationIds.FITBUDS_TURBO
    override val nativeSurface: MiLinkNativeCardSurface =
        MiLinkNativeCardSurface.ANC_THREE_STATE

    override fun nativeSurfaceNoiseMode(state: EarbudState): NoiseMode? =
        state.noiseMode

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
        val windLabel = accessoryLabel(root.context, title, WIND_LABEL)
        val windToggle = createHostToggle(root.context, environment.hostClassLoader).apply {
            contentDescription = WIND_LABEL
            isSaveEnabled = false
        }
        accessory.addView(windLabel)
        accessory.addView(windToggle)

        val gameLabel = accessoryLabel(
            root.context,
            title,
            GAME_MODE_LABEL,
            startPaddingDp = GAME_LABEL_START_PADDING_DP,
        )
        val gameToggle = createHostToggle(root.context, environment.hostClassLoader).apply {
            contentDescription = GAME_MODE_LABEL
            isSaveEnabled = false
        }
        accessory.addView(gameLabel)
        accessory.addView(gameToggle)

        wrapper.addView(
            accessory,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        parent.addView(wrapper, index)

        val controller = FitBudsTurboController(windToggle, gameToggle, address, environment)
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
                COMPONENT,
                "bound FitBuds Turbo wind+game switches layout=${host.generation.logName} " +
                    "address=${maskBluetoothAddress(address)}",
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
        val windToggle = createHostToggle(root.context, environment.hostClassLoader).apply {
            contentDescription = WIND_LABEL
            isSaveEnabled = false
        }
        val windLabel = embeddedLabel(root.context, host, WIND_LABEL)
        accessory.addView(
            windLabel,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        accessory.addView(
            windToggle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val gameToggle = createHostToggle(root.context, environment.hostClassLoader).apply {
            contentDescription = GAME_MODE_LABEL
            isSaveEnabled = false
        }
        val gameLabel = embeddedLabel(root.context, host, GAME_MODE_LABEL)
        accessory.addView(
            gameLabel,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        accessory.addView(
            gameToggle,
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

        val controller = FitBudsTurboController(windToggle, gameToggle, address, environment)
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
                COMPONENT,
                "bound FitBuds Turbo wind+game switches layout=embedded-original " +
                    "address=${maskBluetoothAddress(address)}",
            )
        }
    }

    private fun accessoryLabel(
        context: Context,
        styleSource: TextView,
        text: String,
        startPaddingDp: Int = 0,
        endPaddingDp: Int = LABEL_END_PADDING_DP,
    ): TextView = TextView(context).apply {
        this.text = text
        setTextColor(styleSource.currentTextColor)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, styleSource.textSize)
        typeface = styleSource.typeface
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setPadding(
            context.dp(startPaddingDp),
            0,
            context.dp(endPaddingDp),
            0,
        )
    }

    private fun embeddedLabel(
        context: Context,
        host: EmbeddedAncCard,
        text: String,
    ): TextView = TextView(context).apply {
        this.text = text
        setTextColor(host.styleSource.textColors)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, host.styleSource.textSize)
        typeface = host.styleSource.typeface
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
        private val controller: FitBudsTurboController,
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
        private val controller: FitBudsTurboController,
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

    /**
     * Owns the two accessory switches. The wind toggle reuses the ANC-branch policy; the game
     * toggle maps onto the Edifier low-latency request. Both are rendered from [EarbudState] and
     * only ever issue a request — the authoritative state is restored from the protocol pipeline.
     */
    private class FitBudsTurboController(
        windToggle: CompoundButton,
        gameToggle: CompoundButton,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) {
        private val windToggle = WeakReference(windToggle)
        private val gameToggle = WeakReference(gameToggle)
        private var rendering = false

        fun bind() {
            windToggle.get()?.setOnCheckedChangeListener(::onWindChanged)
            gameToggle.get()?.setOnCheckedChangeListener(::onGameChanged)
        }

        fun render(state: EarbudState, accessory: View) {
            val windToggle = windToggle.get() ?: return
            val gameToggle = gameToggle.get() ?: return
            val wind = WindNoiseToggleControlPolicy.render(state)
            val game = FitBudsTurboGameModePolicy.render(state)
            rendering = true
            try {
                windToggle.isChecked = wind.checked
                windToggle.isEnabled = wind.enabled
                gameToggle.isChecked = game.checked
                gameToggle.isEnabled = game.enabled
                val anyEnabled = wind.enabled || game.enabled
                val alpha = if (anyEnabled) ENABLED_ALPHA else DISABLED_ALPHA
                windToggle.alpha = if (wind.enabled) ENABLED_ALPHA else DISABLED_ALPHA
                gameToggle.alpha = if (game.enabled) ENABLED_ALPHA else DISABLED_ALPHA
                accessory.alpha = alpha
            } finally {
                rendering = false
            }
        }

        fun unbind() {
            windToggle.get()?.setOnCheckedChangeListener(null)
            gameToggle.get()?.setOnCheckedChangeListener(null)
        }

        private fun onWindChanged(button: CompoundButton, checked: Boolean) {
            if (rendering) return
            val current = environment.stateProvider(address)
            val currentWind = WindNoiseToggleControlPolicy.render(current)
            rendering = true
            try {
                button.isChecked = currentWind.checked
            } finally {
                rendering = false
            }
            val requestedMode = WindNoiseToggleControlPolicy.request(current, checked) ?: return
            environment.controlSender(
                address,
                StandardControlRequest.SetNoiseMode(requestedMode),
            )
        }

        private fun onGameChanged(button: CompoundButton, checked: Boolean) {
            if (rendering) return
            val current = environment.stateProvider(address)
            val currentGame = FitBudsTurboGameModePolicy.render(current)
            rendering = true
            try {
                button.isChecked = currentGame.checked
            } finally {
                rendering = false
            }
            val requested = FitBudsTurboGameModePolicy.request(current, checked) ?: return
            environment.controlSender(
                address,
                EdifierControlRequest.SetGameMode(requested),
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
}

private const val COMPONENT = "MiLinkUi"
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
private const val WIND_LABEL = "抗风噪"
private const val GAME_MODE_LABEL = "游戏模式"
private const val LABEL_END_PADDING_DP = 8
private const val GAME_LABEL_START_PADDING_DP = 16
private const val EMBEDDED_HEADER_HEIGHT_DP = 48
private const val EMBEDDED_HEADER_HORIZONTAL_PADDING_DP = 20
private const val ENABLED_ALPHA = 1.0f
private const val DISABLED_ALPHA = 0.45f

/**
 * Pure game-mode toggle policy for the FitBuds Turbo card; UI code contains no independent state.
 *
 * The switch is enabled only while the session is active and connected and the game-mode feature
 * has been observed (i.e. the 0x08 query answered). Requesting a state that is already current
 * returns null so no redundant command is sent.
 */
internal object FitBudsTurboGameModePolicy {
    data class ToggleState(
        val checked: Boolean,
        val enabled: Boolean,
    )

    fun render(state: EarbudState): ToggleState {
        val feature = state.features.get<EdifierGameModeFeatureState>()
        return ToggleState(
            checked = feature?.enabled == true,
            enabled = state.sessionActive && state.connected && feature != null,
        )
    }

    fun request(state: EarbudState, checked: Boolean): Boolean? {
        val current = render(state)
        if (!current.enabled || current.checked == checked) return null
        return checked
    }
}
