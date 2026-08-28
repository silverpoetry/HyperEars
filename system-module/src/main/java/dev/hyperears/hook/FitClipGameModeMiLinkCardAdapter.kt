package dev.hyperears.hook

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.GameModeFeatureState
import dev.hyperears.integration.EdifierMiLinkPresentationIds
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.StandardControlRequest
import java.lang.ref.WeakReference

/**
 * Adds a protocol-confirmed game-mode switch to MiLink's native headset card for Edifier
 * models that expose a game-mode control capability but no ANC branch (such as FitClip Ultra).
 *
 * Unlike the wind-noise toggle, this presentation is independent of MiLink's ANC item layout:
 * it locates the card's title row through either native title contract and injects the game-mode
 * switch beside it. When no title anchor is found the adapter returns null (safe no-op), so the
 * presentation never draws an imitation in an unknown layout. The switch is enabled only while
 * the authoritative adapter reports the game-mode capability; its checked state is always rendered
 * from [EarbudState].
 */
internal open class FitClipGameModeMiLinkCardAdapter(
    final override val presentationId: MiLinkCardPresentationId,
    private val modelLabel: String,
) : MiLinkCardAdapter {

    override fun bind(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        val host = resolveNativeTitle(root) ?: return null
        val title = host.title
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

        return Binding(
            parent = parent,
            originalIndex = index,
            originalLayoutParams = originalParams,
            originalWidth = originalWidth,
            wrapper = wrapper,
            title = title,
            accessory = accessory,
            toggle = toggle,
            address = address,
            environment = environment,
        ).also { binding ->
            toggle.setOnCheckedChangeListener(binding::onToggleChanged)
            ModuleLog.debug(
                "MiLinkUi",
                "bound $modelLabel game-mode switch layout=${host.generation.logName}",
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
        accessory: View,
        toggle: CompoundButton,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) : MiLinkCardBinding {
        private val parent = WeakReference(parent)
        private val wrapper = WeakReference(wrapper)
        private val title = WeakReference(title)
        private val accessory = WeakReference(accessory)
        private val toggle = WeakReference(toggle)
        private var rendering = false

        override fun render(state: EarbudState) {
            val wrapper = wrapper.get() ?: return
            val title = title.get() ?: return
            val accessory = accessory.get() ?: return
            val toggle = toggle.get() ?: return

            wrapper.visibility = if (state.sessionActive) View.VISIBLE else View.GONE
            accessory.visibility =
                if (title.isVisible && state.sessionActive) View.VISIBLE else View.GONE

            val toggleState = GameModeToggleControlPolicy.render(state)
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
            val currentToggleState = GameModeToggleControlPolicy.render(current)

            // A UI gesture is only a request. Restore the authoritative value until the device
            // reports the new game-mode state through the normal protocol state pipeline.
            rendering = true
            try {
                button.isChecked = currentToggleState.checked
            } finally {
                rendering = false
            }

            val requested = GameModeToggleControlPolicy.request(current, checked) ?: return
            environment.controlSender(
                address,
                StandardControlRequest.SetGameMode(requested),
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
        android.widget.Switch(context)
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun resolveNativeTitle(root: View): NativeTitle? {
        val originalTitle = root.findMiLinkView(ORIGINAL_ANC_CARD_TITLE_ID) as? TextView
        if (originalTitle != null) {
            return NativeTitle(
                generation = NativeTitleGeneration.ORIGINAL,
                title = originalTitle,
            )
        }

        val selectTitle = root.findMiLinkView(SELECT_ANC_CARD_TITLE_ID) as? TextView
            ?: return null
        return NativeTitle(
            generation = NativeTitleGeneration.SELECT_CARD,
            title = selectTitle,
        )
    }

    private data class NativeTitle(
        val generation: NativeTitleGeneration,
        val title: TextView,
    )

    private enum class NativeTitleGeneration(val logName: String) {
        ORIGINAL("original"),
        SELECT_CARD("select-card"),
    }

    private companion object {
        const val ORIGINAL_ANC_CARD_TITLE_ID = "anc_card_title"
        const val SELECT_ANC_CARD_TITLE_ID = "anc_card_text"
        const val MIUIX_SLIDING_BUTTON = "miuix.slidingwidget.widget.SlidingButton"
        const val GAME_MODE_LABEL = "游戏模式"
        const val LABEL_END_PADDING_DP = 8
        const val ENABLED_ALPHA = 1.0f
        const val DISABLED_ALPHA = 0.45f
    }
}
/**
 * Concrete presentation for Edifier FitClip Ultra's game-mode capability.
 *
 * The shared [FitClipGameModeMiLinkCardAdapter] keeps MiLink's title row intact and adds the
 * device-specific game-mode switch beside it. This presentation is selected only after the
 * private session confirms the game-mode capability (adapter.presentationId is null otherwise).
 */
internal object FitClipUltraGameModeMiLinkCardAdapter : FitClipGameModeMiLinkCardAdapter(
    presentationId = EdifierMiLinkPresentationIds.GAME_MODE,
    modelLabel = "Edifier FitClip Ultra",
)

/** Pure game-mode-to-switch policy; UI code contains no independent mode state. */
internal object GameModeToggleControlPolicy {
    data class ToggleState(
        val checked: Boolean,
        val enabled: Boolean,
    )

    fun render(state: EarbudState): ToggleState {
        val gameModeOn = state.features.get<GameModeFeatureState>()?.enabled ?: false
        return ToggleState(
            checked = gameModeOn,
            enabled = state.sessionActive &&
                state.connected &&
                (state.adapter?.capabilities?.gameModeControl == true),
        )
    }

    fun request(state: EarbudState, checked: Boolean): Boolean? {
        if (!render(state).enabled) return null
        val currentOn = state.features.get<GameModeFeatureState>()?.enabled ?: false
        return when {
            checked && !currentOn -> true
            !checked && currentOn -> false
            else -> null
        }
    }
}
