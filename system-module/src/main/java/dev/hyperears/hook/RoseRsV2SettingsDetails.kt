package dev.hyperears.hook

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.hyperears.integration.*
import java.lang.ref.WeakReference

@android.annotation.SuppressLint("SetTextI18n")
internal class RoseRsV2SettingsDetails private constructor(
    anchor: View, private val address: String, private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val token = environment.sessionTokenProvider(address)
    private val context = anchor.context
    private val popup = PopupWindow(context)
    private val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var closed = false
    private var pendingAudio: Int? = null
    private var pendingAudioSource: Int? = null
    private var startedHere = false
    private val timer = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopRinging: Runnable = Runnable { guarded { stopOwnedRing() } }
    private fun stopOwnedRing() {
        if (!startedHere) return
        startedHere = false
        timer.removeCallbacks(stopRinging)
        val state = environment.stateProvider(address)
        if (valid(state)) environment.controlSender(address, RoseRsV2ControlRequest.Find(4))
    }

    private var rendering = false
    private var unsubscribe: (() -> Unit)? = null
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = dismiss()
    }
    init {
        try {
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
                background = GradientDrawable().apply { setColor(color(false)); cornerRadius = dp(20).toFloat() }
            }
            body.addView(text("耳机高级设置", 22f)); body.addView(controls)
            body.addView(Button(context).apply { text = "关闭"; setOnClickListener { dismiss() } })
            popup.contentView = ScrollView(context).apply { addView(body) }
            popup.width = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
            popup.height = (context.resources.displayMetrics.heightPixels * 0.82f).toInt()
            popup.isFocusable = true; popup.isOutsideTouchable = true
            popup.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            popup.setOnDismissListener { dismiss() }
            anchor.addOnAttachStateChangeListener(detach)
            val release = environment.observeState(address, ::render)
            if (closed) release() else unsubscribe = release
            render(environment.stateProvider(address))
            if (!anchor.isAttachedToWindow || anchor.windowToken == null) dismiss()
            anchor.post { guarded {
                render(environment.stateProvider(address))
                if (!closed && anchor.isAttachedToWindow && anchor.windowToken != null)
                    popup.showAtLocation(anchor.rootView, Gravity.CENTER, 0, 0)
                else dismiss()
            } }
        } catch (error: Exception) { dismiss(); throw error }
    }
    private inline fun guarded(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            ModuleLog.warn("MiLinkUi", "ROSE settings callback failed: ${error.javaClass.simpleName}")
            dismiss()
        }
    }
    private fun valid(state: EarbudState) = token != null && token == environment.sessionTokenProvider(address) &&
        RoseRsV2SettingsPolicy.available(state, address) && state.features.get<RoseRsV2SettingsFeatureState>()?.hasSettings == true
    private fun send(request: RoseRsV2ControlRequest): Unit = guarded {
        if (closed || rendering) return@guarded
        val state = environment.stateProvider(address)
        if (!valid(state)) { dismiss(); return@guarded }
        RoseRsV2SettingsPolicy.request(state, address, request)?.let {
            environment.controlSender(address, it)
            if (it is RoseRsV2ControlRequest.Find) {
                startedHere = it.target in 1..3
                timer.removeCallbacks(stopRinging)
                if (startedHere) timer.postDelayed(stopRinging, 60_000L)
            }
        }
        render(environment.stateProvider(address))
    }
    override fun render(state: EarbudState): Unit = guarded {
        if (closed) return@guarded
        if (!valid(state)) { dismiss(); return@guarded }
        rendering = true
        try {
            controls.removeAllViews()
            renderRows(state)
        } finally { rendering = false }
    }
    private fun choices(label: String, selected: Int?, labels: List<String>, enabled: Boolean = true, request: (Int) -> RoseRsV2ControlRequest) {
        controls.addView(text(label, 17f))
        controls.addView(RadioGroup(context).apply {
            labels.forEachIndexed { index, title -> addView(RadioButton(context).apply {
                id = View.generateViewId(); text = title; setTextColor(color(true)); isChecked = selected == index; isEnabled = enabled
                setOnClickListener { guarded { send(request(index)) } }
            }) }
        })
    }
    private fun action(label: String, callback: () -> Unit) {
        controls.addView(Button(context).apply { text = label; setOnClickListener { guarded { callback() } } })
    }
    private fun renderRows(state: EarbudState) {
        val settings = state.features.get<RoseRsV2SettingsFeatureState>() ?: return
        val profile = settings.profile ?: return
        controls.addView(text(profile.label, 19f))
        for(transparency in listOf(false,true)) {
            val values=if(transparency) profile.transparencyLevels else profile.ancLevels
            val selected=if(transparency) settings.transparencyLevel else settings.ancLevel
            if(selected in values) choices(if(transparency) "通透强度" else "降噪深度",values.indexOf(selected),values.map(RoseRsV2DailyLabels::noiseLevel),
                RoseRsV2SettingsPolicy.request(state,address,RoseRsV2ControlRequest.SetNoiseLevel(settings.model,transparency,values.first())) != null) {
                RoseRsV2ControlRequest.SetNoiseLevel(settings.model,transparency,values[it])
            }
        }
        if(profile.languageSupported) settings.language?.takeIf { it in 0..1 }?.let { value ->
            choices("耳机提示语言",value,listOf("中文","English")) { RoseRsV2ControlRequest.SetLanguage(settings.model,it) }
        }
        if(profile.wearDetectionSupported) settings.wearDetection?.let { value ->
            choices("佩戴检测",if(value)1 else 0,listOf("关闭","开启")) { RoseRsV2ControlRequest.SetWearDetection(settings.model,it==1) }
        }
        settings.eq?.let { selected ->
            val labels = profile.eqPresets.map { RoseRsV2DailyLabels.equalizer(profile,it) }
            choices("音效", profile.eqPresets.indexOf(selected), labels) { RoseRsV2ControlRequest.SetEqualizer(profile.eqPresets[it]) }
        }
        settings.bass?.let { selected -> choices("低音增强", profile.bassLevels.indexOf(selected),profile.bassLevels.map { "级别 $it" }) {
            RoseRsV2ControlRequest.SetBass(profile.bassLevels[it])
        } }
        if (profile.gainSupported || profile.hearingProtectionSupported) {
            settings.gain?.takeIf { profile.gainSupported && it in 0..2 }?.let { value ->
                choices("增益", value, RoseRsV2DailyLabels.gain) { RoseRsV2ControlRequest.SetGain(settings.model,it) }
            }
            settings.hearingProtection?.takeIf { profile.hearingProtectionSupported && it in 0..6 }?.let { value ->
                choices("听力保护", value, RoseRsV2DailyLabels.hearingProtection) { RoseRsV2ControlRequest.SetHearingProtection(settings.model,it) }
            }
        }
        settings.game?.let { choices("游戏模式",if(it)1 else 0,listOf("关闭","开启")) { RoseRsV2ControlRequest.SetGame(it==1) } }
        settings.multipoint?.let { choices("双设备连接",if(it)1 else 0,listOf("关闭","开启")) { RoseRsV2ControlRequest.SetMultipoint(it==1) } }
        settings.audio?.let { confirmed ->
            if (pendingAudioSource != confirmed) pendingAudio = null
            controls.addView(text("音频模式",17f))
            controls.addView(RadioGroup(context).apply {
                profile.audioModes.forEach { mode -> addView(RadioButton(context).apply {
                    id=View.generateViewId();text=listOf("AAC / SBC","LDAC","LHDC","LC3","aptX","aptX HD","aptX Adaptive")[mode]
                    setTextColor(color(true));isChecked=mode==confirmed
                    setOnClickListener { guarded {
                        val current=environment.stateProvider(address)
                        if(!valid(current)){dismiss();return@guarded}
                        pendingAudio=mode;pendingAudioSource=confirmed;render(current)
                    } }
                }) }
            })
            pendingAudio?.takeIf { it != confirmed }?.let { requested ->
                controls.addView(text("更改音频模式可能中断播放或需要重新连接。",14f))
                action("确认更改音频模式") {
                    val current=environment.stateProvider(address)
                    if(!valid(current)){dismiss()}
                    else if(current.features.get<RoseRsV2SettingsFeatureState>()?.audio==pendingAudioSource) {
                        pendingAudio=null;send(RoseRsV2ControlRequest.SetAudio(requested))
                    } else {pendingAudio=null;render(current)}
                }
                action("取消更改") {pendingAudio=null;render(environment.stateProvider(address))}
            }
        }
        settings.prompt?.let { choices("提示音音量",it-1,(1..5).map { "级别 $it" }) { RoseRsV2ControlRequest.SetPrompt(it+1) } }
        settings.touchEnabled?.let { choices("触摸控制",if(it)1 else 0,listOf("关闭","开启")) { RoseRsV2ControlRequest.SetTouchEnabled(it==1) } }
        settings.touch?.let { mappings ->
            val gestures=listOf("单击","双击","三击","长按")
            val labels=listOf("无操作","播放 / 暂停","上一曲","下一曲","增大音量","减小音量","游戏模式","语音助手","降噪切换").take(profile.touchActions.last+1)
            mappings.forEachIndexed { index,value -> choices("${if(index<4) "左耳" else "右耳"}${gestures[index%4]}",value,labels) {
                RoseRsV2ControlRequest.SetTouch(RoseRsV2SettingsFeatureState.SLOTS[index],it)
            } }
        }
        if(settings.findLeft!=null || settings.findRight!=null) {
            controls.addView(text("查找耳机：60 秒或关闭面板后停止；状态以耳机回报为准。",14f))
            controls.addView(text("响铃回报：左 ${settings.findLeft?.toString() ?: "未知"} / 右 ${settings.findRight?.toString() ?: "未知"}",14f))
            action("查找左耳") {send(RoseRsV2ControlRequest.Find(1))}
            action("查找右耳") {send(RoseRsV2ControlRequest.Find(2))}
            action("查找双耳") {send(RoseRsV2ControlRequest.Find(3))}
            action("停止查找") {send(RoseRsV2ControlRequest.Find(4))}
        }
    }
    override fun dismiss() {
        if (closed) return
        runCatching { stopOwnedRing() }
        timer.removeCallbacks(stopRinging)
        closed = true
        pendingAudio = null; pendingAudioSource = null
        anchor.get()?.removeOnAttachStateChangeListener(detach)
        val release = unsubscribe; unsubscribe = null
        runCatching { release?.invoke() }; runCatching { if (popup.isShowing) popup.dismiss() }
    }
    private fun text(value: String, size: Float) = TextView(context).apply { text = value; textSize = size; setTextColor(color(true)) }
    private fun color(text: Boolean): Int {
        val dark = context.resources.configuration.uiMode and 0x30 == 0x20
        return if (text) { if (dark) Color.WHITE else Color.BLACK }
        else if (dark) 0xff242424.toInt() else 0xfff5f5f5.toInt()
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    companion object {
        fun open(anchor: View, address: String, environment: MiLinkCardEnvironment): MiLinkToggleDetails =
            runCatching<MiLinkToggleDetails> { RoseRsV2SettingsDetails(anchor, address, environment) }.getOrElse {
                ModuleLog.warn("MiLinkUi", "ROSE settings unavailable: ${it.javaClass.simpleName}")
                object : MiLinkToggleDetails { override fun render(state: EarbudState) = Unit; override fun dismiss() = Unit }
            }
    }
}
