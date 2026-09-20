package dev.hyperears.hook

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.SamsungBudsSettingsFeatureState
import dev.hyperears.integration.SamsungControlRequest
import dev.hyperears.integration.SamsungTouchAction
import dev.hyperears.integration.SamsungTouchGesture
import dev.hyperears.integration.SamsungNoiseCycle
import java.lang.ref.WeakReference

/** Protocol-backed second-level touch-control panel for Galaxy Buds2 Pro. */
internal class SamsungTouchControlDetails private constructor(
    anchor: View,
    private val address: String,
    private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val context = anchor.context
    private var closed = false
    private val popup = PopupWindow(context)
    private lateinit var pageTitle: TextView
    private lateinit var backButton: TextView
    private lateinit var pageScroll: ScrollView
    private lateinit var mainBody: LinearLayout
    private var page = 2
    private var cycleLeft = true
    private var cycleDraft: SamsungNoiseCycle? = null
    private var controlsAvailable = false
    private val gestureRows = mutableListOf<GestureRow>()
    private lateinit var masterRow: ToggleRow
    private lateinit var leftActionRow: ActionRow
    private lateinit var rightActionRow: ActionRow
    private lateinit var volumeTouchRow: ToggleRow
    private var rendering = false
    private var lastState: SamsungBudsSettingsFeatureState? = null
    private var unsubscribeState: (() -> Unit)? = null

    init {
        popup.apply {
            contentView = buildContent()
            width = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
            height = (context.resources.displayMetrics.heightPixels * 0.82f).toInt()
            isFocusable = true
            isOutsideTouchable = true
            elevation = context.dp(18).toFloat()
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setOnDismissListener {
                closed = true
                unsubscribeState?.invoke()
                unsubscribeState = null
                clearPending()
            }
        }
        unsubscribeState = environment.observeState(address) { state -> render(state) }
        anchor.post {
            if (closed) return@post
            runCatching {
                check(anchor.isAttachedToWindow && anchor.windowToken != null) {
                    "MiLink anchor has no window token"
                }
                popup.showAtLocation(anchor.rootView, Gravity.CENTER, 0, 0)
            }.onFailure {
                dismiss()
                ModuleLog.warn("MiLinkUi", "unable to open Samsung touch controls", it)
            }
        }
    }

    override fun render(state: EarbudState) {
        if (closed) return
        val feature = state.features.get<SamsungBudsSettingsFeatureState>()
        if (feature == null || !state.sessionActive || !state.connected) {
            dismiss()
            return
        }
        val previousFeature = lastState
        val previouslyAvailable = controlsAvailable
        if (feature != previousFeature) {
            ModuleLog.debug("MiLinkUi", "Samsung details state rev=${state.revision} " +
                "actionsPending=${feature.touchHoldActionsPending} cyclesPending=${feature.touchHoldCyclesPending} " +
                "left=${feature.touchHoldLeftAction} right=${feature.touchHoldRightAction}")
        }
        lastState = feature
        controlsAvailable = state.sessionActive && state.connected &&
            !feature.touchpadLocked && feature.touchHoldEnabled
        rendering = true
        try {
            masterRow.render(!feature.touchpadLocked, state.sessionActive && state.connected)
            val touchEnabled = !feature.touchpadLocked
            gestureRows.forEach { it.render(feature, touchEnabled && state.sessionActive && state.connected) }
            renderHoldFeedback()
            volumeTouchRow.render(feature.outsideDoubleTapEnabled, touchEnabled && state.sessionActive && state.connected)
            if (feature != previousFeature || controlsAvailable != previouslyAvailable) {
                if (page == 3) showHoldPage()
                if (page == 4) showCyclePage()
            }
        } finally {
            rendering = false
        }
    }

    override fun dismiss() {
        closed = true
        unsubscribeState?.invoke()
        unsubscribeState = null
        clearPending()
        if (popup.isShowing) popup.dismiss()
    }

    private fun buildContent(): View {
        val primary = context.themeColor(android.R.attr.textColorPrimary, Color.rgb(32, 32, 32))
        val secondary = context.themeColor(android.R.attr.textColorSecondary, Color.rgb(110, 110, 110))
        val surface = context.themeColor(android.R.attr.colorBackground, Color.rgb(248, 248, 250))
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(22), context.dp(14), context.dp(22), context.dp(24))
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = context.dp(28).toFloat()
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backButton = text("‹", 30f, primary).apply {
            gravity = Gravity.CENTER
            contentDescription = "返回上一级"
            visibility = View.GONE
            setOnClickListener { navigateBack() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(context.dp(40), context.dp(48)))
        pageTitle = text("触摸控制", 24f, primary)
        header.addView(pageTitle, LinearLayout.LayoutParams(0, context.dp(56), 1f))
        header.addView(text("×", 30f, primary).apply {
            gravity = Gravity.CENTER
            contentDescription = "关闭"
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = context.dp(28).toFloat()
            }
            clipToOutline = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && page > 2) {
                    if (event.action == KeyEvent.ACTION_UP) navigateBack()
                    true
                } else false
            }
        }
        header.setPadding(context.dp(22), context.dp(8), context.dp(16), context.dp(8))
        panel.addView(header)

        masterRow = addToggle(body, "触摸控制", "总开关") { enabled ->
            sendMaster(enabled)
        }

        addSection(body, "媒体控制", secondary)
        gestureRows += addGesture(body, "播放或暂停歌曲", "点击", SamsungTouchGesture.SINGLE_TAP) { singleTapEnabled }
        gestureRows += addGesture(body, "播放下一首歌曲", "双击", SamsungTouchGesture.DOUBLE_TAP) { doubleTapEnabled }
        gestureRows += addGesture(body, "播放上一首歌曲", "点击三次", SamsungTouchGesture.TRIPLE_TAP) { tripleTapEnabled }

        addSection(body, "长按", secondary)
        gestureRows += addGesture(body, "启用长按", "左右耳", SamsungTouchGesture.TOUCH_AND_HOLD) { touchHoldEnabled }
        leftActionRow = addAction(body, "左耳长按")
        rightActionRow = addAction(body, "右耳长按")

        addSection(body, "音量触摸控制", secondary)
        volumeTouchRow = addToggle(body, "双击耳机边缘调节音量", "左侧减小 · 右侧增大") { enabled ->
            if (!rendering) sendVolumeTouch(enabled)
        }

        addSection(body, "通话控制", secondary)
        gestureRows += addGesture(body, "接听来电或结束通话", "双击", SamsungTouchGesture.DOUBLE_TAP_CALL) {
            doubleTapCallEnabled
        }
        gestureRows += addGesture(body, "拒绝来电", "长按", SamsungTouchGesture.TOUCH_AND_HOLD_CALL) {
            touchHoldCallEnabled
        }

        mainBody = body
        pageScroll = ScrollView(context).apply {
            isFillViewport = true
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        panel.addView(pageScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return panel
    }

    private fun addSection(parent: LinearLayout, title: String, color: Int) {
        parent.addView(text(title, 14f, color).apply {
            setPadding(context.dp(4), context.dp(18), 0, context.dp(8))
        })
    }

    private fun addGesture(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        gesture: SamsungTouchGesture,
        value: SamsungBudsSettingsFeatureState.() -> Boolean,
    ): GestureRow {
        val row = addToggle(parent, title, subtitle) { enabled ->
            if (!rendering) sendGesture(gesture, enabled)
        }
        return GestureRow(row, gesture, value)
    }

    private fun addToggle(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        onChange: (Boolean) -> Unit,
    ): ToggleRow {
        val container = cardRow()
        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(title, 17f, context.themeColor(android.R.attr.textColorPrimary, Color.BLACK)))
        labels.addView(text(subtitle, 13f, context.themeColor(android.R.attr.textColorSecondary, Color.GRAY)))
        val toggle = createToggle().apply {
            isSaveEnabled = false
            setOnCheckedChangeListener { _, checked -> if (!rendering) onChange(checked) }
        }
        container.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(toggle)
        parent.addView(container)
        return ToggleRow(WeakReference(container), WeakReference(toggle))
    }

    private fun addAction(
        parent: LinearLayout,
        title: String,
    ): ActionRow {
        val container = cardRow()
        val titleView = text(title, 16f, context.themeColor(android.R.attr.textColorPrimary, Color.BLACK))
        val valueView = text("切换噪声控制  ›", 14f, context.themeColor(android.R.attr.colorAccent, Color.rgb(48, 126, 246))).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        container.addView(titleView, LinearLayout.LayoutParams(0, context.dp(48), 1f))
        container.addView(valueView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(48)))
        val row = ActionRow(WeakReference(container), WeakReference(valueView))
        container.setOnClickListener { if (container.isEnabled) showHoldPage() }
        parent.addView(container)
        return row
    }

    private fun pageBody() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(18), 0, context.dp(18), context.dp(20))
    }

    private fun displayPage(level: Int, title: String, body: View) {
        val scrollY = if (page == level) pageScroll.scrollY else 0
        page = level
        pageTitle.text = title
        backButton.visibility = if (level == 2) View.GONE else View.VISIBLE
        pageScroll.removeAllViews()
        pageScroll.addView(body)
        pageScroll.post { if (!closed && page == level) pageScroll.scrollTo(0, scrollY) }
    }

    private fun navigateBack() {
        when (page) {
            4 -> { cycleDraft = null; showHoldPage() }
            3 -> {
                renderHoldFeedback()
                displayPage(2, "触摸控制", mainBody)
            }
            else -> dismiss()
        }
    }

    private fun showHoldPage() {
        val feature = lastState ?: return
        val body = pageBody()
        val secondary = context.themeColor(android.R.attr.textColorSecondary, Color.GRAY)
        listOf(true, false).forEach { left ->
            addSection(body, if (left) "左侧" else "右侧", secondary)
            val current = if (left) feature.displayedLeftAction else feature.displayedRightAction
            val cycle = if (left) feature.displayedLeftCycle else feature.displayedRightCycle
            // Keep the three official Buds2 Pro choices. Preserve Spotify readback
            // if it was configured elsewhere without advertising an unverified action.
            listOf(SamsungTouchAction.NOISE_CONTROL, SamsungTouchAction.VOICE_ASSISTANT,
                SamsungTouchAction.VOLUME).forEach { action ->
                val subtitle = if (action == SamsungTouchAction.NOISE_CONTROL) cycleLabel(cycle) + "  ›" else ""
                addChoice(body, if (action == SamsungTouchAction.VOLUME) {
                    if (left) "音量减小" else "音量增加"
                } else actionLabel(action), subtitle, current == action, controlsAvailable) {
                    if (!controlsAvailable) return@addChoice
                    sendAction(if (left) action else null, if (left) null else action)
                    if (action == SamsungTouchAction.NOISE_CONTROL) {
                        cycleLeft = left
                        cycleDraft = if (left) lastState?.displayedLeftCycle else lastState?.displayedRightCycle
                        showCyclePage()
                    }
                }
            }
            if (current == SamsungTouchAction.SPOTIFY) {
                addSection(body, "当前：Spotify（外部设置）", secondary)
            }
        }
        displayPage(3, "长按", body)
    }

    private fun showCyclePage() {
        val feature = lastState ?: return
        val body = pageBody()
        addSection(body, if (cycleLeft) "左侧 · 切换噪声控制" else "右侧 · 切换噪声控制",
            context.themeColor(android.R.attr.textColorSecondary, Color.GRAY))
        SamsungNoiseCycle.entries.forEach { cycle ->
            addChoice(body, cycleLabel(cycle), "", cycleDraft == cycle, controlsAvailable) {
                cycleDraft = cycle
                sendCycleDraft()
            }
        }
        displayPage(4, "噪声切换组合", body)
    }

    private fun sendCycleDraft() {
        val selected = cycleDraft ?: return
        val current = lastState ?: return
        val left = if (cycleLeft) selected else current.displayedLeftCycle ?: return
        val right = if (!cycleLeft) selected else current.displayedRightCycle ?: return
        if (left != current.displayedLeftCycle || right != current.displayedRightCycle) {
            environment.controlSender(address, SamsungControlRequest.SetTouchHoldNoiseCycles(left, right))
            lastState = current.copy(requestedLeftCycle = left, requestedRightCycle = right,
                touchHoldCyclesPending = true, touchHoldCyclesTimedOut = false)
        }
        cycleDraft = null
        showHoldPage()
    }

    private fun addChoice(
        parent: LinearLayout, title: String, subtitle: String,
        selected: Boolean, enabled: Boolean, onClick: () -> Unit,
    ) {
        val row = cardRow()
        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(title, 18f, context.themeColor(android.R.attr.textColorPrimary, Color.BLACK)))
        if (subtitle.isNotEmpty()) labels.addView(text(subtitle, 13f,
            context.themeColor(android.R.attr.colorAccent, Color.BLUE)))
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(if (selected) "✓" else "", 22f,
            context.themeColor(android.R.attr.colorAccent, Color.BLUE)),
            LinearLayout.LayoutParams(context.dp(32), context.dp(48)))
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.45f
        row.contentDescription = title + if (selected) "，已选择" else ""
        row.setOnClickListener { if (row.isEnabled) onClick() }
        parent.addView(row)
    }

    private fun cycleLabel(cycle: SamsungNoiseCycle?): String = when (cycle) {
        SamsungNoiseCycle.ANC_AMBIENT -> "主动式降噪 ↔ 环境音"
        SamsungNoiseCycle.ANC_OFF -> "主动式降噪 ↔ 关闭"
        SamsungNoiseCycle.AMBIENT_OFF -> "环境音 ↔ 关闭"
        null -> "等待耳机上报"
    }

    private fun sendMaster(enabled: Boolean) {
        val feature = lastState ?: return
        if ((!feature.touchpadLocked) == enabled) return
        masterRow.pending = enabled
        environment.controlSender(address, SamsungControlRequest.SetTouchpadLock(!enabled))
        schedulePendingClear(masterRow)
        render(environment.stateProvider(address))
    }

    private fun sendGesture(gesture: SamsungTouchGesture, enabled: Boolean) {
        val row = gestureRows.firstOrNull { it.gesture == gesture } ?: return
        val feature = lastState ?: return
        if (row.value(feature) == enabled) return
        row.toggle.pending = enabled
        environment.controlSender(address, SamsungControlRequest.SetTouchGesture(gesture, enabled))
        schedulePendingClear(row.toggle)
        render(environment.stateProvider(address))
    }

    private fun sendAction(
        left: SamsungTouchAction? = null,
        right: SamsungTouchAction? = null,
    ) {
        val feature = lastState ?: return
        val nextLeft = left ?: feature.displayedLeftAction
        val nextRight = right ?: feature.displayedRightAction
        if (nextLeft == feature.displayedLeftAction && nextRight == feature.displayedRightAction) return
        environment.controlSender(address, SamsungControlRequest.SetTouchHoldActions(nextLeft, nextRight))
        lastState = feature.copy(requestedLeftAction = nextLeft, requestedRightAction = nextRight,
            touchHoldActionsPending = true, touchHoldActionsTimedOut = false)
        renderHoldFeedback()
        showHoldPage()
    }

    private fun sendVolumeTouch(enabled: Boolean) {
        val feature = lastState ?: return
        if (feature.outsideDoubleTapEnabled == enabled) return
        volumeTouchRow.pending = enabled
        environment.controlSender(address, SamsungControlRequest.SetOutsideDoubleTap(enabled))
        schedulePendingClear(volumeTouchRow)
        render(environment.stateProvider(address))
    }

    private fun schedulePendingClear(row: ToggleRow) {
        row.generation += 1
        val generation = row.generation
        popup.contentView.postDelayed({
            if (row.generation == generation) {
                row.pending = null
                render(environment.stateProvider(address))
            }
        }, CONFIRMATION_TIMEOUT_MS)
    }

    private fun renderHoldFeedback() {
        val feature = lastState ?: return
        leftActionRow.render(feature.displayedLeftAction, controlsAvailable)
        rightActionRow.render(feature.displayedRightAction, controlsAvailable)
    }

    private fun clearPending() {
        masterRow.pending = null
        gestureRows.forEach { it.toggle.pending = null }
        if (::volumeTouchRow.isInitialized) volumeTouchRow.pending = null
    }

    private fun cardRow() = LinearLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dp(6)
        }
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(16), context.dp(9), context.dp(12), context.dp(9))
        background = GradientDrawable().apply {
            setColor(context.themeColor(android.R.attr.colorBackgroundFloating, Color.WHITE))
            cornerRadius = context.dp(18).toFloat()
        }
    }

    private fun createToggle(): CompoundButton = runCatching {
        Class.forName("miuix.slidingwidget.widget.SlidingButton", true, environment.hostClassLoader)
            .asSubclass(CompoundButton::class.java)
            .getConstructor(Context::class.java).newInstance(context)
    }.getOrElse {
        @Suppress("DEPRECATION")
        Switch(context)
    }

    private fun text(value: String, sizeSp: Float, color: Int) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun actionLabel(action: SamsungTouchAction): String = when (action) {
        SamsungTouchAction.VOICE_ASSISTANT -> "语音助手"
        SamsungTouchAction.NOISE_CONTROL -> "切换噪声控制"
        SamsungTouchAction.VOLUME -> "音量"
        SamsungTouchAction.SPOTIFY -> "Spotify"
    }

    private inner class ToggleRow(
        private val container: WeakReference<View>,
        private val toggle: WeakReference<CompoundButton>,
        var pending: Boolean? = null,
        var generation: Int = 0,
    ) {
        fun render(value: Boolean, enabled: Boolean) {
            if (pending == value) pending = null
            val display = pending ?: value
            toggle.get()?.apply {
                isChecked = display
                isEnabled = enabled && pending == null
            }
            container.get()?.alpha = if (enabled) 1f else 0.45f
        }
    }

    private inner class GestureRow(
        val toggle: ToggleRow,
        val gesture: SamsungTouchGesture,
        val value: SamsungBudsSettingsFeatureState.() -> Boolean,
    ) {
        fun render(feature: SamsungBudsSettingsFeatureState, enabled: Boolean) =
            toggle.render(value(feature), enabled)
    }

    private inner class ActionRow(
        private val container: WeakReference<View>,
        private val valueView: WeakReference<TextView>,
        var current: SamsungTouchAction = SamsungTouchAction.NOISE_CONTROL,
    ) {
        fun render(value: SamsungTouchAction, enabled: Boolean) {
            current = value
            // This view belongs to MiLink: module R IDs are not valid in its Resources.
            val caption = actionLabel(current) + "  ›"
            valueView.get()?.text = caption
            container.get()?.apply {
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.45f
            }
        }
    }

    companion object {
        private const val CONFIRMATION_TIMEOUT_MS = 1_800L

        fun open(
            anchor: View,
            address: String,
            environment: MiLinkCardEnvironment,
        ): MiLinkToggleDetails = SamsungTouchControlDetails(anchor, address, environment)
    }
}

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

private fun Context.themeColor(attribute: Int, fallback: Int): Int {
    val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    return when (attribute) {
        android.R.attr.colorBackground -> if (dark) 0xFF101010.toInt() else 0xFFF5F5F5.toInt()
        android.R.attr.colorBackgroundFloating -> if (dark) 0xFF242424.toInt() else Color.WHITE
        android.R.attr.textColorPrimary -> if (dark) 0xFFF2F2F2.toInt() else 0xFF191919.toInt()
        android.R.attr.textColorSecondary -> if (dark) 0xFFA0A0A0.toInt() else 0xFF808080.toInt()
        android.R.attr.colorAccent -> 0xFF3482FF.toInt()
        else -> fallback
    }
}
