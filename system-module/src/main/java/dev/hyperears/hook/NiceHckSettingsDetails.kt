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
import java.lang.ref.WeakReference
import java.util.Locale

/** MiLink-hosted settings. Device reports are authoritative; editor values are unsent drafts. */
internal class NiceHckSettingsDetails private constructor(
    anchor: View, private val address: String, private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val context = anchor.context
    private val token = environment.sessionTokenProvider(address)
    private val popup = PopupWindow(context)
    private val handler = Handler(Looper.getMainLooper())
    private val renders = mutableListOf<(NiceHckSettingsFeatureState) -> Unit>()
    private var unsubscribe: (() -> Unit)? = null
    private var closed = false
    private var rendering = false
    private var pending: NiceHckControlRequest.SetValue? = null
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
        token == environment.sessionTokenProvider(address) && NiceHckCardPolicy.available(state, address)
    override fun render(state: EarbudState) {
        if (closed) return
        try { renderState(state) } catch (_: Exception) { dismiss() }
    }
    private fun renderState(state: EarbudState) {
        if (closed) return
        if (!valid(state)) { dismiss(); return }
        val settings = state.features.get<NiceHckSettingsFeatureState>() ?: return
        pending?.let {
            if (NiceHckCardPolicy.matches(settings, it)) {
                pending = null; handler.removeCallbacks(timeout); status.text = "耳机已确认设置。"
            }
        }
        rendering = true
        try { renders.forEach { it(settings) } } finally { rendering = false }
    }
    private fun send(request: NiceHckControlRequest.SetValue) {
        if (closed || rendering) return
        try {
            val state = environment.stateProvider(address)
            if (!valid(state)) { dismiss(); return }
            val accepted = NiceHckCardPolicy.request(state, address, request) ?: run { render(state); return }
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
        body.addView(text("NiceHCK 耳机设置", 22f))
        val firmware = text("", 14f)
        body.addView(firmware)
        renders += { state ->
            firmware.visibility = if (state.firmwareMajor != null && state.firmwareMinor != null) View.VISIBLE else View.GONE
            firmware.text = String.format(Locale.getDefault(), "固件：%s.%s", state.firmwareMajor, state.firmwareMinor)
        }
        body.addView(text("关闭", 16f).apply { setOnClickListener { dismiss() } })
        body.addView(status)
        val controls = column()
        val labels = mapOf(NiceHckSetting.NOISE to "噪声模式", NiceHckSetting.GAME to "游戏模式",
            NiceHckSetting.EQ to "均衡器", NiceHckSetting.LOW_LATENCY to "低延迟",
            NiceHckSetting.MULTIPOINT to "双设备", NiceHckSetting.WEAR_DETECTION to "入耳检测",
            NiceHckSetting.WIND_SUPPRESSION to "独立风噪抑制")
        val names = NiceHckSetting.entries.associateWith { setting -> when (setting) {
            NiceHckSetting.NOISE -> mapOf(0 to "关闭", 1 to "通透", 2 to "普通降噪", 3 to "深度降噪", 16 to "试验性降噪", 17 to "抗风噪模式")
            NiceHckSetting.EQ -> mapOf(0 to "悔恨之泪", 1 to "均衡中正", 2 to "欧美澎湃", 3 to "真律还原", 4 to "游戏优化", 5 to "细腻佳音", 6 to "温婉人声")
            else -> mapOf(0 to "关闭", 1 to "开启")
        } }
        NiceHckSetting.entries.forEach { setting ->
            val label = labels.getValue(setting)
            val row = column(); row.addView(text(label, 17f)); controls.addView(row)
            val options = setting.values.sorted().map { value ->
                val name = names[setting]?.get(value) ?: value.toString()
                text(name, 16f).apply {
                    contentDescription = "$label $name"
                    setOnClickListener { send(NiceHckControlRequest.SetValue(setting, value)) }
                    row.addView(this)
                } to value
            }
            renders += { state ->
                val current = state.values[setting]
                row.visibility = if (current == null) View.GONE else View.VISIBLE
                options.forEach { (option, value) ->
                    option.visibility = if (value in state.allowed(setting) || value == current) View.VISIBLE else View.GONE
                    option.isEnabled = current != null && value in state.allowed(setting)
                    option.isSelected = current == value
                    option.text = String.format(Locale.getDefault(), "%s%s",
                        if (option.isSelected) "✓ " else "", names[setting]?.get(value) ?: value.toString())
                }
            }
        }
        body.addView(ScrollView(context).apply { addView(controls) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return body
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
            try { NiceHckSettingsDetails(anchor, address, environment) } catch (error: Exception) {
                ModuleLog.warn("MiLinkUi", "NiceHCK settings unavailable (${error.javaClass.simpleName})")
                ClosedDetails
            }
        private object ClosedDetails : MiLinkToggleDetails {
            override fun render(state: EarbudState) = Unit
            override fun dismiss() = Unit
        }
    }
}
