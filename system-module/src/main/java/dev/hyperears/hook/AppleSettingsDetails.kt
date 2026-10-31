package dev.hyperears.hook

import java.util.Locale

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.hyperears.integration.*
import java.lang.ref.WeakReference

/** Second-level panel hosted entirely by MiLink, with accepted reports as the only UI truth. */
internal class AppleSettingsDetails private constructor(
    anchor: View,
    private val address: String,
    private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val token = environment.sessionTokenProvider(address)
    private val context = anchor.context
    private val rows = mutableListOf<Row>()
    private val behaviorRows = mutableListOf<(EarbudState) -> Unit>()
    private var closed = false
    private var rendering = false
    private var unsubscribe: (() -> Unit)? = null
    private val popup = PopupWindow(context)
    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = dismiss()
    }

    init {
        try {
            popup.contentView = buildContent()
            popup.width = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
            popup.height = (context.resources.displayMetrics.heightPixels * 0.82f).toInt()
            popup.isFocusable = true
            popup.isOutsideTouchable = true
            popup.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            popup.setOnDismissListener { dismiss() }
            anchor.addOnAttachStateChangeListener(detachListener)
            // Accommodate a provider that delivers an initial callback synchronously.
            val release = environment.observeState(address, ::render)
            if (closed) release() else unsubscribe = release
            render(environment.stateProvider(address))
            if (!anchor.isAttachedToWindow || anchor.windowToken == null) dismiss()
            anchor.post {
                if (!closed) runCatching {
                    check(anchor.isAttachedToWindow && anchor.windowToken != null)
                    render(environment.stateProvider(address))
                    if (!closed) popup.showAtLocation(anchor.rootView, Gravity.CENTER, 0, 0)
                }.onFailure { dismiss() }
            }
        } catch (error: Exception) {
            dismiss()
            throw error
        }
    }

    private fun valid(state: EarbudState): Boolean = token != null &&
        environment.sessionTokenProvider(address) == token && AppleSettingsCardPolicy.available(state, address)

    override fun render(state: EarbudState) {
        if (closed) return
        if (!valid(state)) { dismiss(); return }
        val feature = state.features.get<AppleSettingsFeatureState>()
        rendering = true
        try {
            rows.forEach { row ->
                val value = feature?.let(row.setting::value)
                row.container.visibility = if (value == null) View.GONE else View.VISIBLE
                if (value != null) row.render(value, AppleSettingsCardPolicy.enabled(state, row.setting))
            }
            behaviorRows.forEach { it(state) }
        } finally { rendering = false }
    }

    private fun send(setting: AppleSetting, value: Int) {
        if (rendering || closed) return
        val state = environment.stateProvider(address)
        if (!valid(state)) { dismiss(); return }
        try {
            AppleSettingsCardPolicy.request(state, address, setting, value)?.let {
                runCatching { environment.controlSender(address, it) }
                    .onFailure { ModuleLog.warn("MiLinkUi", "Apple setting send failed", it) }
            }
        } finally {
            // Widgets locally change on touch; restore accepted state until the headset replies.
            render(environment.stateProvider(address))
        }
    }

    private fun sendBehavior(build: (EarbudState) -> AppleControlRequest?) {
        if (rendering || closed) return
        val state = environment.stateProvider(address)
        if (!valid(state)) { dismiss(); return }
        try {
            build(state)?.let { AppleSettingsCardPolicy.behaviorRequest(state, address, it) }?.let {
                runCatching { environment.controlSender(address, it) }
                    .onFailure { ModuleLog.warn("MiLinkUi", "Apple setting send failed", it) }
            }
        } finally { render(environment.stateProvider(address)) }
    }

    override fun dismiss() {
        if (closed) return
        closed = true
        anchor.get()?.removeOnAttachStateChangeListener(detachListener)
        val release = unsubscribe
        unsubscribe = null
        try { release?.invoke() } finally { if (popup.isShowing) popup.dismiss() }
    }

    private fun buildContent(): View {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(20))
            background = GradientDrawable().apply { setColor(color(false)); cornerRadius = dp(24).toFloat() }
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("耳机高级设置", 22f), LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(text("关闭", 16f).apply { setOnClickListener { dismiss() } }, LinearLayout.LayoutParams(dp(64), dp(56)))
        body.addView(header)
        val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        AppleSetting.entries.forEach { setting ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                visibility = View.GONE
            }
            val caption = text(setting.label, 17f)
            row.addView(caption)
            when (setting) {
                AppleSetting.WEARING_DETECTION -> row.addView(text("开启后，摘下暂停当前媒体，戴回恢复由本功能暂停的播放。", 13f))
                AppleSetting.CONVERSATION_AWARENESS -> row.addView(text("开启后，说话时自动降低这副耳机的媒体声音，结束后恢复。", 13f))
                else -> Unit
            }
            val render: (Int, Boolean) -> Unit
            if (setting.isToggle) {
                val toggle = createToggle().apply {
                    contentDescription = setting.label
                    isSaveEnabled = false
                    setOnCheckedChangeListener { _, checked -> send(setting, if (checked) 1 else 0) }
                }
                row.addView(toggle)
                render = { value, enabled -> toggle.isChecked = value == 1; toggle.isEnabled = enabled }
            } else if (setting.choices.isNotEmpty()) {
                val group = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val options = setting.choices.mapIndexed { index, label ->
                    text(label, 15f).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(12), 0, dp(12))
                        setOnClickListener { send(setting, index) }
                        group.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                }
                row.addView(group)
                render = { value, enabled -> options.forEachIndexed { index, option ->
                    option.text = String.format(Locale.getDefault(), if (index == value) "✓ %s" else "%s", setting.choices[index])
                    option.isSelected = value == index
                    option.isEnabled = enabled
                } }
            } else {
                var tracking = false
                val slider = SeekBar(context).apply {
                    min = setting.range.first
                    max = setting.range.last
                    contentDescription = setting.label
                    isSaveEnabled = false
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                            if (fromUser && !tracking) send(setting, progress)
                        }
                        override fun onStartTrackingTouch(bar: SeekBar) { tracking = true }
                        override fun onStopTrackingTouch(bar: SeekBar) { tracking = false; send(setting, bar.progress) }
                    })
                }
                row.addView(slider)
                render = { value, enabled ->
                    caption.text = String.format(Locale.getDefault(), "%s：%d%s", setting.label, value,
                        if (enabled) "" else "（仅自适应模式可调）")
                    slider.progress = value
                    slider.isEnabled = enabled
                }
            }
            rows += Row(setting, row, render)
            controls.addView(row)
        }
        addSpatialControls(controls)
        addBehaviorControls(controls)
        body.addView(ScrollView(context).apply { addView(controls) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return body
    }

    private fun sendSpatial(request: AppleSpatialControlRequest) {
        if (rendering || closed) return
        val state = environment.stateProvider(address)
        if (!valid(state)) { dismiss(); return }
        val feature = state.features.get<AppleSpatialFeatureState>() ?: return
        if (!feature.rendererAvailable) return
        runCatching { environment.controlSender(address, request) }
            .onFailure { ModuleLog.warn("MiLinkUi", "Spatial control failed", it) }
        render(environment.stateProvider(address))
    }

    private fun addSpatialControls(controls: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        row.addView(text("空间音频", 17f))
        val status = text("", 13f); row.addView(status)
        val group = LinearLayout(context)
        val modes = listOf("关闭", "固定", "头部追踪").mapIndexed { index, label ->
            text(label, 15f).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { sendSpatial(AppleSpatialControlRequest.SetMode(AppleSpatialMode.entries[index])) }
                group.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        row.addView(group)
        val calibrate = text("校准头部方向", 15f).apply {
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { sendSpatial(AppleSpatialControlRequest.BeginCalibration) }
        }
        val step = text("开始记录", 15f).apply {
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { sendSpatial(AppleSpatialControlRequest.CalibrationStep) }
        }
        val recenter = text("以当前方向回中", 15f).apply {
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { sendSpatial(AppleSpatialControlRequest.Recenter) }
        }
        row.addView(calibrate); row.addView(step); row.addView(recenter); controls.addView(row)
        behaviorRows += { state ->
            val feature = state.features.get<AppleSpatialFeatureState>()
            row.visibility = if (feature?.rendererAvailable == true) View.VISIBLE else View.GONE
            if (feature != null) {
                modes.forEachIndexed { index, view ->
                    val mode = AppleSpatialMode.entries[index]
                    view.isEnabled = mode == AppleSpatialMode.OFF || !feature.pending &&
                        (mode == AppleSpatialMode.FIXED || feature.trackerAvailable && feature.calibrated)
                    view.alpha = if (view.isEnabled) 1f else 0.4f
                    view.isSelected = feature.mode == mode
                }
                calibrate.visibility = if (feature.trackerAvailable && feature.calibrationStep < 0) View.VISIBLE else View.GONE
                calibrate.isEnabled = !feature.pending
                step.visibility = if (feature.calibrationStep in 0..2) View.VISIBLE else View.GONE
                step.text = if (feature.collecting) "完成本步" else "开始记录"
                recenter.visibility = if (feature.mode == AppleSpatialMode.TRACKING) View.VISIBLE else View.GONE
                status.text = when {
                    feature.calibrationStep in 0..2 -> "保持手机不动，" + listOf("头向右转", "抬头", "头向右倾斜")[feature.calibrationStep] + "，记录后完成本步。"
                    feature.pending -> "等待耳机与系统确认…"
                    feature.mode == AppleSpatialMode.TRACKING -> "系统头部追踪已接入；可回中。"
                    feature.mode == AppleSpatialMode.FIXED -> "系统固定空间音频已启用。"
                    feature.trackerAvailable && !feature.calibrated -> "使用头部追踪前，请完成三轴方向校准。"
                    else -> "系统空间音频已关闭。"
                }
            }
        }
    }

    private fun addBehaviorControls(controls: LinearLayout) {
        fun row(label: String): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(text(label, 17f))
            controls.addView(this)
        }
        val wear = text("", 16f).also { controls.addView(it) }
        val conversation = text("", 16f).also { controls.addView(it) }
        behaviorRows += { state ->
            val report = state.features.get<AppleWearFeatureState>()
            wear.visibility = if (report == null) View.GONE else View.VISIBLE
            report?.let { wear.text = String.format(Locale.getDefault(), "主耳：%s · 副耳：%s", wearLabel(it.primary), wearLabel(it.secondary)) }
            val activity = state.features.get<AppleConversationFeatureState>()
            conversation.visibility = if (activity == null) View.GONE else View.VISIBLE
            activity?.let {
                conversation.text = String.format(Locale.getDefault(), "耳机对话活动：%s", when (it.supplementaryActivity) {
                    AppleConversationActivity.START -> "进行中"
                    AppleConversationActivity.RELEASE -> "已结束"
                    AppleConversationActivity.UNKNOWN -> "状态未知"
                })
            }
        }
        AppleBud.entries.forEach { bud ->
            val label = if (bud == AppleBud.LEFT) "左耳长按" else "右耳长按"
            val group = row(label)
            val options = AppleLongPressAction.entries.map { action ->
                val title = if (action == AppleLongPressAction.NOISE_CONTROL) "噪声控制" else "语音助手"
                text(title, 16f).apply {
                    contentDescription = "$label：$title"
                    setPadding(0, dp(12), 0, dp(12))
                    setOnClickListener { sendBehavior { AppleControlRequest.SetLongPress(bud, action) } }
                    group.addView(this)
                } to title
            }
            behaviorRows += { state ->
                val report = state.features.get<AppleGestureSettingsFeatureState>()
                val selected = if (bud == AppleBud.LEFT) report?.leftLongPress else report?.rightLongPress
                group.visibility = if (selected == null) View.GONE else View.VISIBLE
                options.forEachIndexed { index, (option, title) ->
                    option.isSelected = selected == AppleLongPressAction.entries[index]
                    option.text = if (option.isSelected) "✓ $title" else title
                    option.isEnabled = report?.leftLongPress != null && report.rightLongPress != null
                }
            }
        }
        val cycle = row("长按循环模式")
        val modes = AppleListeningMode.entries.map { mode ->
            val title = when (mode) {
                AppleListeningMode.OFF -> "关闭"
                AppleListeningMode.ANC -> "降噪"
                AppleListeningMode.TRANSPARENCY -> "通透"
                AppleListeningMode.ADAPTIVE -> "自适应"
            }
            createToggle().apply {
                text = title
                contentDescription = "长按循环：$title"
                isSaveEnabled = false
                setOnCheckedChangeListener { _, checked -> sendBehavior { state ->
                    state.features.get<AppleGestureSettingsFeatureState>()?.listeningCycleMask?.let { mask ->
                        val bit = AppleSettingsCardPolicy.cycleBit(mode)
                        AppleControlRequest.SetListeningCycle(if (checked) mask or bit else mask and bit.inv())
                    }
                } }
                cycle.addView(this)
            }
        }
        behaviorRows += { state ->
            val mask = state.features.get<AppleGestureSettingsFeatureState>()?.listeningCycleMask
            val supported = state.features.get<AppleListeningModeFeatureState>()?.supportedModes.orEmpty()
            cycle.visibility = if (mask in 1..15 && supported.isNotEmpty()) View.VISIBLE else View.GONE
            modes.forEachIndexed { index, toggle ->
                val mode = AppleListeningMode.entries[index]
                toggle.visibility = if (mode in supported) View.VISIBLE else View.GONE
                toggle.isEnabled = mask in 1..15 && mode in supported
                toggle.isChecked = mask != null && mask and AppleSettingsCardPolicy.cycleBit(mode) != 0
            }
        }
        val eq = row("耳机均衡器")
        val eqMode = createToggle().apply {
            text = "自定义均衡器"
            contentDescription = "自定义均衡器"
            isSaveEnabled = false
            setOnCheckedChangeListener { _, checked -> sendBehavior { state ->
                AppleSettingsCardPolicy.equalizer(state)?.let {
                    AppleControlRequest.SetEqualizer(if (checked) 2 else 1, it.low, it.mid, it.high)
                }
            } }
            eq.addView(this)
        }
        val bands = listOf("低频", "中频", "高频").mapIndexed { index, title ->
            val caption = text(title, 16f).also { eq.addView(it) }
            var tracking = false
            val slider = SeekBar(context).apply {
                min = 0; max = 100; contentDescription = title; isSaveEnabled = false
                fun change(value: Int) = sendBehavior { state ->
                    AppleSettingsCardPolicy.equalizer(state)?.takeIf { it.state == 2 }?.let {
                        AppleControlRequest.SetEqualizer(it.state, if (index == 0) value else it.low,
                            if (index == 1) value else it.mid, if (index == 2) value else it.high)
                    }
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser && !tracking) change(progress)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) { tracking = true }
                    override fun onStopTrackingTouch(bar: SeekBar) { tracking = false; change(bar.progress) }
                })
                eq.addView(this)
            }
            caption to slider
        }
        behaviorRows += { state ->
            val report = AppleSettingsCardPolicy.equalizer(state)
            eq.visibility = if (report == null) View.GONE else View.VISIBLE
            eqMode.isChecked = report?.state == 2
            report?.let {
                val values = listOf(it.low, it.mid, it.high)
                bands.forEachIndexed { index, (caption, slider) ->
                    caption.text = String.format(Locale.getDefault(), "%s：%d", slider.contentDescription, values[index])
                    slider.progress = values[index]
                    slider.isEnabled = it.state == 2
                }
            }
        }
    }

    private fun wearLabel(position: AppleWearPosition): String = when (position) {
        AppleWearPosition.IN_EAR -> "佩戴中"
        AppleWearPosition.OUT_OF_EAR -> "未佩戴"
        AppleWearPosition.IN_CASE -> "盒内"
        AppleWearPosition.DISCONNECTED -> "未连接"
    }

    private fun createToggle(): CompoundButton = runCatching {
        Class.forName("miuix.slidingwidget.widget.SlidingButton", true, environment.hostClassLoader)
            .asSubclass(CompoundButton::class.java).getConstructor(Context::class.java).newInstance(context)
    }.getOrElse { @Suppress("DEPRECATION") Switch(context) }

    private fun text(value: String, size: Float) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color(true))
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun color(text: Boolean): Int {
        val dark = context.resources.configuration.uiMode and 0x30 == 0x20
        return if (text) { if (dark) Color.WHITE else Color.BLACK } else {
            if (dark) 0xff242424.toInt() else 0xfff5f5f5.toInt()
        }
    }
    private data class Row(val setting: AppleSetting, val container: View, val render: (Int, Boolean) -> Unit)

    companion object {
        fun open(anchor: View, address: String, environment: MiLinkCardEnvironment): MiLinkToggleDetails =
            try {
                AppleSettingsDetails(anchor, address, environment)
            } catch (error: Exception) {
                // Host exceptions can contain addresses or state payloads; log only their type.
                ModuleLog.warn("MiLinkUi", "Apple settings panel unavailable (${error.javaClass.simpleName})")
                ClosedDetails
            }

        private object ClosedDetails : MiLinkToggleDetails {
            override fun render(state: EarbudState) = Unit
            override fun dismiss() = Unit
        }
    }
}
