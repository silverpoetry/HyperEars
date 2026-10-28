package dev.hyperears.hook

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.hyperears.integration.*
import java.lang.ref.WeakReference

internal object RoseLegacySettingsPolicy {
    fun available(state: EarbudState, address: String) = state.address.equals(address, true) &&
        state.modelId == state.features.get<RoseLegacySettingsFeatureState>()?.profile?.id && state.sessionActive && state.privateChannelConnected &&
        state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE &&
        state.features.get<RoseLegacySettingsFeatureState>()?.hasSettings == true
    fun request(state: EarbudState, address: String, request: RoseLegacyControlRequest.SetValue): RoseLegacyControlRequest.SetValue? {
        if (!available(state, address)) return null
        val feature = state.features.get<RoseLegacySettingsFeatureState>() ?: return null
        return request.takeIf { feature.supports(it, state.noiseMode) &&
            feature.values[it.setting] != it.value }
    }
    fun request(state:EarbudState,address:String,request:RoseLegacyControlRequest.SetTouch):RoseLegacyControlRequest.SetTouch? =
        request.takeIf { available(state,address) && state.features.get<RoseLegacySettingsFeatureState>()?.supports(it)==true }
    fun touchTitle(slot:Int)= (if(slot<16)"左耳" else "右耳")+when(slot and 15){1->"单击";2->"双击";3->"三击";else->"长按"}
    fun touchCaption(model:String,action:Int)="当前：${touchLabel(model,action)}（耳机回报）"
    fun touchLabel(model:String,action:Int):String = if(model in setOf("EXPLORER","CERAMICS_X_7034")) when(action){
        0->"无功能";5->"音量增加";6->"音量降低";3->"上一曲";4->"下一曲";7->"播放 / 暂停";9->"切换降噪模式";2->"语音助手";else->"耳机功能 $action"
    }else when(action){
        0->"无功能";1->"播放 / 暂停";2->"上一曲";3->"下一曲"
        4->"音量增加";5->if(model=="BUDSFREE")"音量增加"else"音量降低";else->"音量降低"
    }
    fun title(setting:RoseLegacySetting)=when(setting){
        RoseLegacySetting.EQ->"音效";RoseLegacySetting.GAME->"游戏模式";RoseLegacySetting.AUDIO->"音频与双设备模式";RoseLegacySetting.TOUCH->"触控开关"
        RoseLegacySetting.PROMPT->"提示音音量";RoseLegacySetting.LANGUAGE->"提示音语言";RoseLegacySetting.WEAR->"佩戴检测";RoseLegacySetting.SPATIAL->"空间音效";RoseLegacySetting.ANC_LEVEL->"降噪强度";RoseLegacySetting.TRANSPARENCY_LEVEL->"通透强度"
    }
    fun labels(setting:RoseLegacySetting,feature:RoseLegacySettingsFeatureState):List<String> {
        val profile=feature.profile ?: return emptyList()
        return profile.allowed(setting).map { value->when(setting){
            RoseLegacySetting.EQ->if(profile==dev.hyperears.protocol.rose.RoseLegacyIdentityCodec.Model.AGATE) listOf("高保真","摇滚")[value]
                else if(profile==dev.hyperears.protocol.rose.RoseLegacyIdentityCodec.Model.AGATE_X) listOf("流行","摇滚")[value]
                else if(profile==dev.hyperears.protocol.rose.RoseLegacyIdentityCodec.Model.OPENFEEL) listOf("均衡","低音")[value] else listOf("高保真","流行","摇滚")[value]
            RoseLegacySetting.AUDIO->if(profile.f8) listOf("双设备连接","LDAC","AAC / SBC")[value] else listOf("AAC / SBC","LDAC","LHDC","LC3","双设备连接")[value]
            RoseLegacySetting.TOUCH->if(!profile.f8) {if(value==0)"开启"else"关闭"}else{if(value==0)"关闭"else"开启"}
            RoseLegacySetting.ANC_LEVEL,RoseLegacySetting.TRANSPARENCY_LEVEL->listOf("高","中","低")[value]
            RoseLegacySetting.LANGUAGE->if(value==0)"中文"else"English"
            RoseLegacySetting.PROMPT->"级别 $value"
            else->if(value==0)"关闭"else"开启"
        } }
    }

}

internal object RoseLegacyDetailsSpec : MiLinkToggleDetailsSpec {
    override val label = "高级设置"
    override fun render(state: EarbudState) = MiLinkToggleState(false,
        state.address?.let { RoseLegacySettingsPolicy.available(state, it) } == true,
        state.features.get<RoseLegacySettingsFeatureState>()?.hasSettings == true)
    override fun request(state: EarbudState, checked: Boolean): ControlRequest? = null
    override fun openDetails(anchor: View, address: String, environment: MiLinkCardEnvironment) =
        RoseLegacySettingsDetails.open(anchor, address, environment)
}

internal class RoseLegacySettingsDetails private constructor(
    anchor: View, private val address: String, private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val token = environment.sessionTokenProvider(address)
    private val model = environment.stateProvider(address).features.get<RoseLegacySettingsFeatureState>()?.model
    private val context = anchor.context
    private val popup = PopupWindow(context)
    private val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var closed = false
    private var pendingAudio: Pair<Int, Int>? = null
    private var unsubscribe: (() -> Unit)? = null
    private fun findSnapshot(state:EarbudState):RoseFindUiSnapshot? {
        if(!valid(state))return null
        val feature=state.features.get<RoseLegacySettingsFeatureState>()?:return null
        return RoseFindUiSnapshot(feature.model,feature.findAvailable,feature.profile?.findTargets().orEmpty(),feature.findIntent)
    }
    private val findDetails=RoseFindDetails(context,
        {findSnapshot(environment.stateProvider(address))},
        { expected,lease,target ->
            val current=environment.stateProvider(address)
            val request=RoseLegacyControlRequest.Find(expected,lease,target)
            if(valid(current) && current.features.get<RoseLegacySettingsFeatureState>()?.supports(request)==true)
                environment.controlSender(address,request)
        },
        {render(environment.stateProvider(address))},::text)

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
        RoseLegacySettingsPolicy.available(state, address) && state.features.get<RoseLegacySettingsFeatureState>()?.model == model
    private fun send(request: RoseLegacyControlRequest): Unit = guarded {
        val state = environment.stateProvider(address)
        if (!valid(state)) { dismiss(); return@guarded }
        val accepted=when(request){
            is RoseLegacyControlRequest.SetValue->RoseLegacySettingsPolicy.request(state,address,request)
            is RoseLegacyControlRequest.SetTouch->RoseLegacySettingsPolicy.request(state,address,request)
            is RoseLegacyControlRequest.Find->request.takeIf{state.features.get<RoseLegacySettingsFeatureState>()?.supports(it)==true}
        }
        accepted?.let { environment.controlSender(address, it) }
        render(environment.stateProvider(address))
    }
    override fun render(state: EarbudState): Unit = guarded {
        if (!valid(state)) { dismiss(); return@guarded }
        controls.removeAllViews()
        findSnapshot(state)?.let{findDetails.render(controls,it)}
        val values = state.features.get<RoseLegacySettingsFeatureState>()!!.values
        if (pendingAudio?.first != values[RoseLegacySetting.AUDIO]) pendingAudio = null
        for ((setting, selected) in values) {
            val feature = state.features.get<RoseLegacySettingsFeatureState>()!!
            if (!feature.supports(RoseLegacyControlRequest.SetValue(feature.model,setting, selected), state.noiseMode)) continue
            controls.addView(text(RoseLegacySettingsPolicy.title(setting)))
            controls.addView(RadioGroup(context).apply {
                RoseLegacySettingsPolicy.labels(setting,feature).forEachIndexed { index, label ->
                    val value = requireNotNull(feature.profile).allowed(setting)[index]
                    addView(RadioButton(context).apply {
                        id = View.generateViewId(); text = label; setTextColor(color(true)); isChecked = selected == value
                        setOnClickListener { guarded {
                            if (setting == RoseLegacySetting.AUDIO) {
                                val fresh = environment.stateProvider(address)
                                if (!valid(fresh)) { dismiss(); return@guarded }
                                if (fresh.features.get<RoseLegacySettingsFeatureState>()?.values?.get(setting) == selected) {
                                    pendingAudio = selected to value; render(fresh)
                                }
                            } else send(RoseLegacyControlRequest.SetValue(feature.model,setting, value))
                        } }
                    })
                }
            })
        }
        val feature=state.features.get<RoseLegacySettingsFeatureState>()!!
        feature.touch?.let { snapshot ->
            RoseLegacyTouch.slots(feature.profile).forEachIndexed { index,slot ->
                controls.addView(text(RoseLegacySettingsPolicy.touchTitle(slot)))
                if(RoseLegacyTouch.action(snapshot,index,feature.profile) !in RoseLegacyTouch.choices(feature.profile))
                    controls.addView(text(RoseLegacySettingsPolicy.touchCaption(feature.model,RoseLegacyTouch.action(snapshot,index,feature.profile))))
                controls.addView(RadioGroup(context).apply {
                    RoseLegacyTouch.choices(feature.profile).forEach { action ->
                        addView(RadioButton(context).apply {
                            id=View.generateViewId();text=RoseLegacySettingsPolicy.touchLabel(feature.model,action)
                            contentDescription="rose.legacy.touch.$slot.$action"
                            isSaveEnabled=false;isChecked=RoseLegacyTouch.action(snapshot,index,feature.profile)==action
                            setOnClickListener { send(RoseLegacyControlRequest.SetTouch(feature.model,snapshot,slot,action)) }
                        })
                    }
                })
            }
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
                    if (fresh.features.get<RoseLegacySettingsFeatureState>()?.values?.get(RoseLegacySetting.AUDIO) == draft.first)
                        send(RoseLegacyControlRequest.SetValue(requireNotNull(fresh.features.get<RoseLegacySettingsFeatureState>()).model,RoseLegacySetting.AUDIO, draft.second))
                } }
            })
        }
    }
    override fun dismiss() {
        if (closed) return
        findDetails.close()
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
            RoseLegacySettingsDetails(anchor, address, environment)
    }
}
