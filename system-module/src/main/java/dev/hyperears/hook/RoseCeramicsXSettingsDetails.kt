package dev.hyperears.hook

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.hyperears.integration.*
import java.lang.ref.WeakReference

internal object RoseCeramicsXSettingsPolicy {
    fun available(state: EarbudState, address: String) = state.address.equals(address, true) &&
        state.modelId == RoseLuliXAdapter.ID && state.sessionActive && state.privateChannelConnected &&
        state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE &&
        state.features.get<RoseCeramicsXSettingsFeatureState>()?.values?.isNotEmpty() == true
    fun request(state: EarbudState, address: String, request: RoseCeramicsXControlRequest.SetValue): RoseCeramicsXControlRequest.SetValue? {
        if (!available(state, address)) return null
        val feature = state.features.get<RoseCeramicsXSettingsFeatureState>() ?: return null
        return request.takeIf { feature.supports(it, state.features.get<NoiseModeFeatureState>()?.mode) &&
            feature.values[it.setting] != it.value }
    }
    fun title(setting: RoseCeramicsXSetting) = when(setting) {
        RoseCeramicsXSetting.EQ -> "音效"
        RoseCeramicsXSetting.GAME -> "游戏模式"
        RoseCeramicsXSetting.LANGUAGE -> "提示音语言"
        RoseCeramicsXSetting.AUDIO -> "音频与双设备模式"
        RoseCeramicsXSetting.ANC_LEVEL -> "降噪强度"
        RoseCeramicsXSetting.TRANSPARENCY_LEVEL -> "通透强度"
        RoseCeramicsXSetting.TOUCH -> "触控开关"
    }
    fun labels(setting: RoseCeramicsXSetting) = when(setting) {
        RoseCeramicsXSetting.EQ -> listOf("高保真", "流行", "摇滚")
        RoseCeramicsXSetting.AUDIO -> listOf("双设备连接", "LDAC", "AAC / SBC")
        RoseCeramicsXSetting.LANGUAGE -> listOf("中文", "English")
        RoseCeramicsXSetting.ANC_LEVEL, RoseCeramicsXSetting.TRANSPARENCY_LEVEL -> listOf("高", "中", "低")
        else -> listOf("关闭", "开启")
    }
}

internal object RoseCeramicsXDetailsSpec : MiLinkToggleDetailsSpec {
    override val label = "高级设置"
    override fun render(state: EarbudState) = MiLinkToggleState(false,
        state.address?.let { RoseCeramicsXSettingsPolicy.available(state, it) } == true,
        state.features.get<RoseCeramicsXSettingsFeatureState>()?.values?.isNotEmpty() == true)
    override fun request(state: EarbudState, checked: Boolean): ControlRequest? = null
    override fun openDetails(anchor: View, address: String, environment: MiLinkCardEnvironment) =
        RoseCeramicsXSettingsDetails.open(anchor, address, environment)
}

internal class RoseCeramicsXSettingsDetails private constructor(
    anchor: View, private val address: String, private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val token = environment.sessionTokenProvider(address)
    private val context = anchor.context
    private val popup = PopupWindow(context)
    private val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var closed = false
    private var pendingAudio: Pair<Int, Int>? = null
    private var unsubscribe: (() -> Unit)? = null
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = dismiss()
    }
    init {
        try {
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
                background = GradientDrawable().apply { setColor(color(false)); cornerRadius = 24f }
            }
            body.addView(text("耳机高级设置")); body.addView(controls)
            body.addView(Button(context).apply { text = "关闭"; setOnClickListener { dismiss() } })
            popup.contentView = ScrollView(context).apply { addView(body) }
            popup.width = (context.resources.displayMetrics.widthPixels * .92f).toInt()
            popup.height = (context.resources.displayMetrics.heightPixels * .82f).toInt()
            popup.isFocusable = true; popup.isOutsideTouchable = true
            popup.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            popup.setOnDismissListener { dismiss() }; anchor.addOnAttachStateChangeListener(detach)
            val release = environment.observeState(address, ::render)
            if (closed) release() else unsubscribe = release
            render(environment.stateProvider(address))
            if (!anchor.isAttachedToWindow || anchor.windowToken == null) dismiss()
            anchor.post { guarded {
                render(environment.stateProvider(address))
                if (!closed && anchor.isAttachedToWindow && anchor.windowToken != null)
                    popup.showAtLocation(anchor.rootView, Gravity.CENTER, 0, 0) else dismiss()
            } }
        } catch (error: Exception) { dismiss(); throw error }
    }
    private fun guarded(action: () -> Unit) { if (!closed) runCatching(action).onFailure { dismiss() } }
    private fun valid(state: EarbudState) = token != null && token == environment.sessionTokenProvider(address) &&
        RoseCeramicsXSettingsPolicy.available(state, address)
    private fun send(request: RoseCeramicsXControlRequest.SetValue): Unit = guarded {
        val state = environment.stateProvider(address)
        if (!valid(state)) { dismiss(); return@guarded }
        RoseCeramicsXSettingsPolicy.request(state, address, request)?.let { environment.controlSender(address, it) }
        render(environment.stateProvider(address))
    }
    override fun render(state: EarbudState): Unit = guarded {
        if (!valid(state)) { dismiss(); return@guarded }
        controls.removeAllViews()
        val values = state.features.get<RoseCeramicsXSettingsFeatureState>()!!.values
        if (pendingAudio?.first != values[RoseCeramicsXSetting.AUDIO]) pendingAudio = null
        for ((setting, selected) in values) {
            val feature = state.features.get<RoseCeramicsXSettingsFeatureState>()!!
            if (!feature.supports(RoseCeramicsXControlRequest.SetValue(setting, selected), state.features.get<NoiseModeFeatureState>()?.mode)) continue
            controls.addView(text(RoseCeramicsXSettingsPolicy.title(setting)))
            controls.addView(RadioGroup(context).apply {
                RoseCeramicsXSettingsPolicy.labels(setting).forEachIndexed { value, label ->
                    addView(RadioButton(context).apply {
                        id = View.generateViewId(); text = label; setTextColor(color(true)); isChecked = selected == value
                        setOnClickListener { guarded {
                            if (setting == RoseCeramicsXSetting.AUDIO) {
                                val fresh = environment.stateProvider(address)
                                if (!valid(fresh)) { dismiss(); return@guarded }
                                if (fresh.features.get<RoseCeramicsXSettingsFeatureState>()?.values?.get(setting) == selected) {
                                    pendingAudio = selected to value; render(fresh)
                                }
                            } else send(RoseCeramicsXControlRequest.SetValue(setting, value))
                        } }
                    })
                }
            })
        }
        pendingAudio?.takeIf { it.first != it.second }?.let { draft ->
            controls.addView(text("更改音频与双设备模式可能中断播放或需要重新连接。"))
            controls.addView(Button(context).apply {
                text = "确认更改音频模式"
                setOnClickListener { guarded {
                    if (pendingAudio != draft) return@guarded
                    pendingAudio = null
                    val fresh = environment.stateProvider(address)
                    if (!valid(fresh)) { dismiss(); return@guarded }
                    if (fresh.features.get<RoseCeramicsXSettingsFeatureState>()?.values?.get(RoseCeramicsXSetting.AUDIO) == draft.first)
                        send(RoseCeramicsXControlRequest.SetValue(RoseCeramicsXSetting.AUDIO, draft.second))
                } }
            })
        }
    }
    override fun dismiss() {
        if (closed) return
        closed = true; pendingAudio = null
        anchor.get()?.removeOnAttachStateChangeListener(detach)
        val release = unsubscribe; unsubscribe = null
        runCatching { release?.invoke() }; runCatching { if (popup.isShowing) popup.dismiss() }
    }
    private fun text(value: String) = TextView(context).apply { text = value; textSize = 17f; setTextColor(color(true)) }
    private fun color(text: Boolean): Int {
        val dark = context.resources.configuration.uiMode and 0x30 == 0x20
        return if (text) { if (dark) Color.WHITE else Color.BLACK } else if (dark) 0xff242424.toInt() else 0xfff5f5f5.toInt()
    }
    companion object {
        fun open(anchor: View, address: String, environment: MiLinkCardEnvironment): MiLinkToggleDetails =
            RoseCeramicsXSettingsDetails(anchor, address, environment)
    }
}
