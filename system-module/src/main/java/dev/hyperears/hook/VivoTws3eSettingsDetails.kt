package dev.hyperears.hook

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.hyperears.integration.*
import java.lang.ref.WeakReference

/** Event-driven details: no polling and no optimistic selection after a command. */
@android.annotation.SuppressLint("SetTextI18n")
internal class VivoTws3eSettingsDetails private constructor(
    anchor: View, private val address: String, private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val token = environment.sessionTokenProvider(address)
    private val context = anchor.context
    private val popup = PopupWindow(context)
    private var closed = false
    private var rendering = false
    private var unsubscribe: (() -> Unit)? = null
    private val rows = mutableListOf<(VivoTws3eFeatureState) -> Unit>()
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = dismiss()
    }
    init {
        try {
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat() }
            }
            body.addView(TextView(context).apply { text = "TWS 3e 高级设置"; textSize = 22f; setTextColor(Color.BLACK) })
            choice(body, "左耳双击", VivoDoubleTap.entries, { it.leftTap }, ::tapLabel) {
                VivoTws3eControlRequest.SetDoubleTap(false, it)
            }
            choice(body, "右耳双击", VivoDoubleTap.entries, { it.rightTap }, ::tapLabel) {
                VivoTws3eControlRequest.SetDoubleTap(true, it)
            }
            choice(body, "左耳长按", VivoHoldCycle.entries,
                { if (it.rightHold != null) it.leftHold else null }, ::holdLabel) { selected ->
                current()?.rightHold?.let { VivoTws3eControlRequest.SetHold(selected, it) }
            }
            choice(body, "右耳长按", VivoHoldCycle.entries,
                { if (it.leftHold != null) it.rightHold else null }, ::holdLabel) { selected ->
                current()?.leftHold?.let { VivoTws3eControlRequest.SetHold(it, selected) }
            }
            choice(body, "DeepX 音效", VivoDeepX.entries, { it.deepX }, { when (it) {
                VivoDeepX.DEFAULT -> "默认"; VivoDeepX.VOICE -> "清澈人声"; VivoDeepX.BASS -> "超重低音"
                VivoDeepX.TREBLE -> "清亮高音"; VivoDeepX.AUDIOBOOK -> "悠扬听书"
            } }) { VivoTws3eControlRequest.SetDeepX(it) }
            choice(body, "佩戴检测", listOf(false, true), { it.wearDetection }, { if (it) "开启" else "关闭" }) {
                VivoTws3eControlRequest.SetWearDetection(it)
            }
            body.addView(Button(context).apply { text = "关闭"; setOnClickListener { dismiss() } })
            popup.contentView = ScrollView(context).apply { addView(body) }
            popup.width = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
            popup.height = (context.resources.displayMetrics.heightPixels * 0.80f).toInt()
            popup.isFocusable = true; popup.isOutsideTouchable = true
            popup.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            popup.setOnDismissListener { dismiss() }
            anchor.addOnAttachStateChangeListener(detach)
            val release = environment.observeState(address, ::render)
            if (closed) release() else unsubscribe = release
            render(environment.stateProvider(address))
            if (!anchor.isAttachedToWindow || anchor.windowToken == null) dismiss()
            anchor.post {
                if (!closed) runCatching {
                    render(environment.stateProvider(address))
                    if (!closed && anchor.isAttachedToWindow && anchor.windowToken != null)
                        popup.showAtLocation(anchor.rootView, Gravity.CENTER, 0, 0)
                    else dismiss()
                }.onFailure { dismiss() }
            }
        } catch (e: Exception) { dismiss(); throw e }
    }
    private fun current() = environment.stateProvider(address).features.get<VivoTws3eFeatureState>()
    private fun <T> choice(body: LinearLayout, title: String, values: List<T>,
        selected: (VivoTws3eFeatureState) -> T?, label: (T) -> String,
        request: (T) -> VivoTws3eControlRequest?) {
        val group = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        group.addView(TextView(context).apply { text = title; textSize = 17f; setTextColor(Color.BLACK) })
        val buttons = RadioGroup(context)
        val options = values.map { value -> RadioButton(context).apply {
            id = View.generateViewId(); text = label(value); setTextColor(Color.BLACK); isSaveEnabled = false
            setOnClickListener { guarded { if (!rendering) request(value)?.let(::send) } }
        }.also(buttons::addView) }
        group.addView(buttons); body.addView(group)
        rows += { state ->
            val value = selected(state)
            group.visibility = if (value == null) View.GONE else View.VISIBLE
            options.forEachIndexed { i, button -> button.isChecked = value == values[i]; button.isEnabled = value != null }
        }
    }
    private fun valid(state: EarbudState) = token != null && token == environment.sessionTokenProvider(address) &&
        VivoTws3eSettingsPolicy.available(state, address)
    private fun send(request: VivoTws3eControlRequest) {
        if (closed || rendering) return
        val state = environment.stateProvider(address)
        if (!valid(state)) { dismiss(); return }
        try {
            VivoTws3eSettingsPolicy.request(state, address, request)?.let {
                runCatching { environment.controlSender(address, it) }
                    .onFailure { ModuleLog.warn("MiLinkUi", "TWS 3e setting send failed: ${it.javaClass.simpleName}") }
            }
        } finally { render(environment.stateProvider(address)) }
    }
    private inline fun guarded(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            ModuleLog.warn("MiLinkUi", "TWS 3e callback failed: ${error.javaClass.simpleName}")
            dismiss()
        }
    }
    override fun render(state: EarbudState) = guarded { renderAccepted(state) }
    private fun renderAccepted(state: EarbudState) {
        if (closed) return
        if (!valid(state)) { dismiss(); return }
        val settings = state.features.get<VivoTws3eFeatureState>() ?: return
        rendering = true
        try { rows.forEach { it(settings) } } finally { rendering = false }
    }
    override fun dismiss() {
        if (closed) return
        closed = true
        anchor.get()?.removeOnAttachStateChangeListener(detach)
        val release = unsubscribe; unsubscribe = null
        runCatching { release?.invoke() }
        runCatching { if (popup.isShowing) popup.dismiss() }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun tapLabel(action: VivoDoubleTap) = when (action) {
        VivoDoubleTap.ASSISTANT -> "语音助手"; VivoDoubleTap.PLAY_PAUSE -> "播放 / 暂停"
        VivoDoubleTap.PREVIOUS -> "上一首"; VivoDoubleTap.NEXT -> "下一首"; VivoDoubleTap.NONE -> "无操作"
    }
    private fun holdLabel(cycle: VivoHoldCycle) = when (cycle) {
        VivoHoldCycle.ANC_OFF -> "降噪 / 关闭"; VivoHoldCycle.TRANSPARENCY_OFF -> "通透 / 关闭"
        VivoHoldCycle.ANC_TRANSPARENCY -> "降噪 / 通透"; VivoHoldCycle.ALL -> "降噪 / 关闭 / 通透"
        VivoHoldCycle.NONE -> "无操作"
    }
    companion object {
        fun open(anchor: View, address: String, environment: MiLinkCardEnvironment): MiLinkToggleDetails =
            runCatching<MiLinkToggleDetails> { VivoTws3eSettingsDetails(anchor, address, environment) }
                .getOrElse {
                    ModuleLog.warn("MiLinkUi", "TWS 3e details unavailable: ${it.javaClass.simpleName}")
                    object : MiLinkToggleDetails {
                        override fun render(state: EarbudState) = Unit
                        override fun dismiss() = Unit
                    }
                }
    }
}
