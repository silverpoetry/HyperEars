package dev.hyperears.hook

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import dev.hyperears.integration.EdifierControlRequest
import dev.hyperears.integration.EdifierGameModeFeatureState
import dev.hyperears.integration.EdifierMiLinkPresentationIds
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.MiLinkCardPresentationId
import java.lang.ref.WeakReference

/**
 * Adds FitClip Ultra's protocol-confirmed game mode as a standalone native-style control row.
 *
 * This model has no ANC card, so the extension anchors to MiLink's stable More settings row and
 * takes typography, spacing and background from the adjacent native volume card. It never moves or
 * replaces a host view. If the expected semantic structure is unavailable, binding is skipped and
 * the stock card remains untouched.
 */
internal object FitClipUltraGameModeMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId: MiLinkCardPresentationId =
        EdifierMiLinkPresentationIds.GAME_MODE

    override fun bind(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        val moreSettings = root.findMiLinkView(MORE_SETTINGS_ID)
            ?: return skipped(address, "more-settings-anchor-missing")
        val parent = moreSettings.parent as? LinearLayout
            ?: return skipped(address, "more-settings-parent-not-linear")
        val anchorIndex = parent.indexOfChild(moreSettings)
            .takeIf { it > 0 }
            ?: return skipped(address, "more-settings-position-invalid")
        val styleCard = parent.getChildAt(anchorIndex - 1)
        val styleTitle = root.findMiLinkView(VOLUME_TITLE_ID) as? TextView
            ?: return skipped(address, "volume-title-style-missing")
        if (!styleTitle.isDescendantOf(styleCard)) {
            return skipped(address, "volume-title-outside-style-card")
        }
        val background = styleCard.background.independentCopy(root)
            ?: return skipped(address, "volume-card-background-unavailable")
        val panelHeightReservation = PanelHeightReservation(parent)

        val row = LinearLayout(root.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            this.background = background
            setPadding(
                styleCard.paddingLeft,
                styleCard.paddingTop,
                styleCard.paddingRight,
                styleCard.paddingBottom,
            )
        }
        val label = TextView(root.context).apply {
            text = GAME_MODE_LABEL
            setTextColor(styleTitle.textColors)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, styleTitle.textSize)
            typeface = styleTitle.typeface
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val toggle = createHostToggle(root.context, environment.hostClassLoader).apply {
            contentDescription = GAME_MODE_LABEL
            isSaveEnabled = false
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            ),
        )
        row.addView(
            toggle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val styleParams = styleCard.layoutParams
        val anchorParams = moreSettings.layoutParams as? ViewGroup.MarginLayoutParams
        val rowParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            styleParams.height.takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = anchorParams?.topMargin ?: 0
        }
        parent.addView(row, anchorIndex, rowParams)
        panelHeightReservation.attach(row)

        return Binding(
            parent = parent,
            row = row,
            toggle = toggle,
            panelHeightReservation = panelHeightReservation,
            address = address,
            environment = environment,
        ).also { binding ->
            binding.bind()
            ModuleLog.debug(
                COMPONENT,
                "bound FitClip Ultra game-mode row anchor=$MORE_SETTINGS_ID " +
                    "style=$VOLUME_TITLE_ID address=${maskBluetoothAddress(address)}",
            )
        }
    }

    private fun skipped(address: String, reason: String): MiLinkCardBinding? {
        ModuleLog.debug(
            COMPONENT,
            "skipped FitClip Ultra game-mode row reason=$reason " +
                "address=${maskBluetoothAddress(address)}",
        )
        return null
    }

    private class Binding(
        parent: LinearLayout,
        row: View,
        toggle: CompoundButton,
        private val panelHeightReservation: PanelHeightReservation,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) : MiLinkCardBinding {
        private val parent = WeakReference(parent)
        private val row = WeakReference(row)
        private val toggle = WeakReference(toggle)
        private var rendering = false
        private var lastRendered: FitClipGameModeTogglePolicy.ToggleState? = null

        fun bind() {
            toggle.get()?.setOnCheckedChangeListener(::onToggleChanged)
        }

        override fun render(state: EarbudState) {
            val row = row.get() ?: return
            val toggle = toggle.get() ?: return
            val projected = FitClipGameModeTogglePolicy.render(state)
            row.visibility = if (state.sessionActive) View.VISIBLE else View.GONE
            panelHeightReservation.setActive(state.sessionActive)
            rendering = true
            try {
                toggle.isChecked = projected.checked
                toggle.isEnabled = projected.enabled
                row.alpha = if (projected.enabled) ENABLED_ALPHA else DISABLED_ALPHA
            } finally {
                rendering = false
            }
            if (lastRendered != projected) {
                lastRendered = projected
                ModuleLog.debug(
                    COMPONENT,
                    "rendered FitClip Ultra game-mode enabled=${projected.enabled} " +
                        "checked=${projected.checked} address=${maskBluetoothAddress(address)}",
                )
            }
        }

        private fun onToggleChanged(button: CompoundButton, checked: Boolean) {
            if (rendering) return
            val current = environment.stateProvider(address)
            val authoritative = FitClipGameModeTogglePolicy.render(current)
            rendering = true
            try {
                button.isChecked = authoritative.checked
            } finally {
                rendering = false
            }
            val requested = FitClipGameModeTogglePolicy.request(current, checked) ?: return
            ModuleLog.debug(
                COMPONENT,
                "requested FitClip Ultra game-mode enabled=$requested " +
                    "address=${maskBluetoothAddress(address)}",
            )
            environment.controlSender(
                address,
                EdifierControlRequest.SetGameMode(requested),
            )
        }

        override fun unbind() {
            toggle.get()?.setOnCheckedChangeListener(null)
            val parent = parent.get()
            val row = row.get()
            if (parent != null && row?.parent === parent) parent.removeView(row)
            panelHeightReservation.release()
        }
    }

    /**
     * Reserves the actual custom-row height in MiLink's explicitly sized headset panel.
     *
     * MiLink computes `headsets_control` height from stock capability flags instead of measuring
     * arbitrary children. A stock ANC card contributes to that calculation; a late CardAdapter
     * row does not. This reservation observes normal layout callbacks, expands only when visible
     * content would otherwise be clipped, and restores the host's original layout contract when
     * the binding is removed. It does not call host methods or depend on obfuscated field names.
     */
    private class PanelHeightReservation(
        panel: LinearLayout,
    ) {
        private val panel = WeakReference(panel)
        private var row: WeakReference<View>? = null
        private val originalLayoutHeight = panel.layoutParams.height
        private val originalRenderedHeight = maxOf(panel.height, panel.measuredHeight, 0)
        private var active = false
        private var appliedHeight: Int? = null
        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (active) ensureHeight()
        }

        fun attach(row: View) {
            this.row = WeakReference(row)
            panel.get()?.addOnLayoutChangeListener(layoutListener)
            setActive(true)
        }

        fun setActive(active: Boolean) {
            if (this.active == active) return
            this.active = active
            if (active) scheduleMeasurement() else restoreHeight()
        }

        fun release() {
            active = false
            panel.get()?.removeOnLayoutChangeListener(layoutListener)
            restoreHeight()
            row?.clear()
            row = null
        }

        private fun scheduleMeasurement() {
            val row = row?.get() ?: return
            row.doOnLayout {
                if (active) ensureHeight()
            }
            row.requestLayout()
            panel.get()?.requestLayout()
        }

        private fun ensureHeight() {
            val panel = panel.get() ?: return
            val row = row?.get()?.takeIf { it.isVisible } ?: return
            val rowParams = row.layoutParams as? ViewGroup.MarginLayoutParams
            val reservedHeight = row.measuredHeight +
                (rowParams?.topMargin ?: 0) +
                (rowParams?.bottomMargin ?: 0)
            if (reservedHeight <= 0) {
                scheduleMeasurement()
                return
            }
            val contentHeight = panel.visibleVerticalContentHeight()
            val requiredHeight = FitClipPanelLayoutPolicy.requiredHeight(
                originalPanelHeight = originalRenderedHeight,
                customRowHeight = reservedHeight,
                measuredContentHeight = contentHeight,
                availableHeight = (panel.parent as? View)?.height ?: 0,
            )
            if (panel.height >= requiredHeight) return
            panel.layoutParams = panel.layoutParams.apply { height = requiredHeight }
            appliedHeight = requiredHeight
            panel.requestLayout()
            (panel.parent as? View)?.requestLayout()
            ModuleLog.debug(
                COMPONENT,
                "reserved FitClip Ultra panel height base=$originalRenderedHeight " +
                    "row=$reservedHeight content=$contentHeight target=$requiredHeight",
            )
        }

        private fun restoreHeight() {
            val panel = panel.get() ?: return
            if (appliedHeight == null) return
            panel.layoutParams = panel.layoutParams.apply { height = originalLayoutHeight }
            panel.requestLayout()
            (panel.parent as? View)?.requestLayout()
            appliedHeight = null
        }

        private fun LinearLayout.visibleVerticalContentHeight(): Int =
            paddingTop + paddingBottom + (0 until childCount).sumOf { index ->
                val child = getChildAt(index)
                if (child.isGone) return@sumOf 0
                val params = child.layoutParams as? ViewGroup.MarginLayoutParams
                child.measuredHeight +
                    (params?.topMargin ?: 0) +
                    (params?.bottomMargin ?: 0)
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

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun Drawable?.independentCopy(root: View): Drawable? =
        this?.constantState?.newDrawable(root.resources)?.mutate()

    private const val COMPONENT = "MiLinkUi"
    private const val MORE_SETTINGS_ID = "headset_more_settings"
    private const val VOLUME_TITLE_ID = "volume_title"
    private const val MIUIX_SLIDING_BUTTON = "miuix.slidingwidget.widget.SlidingButton"
    private const val GAME_MODE_LABEL = "游戏模式"
    private const val ENABLED_ALPHA = 1.0f
    private const val DISABLED_ALPHA = 0.45f
}

/** Pure sizing policy shared by the Android binding and unit tests. */
internal object FitClipPanelLayoutPolicy {
    fun requiredHeight(
        originalPanelHeight: Int,
        customRowHeight: Int,
        measuredContentHeight: Int,
        availableHeight: Int,
    ): Int {
        val required = maxOf(
            measuredContentHeight.coerceAtLeast(0),
            originalPanelHeight.coerceAtLeast(0) + customRowHeight.coerceAtLeast(0),
        )
        return if (availableHeight > 0) required.coerceAtMost(availableHeight) else required
    }
}

/** Pure projection from authoritative device state to the FitClip game-mode control. */
internal object FitClipGameModeTogglePolicy {
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
