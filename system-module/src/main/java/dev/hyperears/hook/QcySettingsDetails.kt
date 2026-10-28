package dev.hyperears.hook

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.hyperears.integration.*
import dev.hyperears.protocol.qcy.QcyWireCodec
import java.lang.ref.WeakReference
import java.util.Locale

/** MiLink-hosted settings. Device reports are authoritative; editor values are unsent drafts. */
internal class QcySettingsDetails private constructor(
    anchor: View, private val address: String, private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val context = anchor.context
    private val token = environment.sessionTokenProvider(address)
    private val popup = PopupWindow(context)
    private val handler = Handler(Looper.getMainLooper())
    private val renders = mutableListOf<(QcySettingsFeatureState) -> Unit>()
    private var unsubscribe: (() -> Unit)? = null
    private var closed = false
    private var rendering = false
    private var pending: QcyControlRequest? = null
    private val status = text("", 14f)
    private val timeout = Runnable {
        if (!closed && pending != null) {
            pending = null
            status.text = "尚未收到确认，请检查耳机连接后重试。"
        }
    }
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = dismiss()
    }
    init {
        try {
            popup.contentView = content()
            popup.width = (context.resources.displayMetrics.widthPixels * .92f).toInt()
            popup.height = (context.resources.displayMetrics.heightPixels * .82f).toInt()
            popup.isFocusable = true
            popup.isOutsideTouchable = true
            popup.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            popup.setOnDismissListener { dismiss() }
            anchor.addOnAttachStateChangeListener(detach)
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
        } catch (error: Exception) { dismiss(); throw error }
    }
    private fun valid(state: EarbudState) = token != null &&
        token == environment.sessionTokenProvider(address) && QcyCardPolicy.available(state, address)
    override fun render(state: EarbudState) {
        if (closed) return
        try { renderState(state) } catch (_: Exception) { dismiss() }
    }
    private fun renderState(state: EarbudState) {
        if (closed) return
        if (!valid(state)) { dismiss(); return }
        val settings = state.features.get<QcySettingsFeatureState>() ?: return
        pending?.let {
            if (QcyCardPolicy.matches(settings, it)) {
                pending = null; handler.removeCallbacks(timeout); status.text = "耳机已确认设置。"
            }
        }
        rendering = true
        try { renders.forEach { it(settings) } } finally { rendering = false }
    }
    private fun send(request: QcyControlRequest) {
        if (closed || rendering) return
        try {
            val state = environment.stateProvider(address)
            if (!valid(state)) { dismiss(); return }
            val accepted = QcyCardPolicy.request(state, address, request) ?: run { render(state); return }
            pending = accepted
            status.text = "已发送，等待耳机确认…"
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, 5000)
            environment.controlSender(address, accepted)
            render(environment.stateProvider(address))
        } catch (_: Exception) {
            pending = null; handler.removeCallbacks(timeout)
            status.text = "发送未完成，请重试。"
            runCatching { render(environment.stateProvider(address)) }.onFailure { dismiss() }
        }
    }
    override fun dismiss() {
        if (closed) return
        closed = true
        handler.removeCallbacks(timeout)
        pending = null
        anchor.get()?.removeOnAttachStateChangeListener(detach)
        val release = unsubscribe; unsubscribe = null
        runCatching { release?.invoke() }
        runCatching { if (popup.isShowing) popup.dismiss() }
    }
    private fun content(): View {
        val body = column().apply {
            setPadding(dp(20), dp(12), dp(20), dp(20))
            background = GradientDrawable().apply { setColor(color(false)); cornerRadius = dp(24).toFloat() }
        }
        body.addView(text("QCY 高级设置", 22f))
        body.addView(text("关闭", 16f).apply { setOnClickListener { dismiss() } })
        body.addView(status)
        val controls = column()
        val labels = listOf("低延迟模式", "睡眠模式", "LDAC", "双设备连接", "动态 EQ", "自适应音量")
        QcyToggle.entries.forEachIndexed { index, setting ->
            val row = column()
            row.addView(text(labels[index], 17f))
            val toggle = createToggle().apply {
                contentDescription = labels[index]
                isSaveEnabled = false
                setOnCheckedChangeListener { _, enabled -> send(QcyControlRequest.SetToggle(setting, enabled)) }
            }
            row.addView(toggle); controls.addView(row)
            renders += { state ->
                val value = state.toggles[setting]
                row.visibility = if (value == null) View.GONE else View.VISIBLE
                if (value != null) toggle.isChecked = value
            }
        }
        addChoices(controls, "空间音效", listOf(0 to "关闭", 1 to "模式 1", 2 to "模式 2"),
            { it.spatialMode }, { QcyControlRequest.SetSpatialMode(it) })
        addChoices(controls, "耳机均衡器预设", listOf(1 to "默认", 2 to "流行", 3 to "低音", 4 to "摇滚",
            5 to "柔和", 6 to "古典", 10 to "空间", 0 to "自定义"),
            { it.eqPreset }, { QcyControlRequest.SetEqPreset(it) })
        addEqualizer(controls)
        body.addView(ScrollView(context).apply { addView(controls) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return body
    }
    private fun addChoices(parent: LinearLayout, label: String, values: List<Pair<Int, String>>,
        current: (QcySettingsFeatureState) -> Int?, request: (Int) -> QcyControlRequest) {
        val row = column(); row.addView(text(label, 17f))
        val options = values.map { (value, name) -> text(name, 16f).apply {
            contentDescription = "$label $name"
            setOnClickListener { send(request(value)) }; row.addView(this)
        } }
        parent.addView(row)
        renders += { state ->
            val value = current(state)
            row.visibility = if (value == null) View.GONE else View.VISIBLE
            options.forEachIndexed { index, option ->
                option.isSelected = value == values[index].first
                option.text = String.format(Locale.getDefault(), "%s%s", if (option.isSelected) "✓ " else "", values[index].second)
            }
        }
    }
    private fun addEqualizer(parent: LinearLayout) {
        val row = column()
        val report = text("", 14f); row.addView(report)
        val editor = column()
        val draft = MutableList(10) { 0 }
        var initialized = false
        val sliders = mutableListOf<SeekBar>()
        val captions = mutableListOf<TextView>()
        row.addView(text("编辑十段均衡器", 17f))
        row.addView(text("下方为待应用草稿：固定频率，Q=1，峰值滤波，总增益 0 dB。", 14f))
        QcyWireCodec.customFrequencies.forEachIndexed { index, frequency ->
            val caption = text("$frequency Hz：0 dB", 16f); captions += caption; editor.addView(caption)
            val slider = SeekBar(context).apply {
                min = 0; max = 16; progress = 8; isSaveEnabled = false
                contentDescription = "$frequency Hz 增益"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        if (fromUser) {
                            draft[index] = value - 8
                            caption.text = String.format(Locale.getDefault(), "%d Hz：%d dB（草稿）", frequency, draft[index])
                        }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            }; sliders += slider; editor.addView(slider)
        }
        row.addView(editor)
        row.addView(text("应用均衡器草稿", 17f).apply {
            setOnClickListener { send(QcyControlRequest.SetCustomEq(draft.toList())) }
        })
        row.addView(text("从耳机回报重新载入", 16f).apply {
            setOnClickListener {
                runCatching {
                    val state = environment.stateProvider(address)
                    if (!valid(state)) dismiss() else { initialized = false; render(state) }
                }.onFailure { dismiss() }
            }
        })
        parent.addView(row)
        renders += { state ->
            val eq = state.equalizer
            row.visibility = if (eq == null) View.GONE else View.VISIBLE
            if (eq != null) {
                report.text = String.format(Locale.getDefault(), "耳机回报：预设 %d，总增益 %.2f dB\n%s",
                    eq.preset, eq.masterGainHundredths / 100.0,
                    eq.bands.joinToString("\n") { String.format(Locale.getDefault(),
                        "%d Hz · %.2f dB · Q %.2f · 类型 %d", it.frequency,
                        it.gainHundredths / 100.0, it.qHundredths / 100.0, it.type) })
                if (!initialized) {
                    val gains = eq.customGains() ?: List(10) { 0 }
                    gains.forEachIndexed { index, gain ->
                        draft[index] = gain; sliders[index].progress = gain + 8
                        captions[index].text = String.format(Locale.getDefault(), "%d Hz：%d dB（草稿）", QcyWireCodec.customFrequencies[index], gain)
                    }
                    initialized = true
                }
            }
        }
    }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun createToggle(): CompoundButton = runCatching {
        Class.forName("miuix.slidingwidget.widget.SlidingButton", true, environment.hostClassLoader)
            .asSubclass(CompoundButton::class.java).getConstructor(Context::class.java).newInstance(context)
    }.getOrElse { @Suppress("DEPRECATION") Switch(context) }
    private fun text(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color(true)); setPadding(0, dp(10), 0, dp(10))
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun color(text: Boolean): Int {
        val dark = context.resources.configuration.uiMode and 0x30 == 0x20
        return if (text) { if (dark) Color.WHITE else Color.BLACK }
        else if (dark) 0xff242424.toInt() else 0xfff5f5f5.toInt()
    }
    companion object {
        fun open(anchor: View, address: String, environment: MiLinkCardEnvironment): MiLinkToggleDetails =
            try { QcySettingsDetails(anchor, address, environment) } catch (error: Exception) {
                ModuleLog.warn("MiLinkUi", "QCY settings unavailable (${error.javaClass.simpleName})")
                ClosedDetails
            }
        private object ClosedDetails : MiLinkToggleDetails {
            override fun render(state: EarbudState) = Unit
            override fun dismiss() = Unit
        }
    }
}
