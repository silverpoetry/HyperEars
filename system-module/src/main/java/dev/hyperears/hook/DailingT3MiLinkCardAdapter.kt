package dev.hyperears.hook

import android.content.Context
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.hyperears.integration.ControlOwnership
import dev.hyperears.integration.DailingAncDepth
import dev.hyperears.integration.DailingAncDepthFeatureState
import dev.hyperears.integration.DailingControlRequest
import dev.hyperears.integration.DailingDualConnectionFeatureState
import dev.hyperears.integration.DailingGameModeFeatureState
import dev.hyperears.integration.DailingMiLinkPresentationIds
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.NoiseMode
import java.lang.ref.WeakReference

/**
 * DaiLing T3 card extension.
 *
 * Three model-specific controls share one card presentation:
 * - a protocol-backed low-latency game-mode toggle and a dual-device toggle in the native
 *   noise-control title row (the decorative headline text is cleared to make room), and
 * - an extra ANC-depth row that takes over the trailing More Settings slot while ANC is active,
 *   so the card keeps its native height and never has to scroll.
 */
internal object DailingT3CardAdapter : NativeAncToggleMiLinkCardAdapter(
    presentationId = DailingMiLinkPresentationIds.CARD,
    modelLabel = "DaiLing T3",
    toggles = listOf(DailingGameModeToggleSpec, DailingDualConnectionToggleSpec),
) {
    override fun bind(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        // The headline label is pure decoration; clearing it gives the two switches room in the
        // same row and restores the original text when the card is released.
        val title = resolveHeadline(root)
        val originalTitle = title?.text
        val toggleBinding = super.bind(root, address, environment)
        val depthBinding = DailingAncDepthSlot.attach(root, address, environment)
        val combined = when {
            toggleBinding == null -> depthBinding
            depthBinding == null -> toggleBinding
            else -> CombinedBinding(toggleBinding, depthBinding)
        }
        if (combined == null) {
            title?.text = originalTitle
            return null
        }
        if (title != null) title.text = ""
        return TitleRestoringBinding(combined, title, originalTitle)
    }

    private fun resolveHeadline(root: View): TextView? {
        val row = NativeAncModeRow.resolve(root)
        if (row != null) {
            (root.findMiLinkView(row.layout.titleId) as? TextView)?.let { return it }
        }
        return root.findTextViewByText(HEADLINE_TEXT)
    }

    private class TitleRestoringBinding(
        private val delegate: MiLinkCardBinding,
        private val title: TextView?,
        private val originalTitle: CharSequence?,
    ) : MiLinkCardBinding {
        override fun render(state: EarbudState) = delegate.render(state)

        override fun unbind() {
            delegate.unbind()
            title?.text = originalTitle
        }
    }

    private class CombinedBinding(
        private val first: MiLinkCardBinding,
        private val second: MiLinkCardBinding,
    ) : MiLinkCardBinding {
        override fun render(state: EarbudState) {
            first.render(state)
            second.render(state)
        }

        override fun unbind() {
            second.unbind()
            first.unbind()
        }
    }
}

/** Protocol-backed game-mode switch rendered next to the native noise-control title. */
internal object DailingGameModeToggleSpec : MiLinkToggleSpec {
    override val label: String = "游戏模式"

    override fun render(state: EarbudState): MiLinkToggleState {
        val feature = state.features.get<DailingGameModeFeatureState>()
        return MiLinkToggleState(
            checked = feature?.enabled == true,
            enabled = state.sessionActive && state.connected && feature != null,
            available = feature != null,
        )
    }

    override fun request(state: EarbudState, checked: Boolean): DailingControlRequest.SetGameMode? {
        val feature = state.features.get<DailingGameModeFeatureState>() ?: return null
        if (feature.enabled == checked) return null
        if (!state.sessionActive || !state.connected) return null
        return DailingControlRequest.SetGameMode(checked)
    }
}

/** Protocol-backed dual-device switch rendered next to the game-mode toggle. */
internal object DailingDualConnectionToggleSpec : MiLinkToggleSpec {
    override val label: String = "双设备"

    override fun render(state: EarbudState): MiLinkToggleState {
        val feature = state.features.get<DailingDualConnectionFeatureState>()
        return MiLinkToggleState(
            checked = feature?.enabled == true,
            enabled = state.sessionActive && state.connected && feature != null,
            available = feature != null,
        )
    }

    override fun request(
        state: EarbudState,
        checked: Boolean,
    ): DailingControlRequest.SetDualConnection? {
        val feature = state.features.get<DailingDualConnectionFeatureState>() ?: return null
        if (feature.enabled == checked) return null
        if (!state.sessionActive || !state.connected) return null
        return DailingControlRequest.SetDualConnection(checked)
    }
}

/** Extra ANC-depth row that borrows the trailing More Settings slot while ANC is active. */
internal object DailingAncDepthSlot {
    fun attach(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        val row = NativeAncModeRow.resolve(root)
            ?: return skipped(address, "native-anc-row-unavailable")
        val container = row.container
        val content = container.parent as? ViewGroup
            ?: return skipped(address, "native-anc-row-detached")
        val slot = findMoreSettingsSlot(content)
            ?: return skipped(address, "more-settings-slot-unavailable")
        val slotParent = slot.parent as? ViewGroup
            ?: return skipped(address, "more-settings-slot-detached")
        val nativeItems = row.items

        // Clone the native segment container itself, then the three segment items in the same
        // order, so geometry, padding, selection drawing and icon slot all match the row above.
        val depthItems = DEPTH_ORDER.mapIndexed { index, depth ->
            val source = nativeItems[index % nativeItems.size]
            row.createItem(root, source = source, label = depth.label(), iconSource = source)
                ?: return skipped(address, "anc-depth-item-construction-failed")
        }.onEach { item ->
            item.id = View.generateViewId()
        }

        val depthRow = runCatching {
            container.javaClass.getConstructor(Context::class.java)
                .newInstance(root.context) as ViewGroup
        }.getOrNull() ?: LinearLayout(root.context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        depthRow.tag = DEPTH_ROW_TAG
        depthRow.background = container.background?.constantState?.newDrawable(root.resources)?.mutate()
        depthRow.setPadding(
            container.paddingLeft,
            container.paddingTop,
            container.paddingRight,
            container.paddingBottom,
        )
        depthRow.visibility = View.GONE
        val nativeHeight = container.layoutParams?.height?.takeIf { it > 0 }
            ?: container.height.takeIf { it > 0 }
            ?: slot.layoutParams?.height?.takeIf { it > 0 }
            ?: ViewGroup.LayoutParams.WRAP_CONTENT
        depthRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            nativeHeight,
        ).apply {
            topMargin = root.context.dp(ROW_GAP_DP)
        }
        depthItems.forEachIndexed { index, item ->
            val sourceParams = nativeItems[index % nativeItems.size].layoutParams
            val itemParams = sourceParams?.toLinearParams()
                ?: LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            itemParams.gravity = Gravity.CENTER_VERTICAL
            depthRow.addView(item, itemParams)
        }

        val slotIndex = slotParent.indexOfChild(slot)
        if (slotIndex < 0) return skipped(address, "more-settings-slot-index-missing")
        val slotVisibility = slot.visibility
        slotParent.addView(depthRow, slotIndex, depthRow.layoutParams)

        val binding = Binding(
            depthRow = depthRow,
            slot = slot,
            originalSlotVisibility = slotVisibility,
            items = depthItems.map(::WeakReference),
            address = address,
            environment = environment,
        )
        depthItems.forEachIndexed { itemIndex, item ->
            item.setOnClickListener { binding.onDepthClicked(DEPTH_ORDER[itemIndex]) }
        }
        ModuleLog.debug(
            COMPONENT,
            "bound Dailing T3 ANC-depth row layout=${row.layout.logName} " +
                "address=${maskBluetoothAddress(address)}",
        )
        return binding
    }

    private fun skipped(address: String, reason: String): MiLinkCardBinding? {
        ModuleLog.debug(
            COMPONENT,
            "skipped Dailing T3 ANC-depth row reason=$reason " +
                "address=${maskBluetoothAddress(address)}",
        )
        return null
    }

    private class Binding(
        private val depthRow: ViewGroup,
        private val slot: View,
        private val originalSlotVisibility: Int,
        private val items: List<WeakReference<View>>,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) : MiLinkCardBinding {
        private var lastRendered: DailingAncDepthRowPolicy.RowState? = null

        override fun render(state: EarbudState) {
            val projected = DailingAncDepthRowPolicy.render(state)
            val showRow = projected.visible
            // While the row is hidden the native More Settings entry keeps its slot.
            if (showRow) {
                slot.visibility = View.GONE
                depthRow.visibility = View.VISIBLE
            } else {
                depthRow.visibility = View.GONE
                slot.visibility = originalSlotVisibility
            }
            if (!showRow) return

            val views = items.mapNotNull(WeakReference<View>::get)
            if (views.size != items.size) return
            views.forEachIndexed { index, view ->
                view.setSelectedTree(DEPTH_ORDER[index] == projected.current)
                view.isEnabled = projected.enabled
                view.alpha = if (projected.enabled) ENABLED_ALPHA else DISABLED_ALPHA
            }
            if (lastRendered != projected) {
                lastRendered = projected
                ModuleLog.debug(
                    COMPONENT,
                    "rendered Dailing T3 ANC-depth row visible=$showRow " +
                        "enabled=${projected.enabled} depth=${projected.current} " +
                        "address=${maskBluetoothAddress(address)}",
                )
            }
        }

        fun onDepthClicked(depth: DailingAncDepth) {
            val current = environment.stateProvider(address)
            val requested = DailingAncDepthRowPolicy.request(current, depth) ?: return
            environment.controlSender(address, DailingControlRequest.SetAncDepth(requested))
        }

        override fun unbind() {
            items.mapNotNull(WeakReference<View>::get).forEach { item ->
                item.setOnClickListener(null)
            }
            slot.visibility = originalSlotVisibility
            (depthRow.parent as? ViewGroup)?.removeView(depthRow)
        }
    }

    private val DEPTH_ORDER = listOf(
        DailingAncDepth.DEEP,
        DailingAncDepth.MIDDLE,
        DailingAncDepth.LIGHT,
    )
}

/** Depth-first search for a TextView with the given literal label. */
private fun View.findTextViewByText(label: String): TextView? {
    if (this is TextView && text?.toString() == label) return this
    if (this !is ViewGroup) return null
    for (index in 0 until childCount) {
        getChildAt(index).findTextViewByText(label)?.let { return it }
    }
    return null
}

/** Finds the trailing More Settings row that the depth row temporarily takes over. */
private fun findMoreSettingsSlot(content: ViewGroup): View? {
    val textView = content.findTextView { text -> text in MORE_SETTINGS_LABELS } ?: return null
    var node: View = textView
    while (true) {
        val parent = node.parent as? ViewGroup ?: return textView
        if (parent === content) return node
        if (parent === content.parent) return node
        node = parent
    }
}

private fun ViewGroup.findTextView(matches: (String) -> Boolean): TextView? {
    for (index in 0 until childCount) {
        val child = getChildAt(index)
        if (child is android.widget.TextView) {
            val text = child.text?.toString().orEmpty()
            if (text.isNotEmpty() && matches(text)) return child
        }
        if (child is ViewGroup) child.findTextView(matches)?.let { return it }
    }
    return null
}

/** Converts host layout params into the linear params used inside the cloned row. */
private fun ViewGroup.LayoutParams.toLinearParams(): LinearLayout.LayoutParams = when (this) {
    is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(this)
    is ViewGroup.MarginLayoutParams -> LinearLayout.LayoutParams(this)
    else -> LinearLayout.LayoutParams(this)
}

/**
 * Cell policy for the extra row.
 *
 * The row follows the same availability rules as the standard noise control: an active session
 * with accepted handshake, protocol-confirmed noise capability and module ownership.
 */
internal object DailingAncDepthRowPolicy {
    data class RowState(
        val visible: Boolean,
        val enabled: Boolean,
        val current: DailingAncDepth?,
    )

    fun render(state: EarbudState): RowState {
        val feature = state.features.get<DailingAncDepthFeatureState>()
        val active = state.sessionActive && state.connected && feature != null
        return RowState(
            visible = active && state.noiseMode == NoiseMode.ANC,
            enabled = active &&
                state.lifecycle.controlOwnership == ControlOwnership.MODULE &&
                feature.supported.isNotEmpty(),
            current = feature?.current,
        )
    }

    fun request(state: EarbudState, depth: DailingAncDepth): DailingAncDepth? {
        val current = render(state)
        if (!current.visible || !current.enabled || current.current == depth) return null
        return depth
    }
}

private fun DailingAncDepth.label(): String = when (this) {
    DailingAncDepth.DEEP -> "深度"
    DailingAncDepth.MIDDLE -> "中度"
    DailingAncDepth.LIGHT -> "轻度"
}

private fun View.setSelectedTree(selected: Boolean) {
    isSelected = selected
    if (this !is ViewGroup) return
    for (index in 0 until childCount) getChildAt(index).setSelectedTree(selected)
}

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

private const val DEPTH_ROW_TAG = "dailing-anc-depth-row"
private val MORE_SETTINGS_LABELS = setOf("更多设置", "更多設定", "More settings")
private const val COMPONENT = "MiLinkUi"
private const val HEADLINE_TEXT = "噪声控制"
private const val ROW_GAP_DP = 10
private const val ENABLED_ALPHA = 1.0f
private const val DISABLED_ALPHA = 0.45f
