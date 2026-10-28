package dev.hyperears.hook

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.hyperears.integration.*
import java.lang.ref.WeakReference

internal object RoseZtLabels {
    fun title(setting: String) = when(setting) {
        "EQ" -> "音效"; "GAME" -> "游戏模式"; "AUDIO" -> "音频模式"
        "MULTIPOINT" -> "双设备连接"; "GAIN" -> "增益"; "PROMPT" -> "提示音音量"; "WEAR_DETECTION" -> "佩戴检测"; else -> setting
    }
    fun labels(setting: String, model: dev.hyperears.protocol.rose.RoseZtWireCodec.Model) = when(setting) {
        "EQ" -> if(model==dev.hyperears.protocol.rose.RoseZtWireCodec.Model.I5_SOURCE) listOf("弱水经典","日系柔情","乐器大师","清新空灵") else listOf("流行", "高保真", "摇滚")
        "GAME" -> listOf("开启", "关闭")
        "AUDIO" -> if (model != dev.hyperears.protocol.rose.RoseZtWireCodec.Model.HEADFEEL)
            listOf("AAC / SBC · 双设备", "LDAC") else listOf("AAC / SBC", "LDAC", "LHDC", "LDAC / LHDC")
        "MULTIPOINT" -> listOf("关闭", "开启")
        "GAIN" -> listOf("档位 1", "档位 2", "档位 3")
        "PROMPT" -> (1..5).map { "级别 $it" }
        "WEAR_DETECTION" -> listOf("开启","关闭")
        else -> emptyList()
    }
}

internal class RoseZtSettingsDetails private constructor(
    anchor: View, private val address: String, private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val token = environment.sessionTokenProvider(address)
    private val context = anchor.context
    private val popup = PopupWindow(context)
    private val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var closed = false
    private data class Draft(val model: String, val setting: String, val source: Int, val target: Int)
    private var pendingAudio: Draft? = null
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
        RoseZtSettingsPolicy.available(state, address) && state.features.get<RoseZtSettingsFeatureState>()?.hasSettings == true
    private fun send(request: RoseZtControlRequest.SetValue): Unit = guarded {
        val state = environment.stateProvider(address)
        if (!valid(state)) { dismiss(); return@guarded }
        RoseZtSettingsPolicy.request(state, address, request)?.let { environment.controlSender(address, it) }
        render(environment.stateProvider(address))
    }
    override fun render(state: EarbudState): Unit = guarded {
        if (!valid(state)) { dismiss(); return@guarded }
        controls.removeAllViews()
        val settings = state.features.get<RoseZtSettingsFeatureState>()!!
        val values = settings.values
        val profile = settings.profile ?: return@guarded
        pendingAudio?.let { if (it.model != settings.model || values[it.setting] != it.source) pendingAudio = null }
        for ((setting, selected) in values) {
            controls.addView(text(RoseZtLabels.title(setting)))
            controls.addView(RadioGroup(context).apply {
                RoseZtLabels.labels(setting, profile).forEachIndexed { index, label ->
                    val value = dev.hyperears.protocol.rose.RoseZtWireCodec.Setting.valueOf(setting).values(profile).first + index
                    addView(RadioButton(context).apply {
                        id = View.generateViewId(); text = label; setTextColor(color(true)); isChecked = selected == value
                        setOnClickListener { guarded {
                            if (setting in setOf("AUDIO", "MULTIPOINT")) {
                                val fresh = environment.stateProvider(address)
                                if (!valid(fresh)) { dismiss(); return@guarded }
                                if (fresh.features.get<RoseZtSettingsFeatureState>()?.values?.get(setting) == selected) {
                                    pendingAudio = Draft(settings.model, setting, selected, value); render(fresh)
                                }
                            } else send(RoseZtControlRequest.SetValue(settings.model, setting, value))
                        } }
                    })
                }
            })
        }
        pendingAudio?.takeIf { it.source != it.target }?.let { draft ->
            controls.addView(text("更改音频与双设备模式可能中断播放或需要重新连接。"))
            controls.addView(Button(context).apply {
                text = "确认更改音频模式"
                setOnClickListener { guarded {
                    if (pendingAudio != draft) return@guarded
                    pendingAudio = null
                    val fresh = environment.stateProvider(address)
                    if (!valid(fresh)) { dismiss(); return@guarded }
                    if (fresh.features.get<RoseZtSettingsFeatureState>()?.let { it.model == draft.model && it.values[draft.setting] == draft.source } == true)
                        send(RoseZtControlRequest.SetValue(draft.model, draft.setting, draft.target))
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
            RoseZtSettingsDetails(anchor, address, environment)
    }
}
