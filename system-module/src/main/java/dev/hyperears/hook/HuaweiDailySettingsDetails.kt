package dev.hyperears.hook

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.hyperears.integration.*
import java.lang.ref.WeakReference
import java.util.Locale

@android.annotation.SuppressLint("SetTextI18n")
internal class HuaweiDailySettingsDetails private constructor(
    anchor: View, private val address: String, private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val token = environment.sessionTokenProvider(address)
    private val context = anchor.context
    private val popup = PopupWindow(context)
    private val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var pendingDevice:HuaweiDailyControlRequest.ManageDevice?=null
    private var closed = false
    private var rendering = false
    private var unsubscribe: (() -> Unit)? = null
    private var draftSource: HuaweiCustomEqualizer? = null
    private var draft: MutableList<Int>? = null
    private var eqDraftSnapshot:HuaweiEqSnapshot?=null
    private var creatingEq=false
    private var draftName=""
    private var pendingEqDelete:HuaweiDailyControlRequest.ManageEqualizer?=null
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
            ModuleLog.warn("MiLinkUi", "Huawei settings callback failed: ${error.javaClass.simpleName}")
            dismiss()
        }
    }
    private fun valid(state: EarbudState) = token != null && token == environment.sessionTokenProvider(address) &&
        HuaweiDailySettingsPolicy.available(state, address)
    private fun send(request: HuaweiDailyControlRequest): Unit = guarded {
        if (closed || rendering) return@guarded
        val state = environment.stateProvider(address)
        if (!valid(state)) { dismiss(); return@guarded }
        HuaweiDailySettingsPolicy.request(state, address, request)?.let { environment.controlSender(address, it) }
        render(environment.stateProvider(address))
    }
    override fun render(state: EarbudState): Unit = guarded {
        if (closed) return@guarded
        if (!valid(state)) { dismiss(); return@guarded }
        rendering = true
        try {
            controls.removeAllViews()
            HuaweiDailyChoices.rows(state).forEach { row ->
                controls.addView(text(if (row.currentLabel != null && row.choices.none { it.value == row.selected })
                    "${row.label}（耳机当前值：${row.currentLabel}）"
                    else if (row.choices.isEmpty()) "${row.label}（耳机当前值：${row.selected}）" else row.label, 17f))
                val group = RadioGroup(context)
                row.choices.forEach { choice -> group.addView(RadioButton(context).apply {
                    id = View.generateViewId(); text = choice.label; setTextColor(color(true)); isSaveEnabled = false
                    isChecked = row.selected == choice.value
                    setOnClickListener { send(choice.request) }
                }) }
                controls.addView(group)
            }
            renderPaired(state)
            renderAncLevels(state)
            renderCustom(state)
        } finally { rendering = false }
    }
    private fun renderPaired(state:EarbudState) {
        val snapshot=state.features.get<HuaweiDailySettingsFeatureState>()?.pairedDevices
        if(pendingDevice?.expected!=snapshot)pendingDevice=null
        if(snapshot==null)return
        snapshot.devices.forEach { device ->
            controls.addView(text(device.name.ifBlank{device.mac},17f))
            controls.addView(text(HuaweiPairedLabels.status(device),14f))
            HuaweiPairedAction.entries.filter{snapshot.supports(device.mac,it)}.forEach { action ->
                val request=HuaweiDailyControlRequest.ManageDevice(snapshot,device.mac,action)
                controls.addView(Button(context).apply {
                    text=HuaweiPairedLabels.action(action);contentDescription="huawei.device.${device.mac}.${action.name}"
                    setOnClickListener { guarded {
                        val fresh=environment.stateProvider(address)
                        if(!valid(fresh)){dismiss();return@guarded}
                        if(HuaweiDailySettingsPolicy.request(fresh,address,request)==null)return@guarded
                        if(HuaweiPairedLabels.destructive(action)){pendingDevice=request;render(fresh)}else send(request)
                    } }
                })
            }
        }
        pendingDevice?.let { request ->
            controls.addView(text("确认${HuaweiPairedLabels.action(request.action)}？",16f))
            controls.addView(Button(context).apply {
                text="确认";contentDescription="huawei.device.confirm"
                setOnClickListener {guarded{if(pendingDevice!=request)return@guarded;pendingDevice=null;send(request)}}
            })
            controls.addView(Button(context).apply {text="取消";setOnClickListener{pendingDevice=null;render(environment.stateProvider(address))}})
        }
    }
    private fun renderAncLevels(state: EarbudState) {
        val level = state.features.get<HuaweiAncLevelFeatureState>() ?: return
        if (!level.hasCurrentControls(state.noiseMode)) return
        val options = level.supported.filter { it.domainNoiseMode() == state.noiseMode }
        if (options.isEmpty()) return
        controls.addView(text("噪声强度", 17f))
        val group = RadioGroup(context)
        options.forEach { value -> group.addView(RadioButton(context).apply {
            id = View.generateViewId(); text = when (value) {
                HuaweiAncLevel.NORMAL -> "均衡"; HuaweiAncLevel.COMFORT -> "轻度"; HuaweiAncLevel.ULTRA -> "深度"
                HuaweiAncLevel.DYNAMIC -> "动态"; HuaweiAncLevel.VOICE_BOOST -> "人声增强"; HuaweiAncLevel.TRANS_NORMAL -> "标准通透"
            }; setTextColor(color(true)); isChecked = level.current == value
            setOnClickListener { guarded {
                val current = environment.stateProvider(address)
                if (!valid(current)) { dismiss(); return@guarded }
                val currentLevel = current.features.get<HuaweiAncLevelFeatureState>()
                if (currentLevel?.hasCurrentControls(current.noiseMode) == true &&
                    value in currentLevel.supported && value.domainNoiseMode() == current.noiseMode)
                    environment.controlSender(address, HuaweiControlRequest.SetAncLevel(value))
                render(environment.stateProvider(address))
            } }
        }) }
        controls.addView(group)
    }
    private fun renderCustom(state: EarbudState) {
        val feature=state.features.get<HuaweiDailySettingsFeatureState>()?:return
        val eq = feature.equalizer
        val inventory=feature.eqInventory
        if(feature.eqManaged) {
            if(eqDraftSnapshot!=inventory) {
                eqDraftSnapshot=inventory;creatingEq=false;draftSource=null;draft=null;pendingEqDelete=null
            }
            if(feature.eqPending || inventory==null) {
                controls.addView(text(if(feature.eqPending)"等待耳机确认音效变更" else "等待完整音效列表",15f))
                controls.addView(Button(context).apply {text="重新读取音效";setOnClickListener{guarded {
                    if(valid(environment.stateProvider(address)))environment.controlSender(address,StandardControlRequest.Refresh)
                }}})
                return
            }
            if(!creatingEq && inventory.nextId!=null)controls.addView(Button(context).apply {
                text="新建自定义音效";contentDescription="huawei.eq.create"
                setOnClickListener {guarded {
                    val fresh=environment.stateProvider(address)
                    if(!valid(fresh) || fresh.features.get<HuaweiDailySettingsFeatureState>()?.eqInventory!=inventory)return@guarded
                    creatingEq=true;draftSource=null;draft=null;draftName="自定义音效"
                    render(fresh)
                }}
            })
            if(!creatingEq && inventory.slots.any{it.id==inventory.selected})controls.addView(Button(context).apply {
                text="删除当前自定义音效";contentDescription="huawei.eq.delete"
                setOnClickListener{guarded {
                    pendingEqDelete=HuaweiDailyControlRequest.ManageEqualizer(inventory,HuaweiEqAction.DELETE,inventory.selected)
                    render(environment.stateProvider(address))
                }}
            })
            pendingEqDelete?.let { request ->
                controls.addView(text("确认删除当前自定义音效？",16f))
                controls.addView(Button(context).apply {text="确认删除";contentDescription="huawei.eq.delete.confirm"
                    setOnClickListener{guarded{if(pendingEqDelete!=request)return@guarded;pendingEqDelete=null;send(request)}}})
                controls.addView(Button(context).apply {text="取消";setOnClickListener{guarded{pendingEqDelete=null;render(environment.stateProvider(address))}}})
            }
        }
        val original = if(feature.eqManaged && creatingEq && inventory?.nextId!=null)
            HuaweiCustomEqualizer(inventory.nextId!!,"",List(10){0})
            else eq?.custom?.singleOrNull { it.id == eq.selected }?.takeIf { eq.customWritable }
        if (original == null) { draftSource = null; draft = null; return }
        if (draftSource != original) { draftSource = original; draft = original.gains.toMutableList() }
        val nameInput=if(feature.eqManaged && creatingEq)EditText(context).apply {
            hint="音效名称（最多 24 字节）";contentDescription="huawei.eq.name";isSingleLine=true;setText(draftName)
            addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){draftName=s?.toString().orEmpty()}
                override fun afterTextChanged(s:android.text.Editable?)=Unit
            })
            controls.addView(this)
        } else null
        controls.addView(text("自定义音效：${original.name}", 17f))
        controls.addView(text("调整后点击保存；选择状态以耳机回读为准。", 13f))
        repeat(10) { index ->
            val caption = text(gainLabel(index, draft!![index]), 15f)
            controls.addView(caption)
            controls.addView(SeekBar(context).apply {
                min = 0; max = 120; progress = draft!![index] + 60; contentDescription = "EQ band ${index + 1}"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser && !rendering) guarded {
                            if (draftSource != original) return@guarded
                            if (!valid(environment.stateProvider(address))) { dismiss(); return@guarded }
                            draft?.set(index, (progress - 60).coerceIn(-60, 60))
                            caption.text = gainLabel(index, draft!![index])
                        }
                    }
                })
            })
        }
        controls.addView(Button(context).apply { text = "保存自定义音效"; setOnClickListener { guarded {
            if (draftSource != original) return@guarded
            val gains = draft?.toList() ?: return@guarded
            if(feature.eqManaged && inventory!=null) {
                if(creatingEq && !dev.hyperears.protocol.huawei.HuaweiEqualizerInventoryCodec.validName(draftName)) {
                    nameInput?.error="名称不能为空，且 UTF-8 长度不能超过 24 字节";return@guarded
                }
                send(HuaweiDailyControlRequest.ManageEqualizer(inventory,
                    if(creatingEq)HuaweiEqAction.CREATE else HuaweiEqAction.UPDATE,original.id,
                    name=if(creatingEq)draftName else "",gains=gains))
            } else send(HuaweiDailyControlRequest.SetCustomEqualizer(original.id, gains, original))
        } } })
    }
    private fun gainLabel(index: Int, gain: Int) = "频段 ${index + 1}: " + String.format(Locale.ROOT, "%.1f dB", gain / 10.0)
    override fun dismiss() {
        if (closed) return
        closed = true; draft = null; draftSource = null; eqDraftSnapshot=null;pendingEqDelete=null;creatingEq=false
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
            runCatching<MiLinkToggleDetails> { HuaweiDailySettingsDetails(anchor, address, environment) }.getOrElse {
                ModuleLog.warn("MiLinkUi", "Huawei settings unavailable: ${it.javaClass.simpleName}")
                object : MiLinkToggleDetails { override fun render(state: EarbudState) = Unit; override fun dismiss() = Unit }
            }
    }
}
