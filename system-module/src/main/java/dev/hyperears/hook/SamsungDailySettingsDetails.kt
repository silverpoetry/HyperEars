package dev.hyperears.hook

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.hyperears.integration.*
import java.lang.ref.WeakReference

@android.annotation.SuppressLint("SetTextI18n")
internal class SamsungDailySettingsDetails private constructor(
    anchor: View, private val address: String, private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val token = environment.sessionTokenProvider(address)
    private val context = anchor.context
    private val popup = PopupWindow(context)
    private val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var closed = false
    private var startedHere = false
    private val timer = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopRinging: Runnable = Runnable { guarded { stopOwnedRing() } }
    private fun stopOwnedRing() {
        if (!startedHere) return
        startedHere = false
        timer.removeCallbacks(stopRinging)
        val state = environment.stateProvider(address)
        if (valid(state)) environment.controlSender(address, SamsungDailyControlRequest.Find(false))
    }

    private var rendering = false
    private var unsubscribe: (() -> Unit)? = null
    private data class DraftSource(val model: String, val revision: Int, val preset: Int, val enabled: Boolean)
    private var draftSource: DraftSource? = null
    private var touchDetails: MiLinkToggleDetails? = null
    private var draft: MutableList<Int>? = null
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
            ModuleLog.warn("MiLinkUi", "Samsung settings callback failed: ${error.javaClass.simpleName}")
            dismiss()
        }
    }
    private fun valid(state: EarbudState) = token != null && token == environment.sessionTokenProvider(address) &&
        SamsungDailySettingsPolicy.available(state, address)
    private fun send(request: SamsungDailyControlRequest): Unit = guarded {
        if (closed || rendering) return@guarded
        val state = environment.stateProvider(address)
        if (!valid(state)) { dismiss(); return@guarded }
        SamsungDailySettingsPolicy.request(state, address, request)?.let {
            environment.controlSender(address, it)
            if (it is SamsungDailyControlRequest.Find) {
                startedHere = it.start
                timer.removeCallbacks(stopRinging)
                if (it.start) timer.postDelayed(stopRinging, 60_000L)
            }
        }
        render(environment.stateProvider(address))
    }
    override fun render(state: EarbudState): Unit = guarded {
        if (closed) return@guarded
        if (!valid(state)) { dismiss(); return@guarded }
        touchDetails?.render(state)
        rendering = true
        try {
            controls.removeAllViews()
            renderRows(state)
            renderCustom(state)
        } finally { rendering = false }
    }
    private fun choices(label: String, selected: Int?, labels: List<String>, request: (Int) -> SamsungDailyControlRequest) {
        controls.addView(text(label, 17f))
        controls.addView(RadioGroup(context).apply {
            labels.forEachIndexed { index, title -> addView(RadioButton(context).apply {
                id = View.generateViewId(); text = title; setTextColor(color(true)); isChecked = selected == index
                setOnClickListener { guarded { send(request(index)) } }
            }) }
        })
    }
    private fun action(label: String, callback: () -> Unit) {
        controls.addView(Button(context).apply { text = label; setOnClickListener { guarded { callback() } } })
    }
    private fun addOptionalSettings(state: EarbudState) {
        val rows: List<Pair<String,(Boolean) -> SamsungControlRequest>> = listOf(
            "单耳噪声控制" to SamsungControlRequest::SetNoiseControlsWithOneEarbud,
            "无缝连接" to SamsungControlRequest::SetSeamlessConnection,
            "通话侧音" to SamsungControlRequest::SetSidetone,
            "增强通透音量" to SamsungControlRequest::SetExtraHighAmbient,
        )
        rows.forEach { (label,request) ->
            val reported = SamsungDailySettingsPolicy.optionalValue(state,request(false)) ?: return@forEach
            controls.addView(Switch(context).apply {
                text=label; contentDescription=label; isSaveEnabled=false
                setTextColor(color(true)); isChecked=reported
                setOnCheckedChangeListener { _, enabled -> guarded {
                    if (closed || rendering) return@guarded
                    val current=environment.stateProvider(address)
                    if (!valid(current)) { dismiss(); return@guarded }
                    SamsungDailySettingsPolicy.request(current,address,request(enabled))?.let {
                        environment.controlSender(address,it)
                    }
                    // Re-render only authoritative reports; the click is not a confirmed value.
                    render(environment.stateProvider(address))
                } }
            })
        }
    }
    private fun renderRows(state: EarbudState) {
        val settings = state.features.get<SamsungDailySettingsFeatureState>() ?: return
        if (settings.equalizerEnabled != null && settings.equalizerPreset != null) {
            val selected = if (!settings.equalizerEnabled!!) 0 else if (settings.equalizerPreset!! <= 4) settings.equalizerPreset!! + 1 else null
            choices("音效（以耳机回报为准）", selected, listOf("正常", "低音增强", "柔和", "动感", "清晰", "高音增强")) {
                SamsungDailyControlRequest.SetEqualizer(it != 0, (it - 1).coerceAtLeast(0))
            }
        }
        settings.conversationTimeoutSeconds?.takeIf { settings.sourceModel == dev.hyperears.protocol.samsung.SamsungBudsCodec.Model.BUDS2_PRO }?.let {
            choices("语音检测结束等待时间",it/5-1,listOf("5 秒","10 秒","15 秒")) { index ->
                SamsungDailyControlRequest.SetConversationTimeout((index+1)*5)
            }
        }
        settings.ambientVolume?.let { volume -> choices("通透音量", volume,
            (0..settings.ambientMaximum).map { "级别 $it" }, SamsungDailyControlRequest::SetAmbientVolume) }
        settings.ancLevel?.let { choices("降噪强度", it, listOf("低", "高"), SamsungDailyControlRequest::SetAncLevel) }
        settings.customAmbient?.let { custom ->
            choices("个性化通透", if (custom.enabled) 1 else 0, listOf("关闭", "开启")) {
                SamsungDailyControlRequest.SetCustomAmbient(it == 1, custom.left, custom.right, custom.tone, custom)
            }
            choices("左耳通透", custom.left, (0..custom.maximum).map(Int::toString)) {
                SamsungDailyControlRequest.SetCustomAmbient(custom.enabled, it, custom.right, custom.tone, custom)
            }
            choices("右耳通透", custom.right, (0..custom.maximum).map(Int::toString)) {
                SamsungDailyControlRequest.SetCustomAmbient(custom.enabled, custom.left, it, custom.tone, custom)
            }
            choices("通透音色", custom.tone, (0..4).map { "音色 $it" }) {
                SamsungDailyControlRequest.SetCustomAmbient(custom.enabled, custom.left, custom.right, it, custom)
            }
        }
        settings.voiceFocus?.let { choices("通透人声聚焦", if (it) 1 else 0, listOf("关闭", "开启")) {
            SamsungDailyControlRequest.SetVoiceFocus(it == 1)
        } }
        settings.locked?.let { choices("触摸控制", if (it) 0 else 1, listOf("锁定", "开启")) {
            SamsungDailyControlRequest.SetLegacyLock(it == 0)
        } }
        if (settings.leftHold != null && settings.rightHold != null) {
            val original = settings.sourceModel.original
            val labels = if (original) listOf("语音助手", "快速通透", "音量", "通透", "Spotify")
                else listOf("语音助手", "通透", "音量", "Spotify")
            val offset = if (original) 0 else 1
            if (settings.sourceModel.ambientOnly) {
                choices("左耳长按", settings.leftHold!! - offset, labels) {
                    SamsungDailyControlRequest.SetLegacyHold(it + offset, settings.rightHold!!)
                }
                choices("右耳长按", settings.rightHold!! - offset, labels) {
                    SamsungDailyControlRequest.SetLegacyHold(settings.leftHold!!, it + offset)
                }
            }
        }
        addOptionalSettings(state)
        val existing = state.features.get<SamsungBudsSettingsFeatureState>()
        if (existing?.voiceDetectSupported == true) {
            controls.addView(Switch(context).apply {
                text = "语音检测"; setTextColor(color(true)); isChecked = existing.voiceDetectEnabled
                setOnCheckedChangeListener { _, enabled -> guarded {
                    if (rendering) return@guarded
                    val current = environment.stateProvider(address)
                    if (!valid(current)) { dismiss(); return@guarded }
                    if (current.features.get<SamsungBudsSettingsFeatureState>()?.voiceDetectSupported == true)
                        environment.controlSender(address, SamsungControlRequest.SetVoiceDetect(enabled))
                    render(environment.stateProvider(address))
                } }
            })
        }
        if (state.features.get<SamsungBudsSettingsFeatureState>() != null) action("触摸与长按设置") {
            val current = environment.stateProvider(address)
            if (!valid(current) || current.features.get<SamsungBudsSettingsFeatureState>() == null) { dismiss() }
            else { touchDetails?.dismiss(); anchor.get()?.let { touchDetails = SamsungTouchControlDetails.open(it, address, environment) } }
        }
        controls.addView(text("查找耳机：手动播放提示音，60 秒或关闭面板后停止。耳机不会回报正在响铃状态。", 14f))
        val intent = state.features.get<SamsungDailyIntentFeatureState>()
        if (intent?.findRequested == true) controls.addView(text(if (intent.findWritten) "响铃命令已发送，实际状态以耳机为准" else "响铃命令待发送", 14f))
        if (intent?.findRequested == false) controls.addView(text(if (intent.findWritten) "停止命令已发送，等待耳机回报" else "停止命令待发送", 14f))
        if (settings.findStopped == true && (intent?.findRequested != true || intent.findWritten)) controls.addView(text("已收到停止回报", 14f))
        action("开始响铃") { send(SamsungDailyControlRequest.Find(true)) }
        action("停止响铃") { send(SamsungDailyControlRequest.Find(false)) }
        action("仅左耳响铃") { send(SamsungDailyControlRequest.MuteFind(false, true)) }
        action("仅右耳响铃") { send(SamsungDailyControlRequest.MuteFind(true, false)) }
        action("双耳响铃") { send(SamsungDailyControlRequest.MuteFind(false, false)) }
        if (settings.findLeftMuted != null && settings.findRightMuted != null)
            controls.addView(text("耳机回报静音：左 ${settings.findLeftMuted} / 右 ${settings.findRightMuted}", 14f))
    }
    private fun renderCustom(state: EarbudState) {
        val settings = state.features.get<SamsungDailySettingsFeatureState>() ?: return
        if (!settings.customEqAvailable || settings.equalizerEnabled == null || settings.equalizerPreset == null) {
            draftSource = null; draft = null; return
        }
        val source = DraftSource(settings.model, settings.revision, requireNotNull(settings.equalizerPreset), requireNotNull(settings.equalizerEnabled))
        if (draftSource != source) { draftSource = source; draft = MutableList(9) { 0 } }
        controls.addView(text("自定义均衡器", 17f))
        controls.addView(text("耳机不能可靠回报曲线；下方是本次草稿，应用后不会自动重放。自定义模式是否启用以耳机回报为准。", 14f))
        state.features.get<SamsungDailyIntentFeatureState>()?.submittedCurve?.let {
            val sent = state.features.get<SamsungDailyIntentFeatureState>()?.curveWritten == true
            controls.addView(text("${if (sent) "上次已发送" else "待发送"}（非耳机确认）：${it.joinToString()} dB", 14f))
        }
        controls.addView(text("当前模式：${if (settings.equalizerEnabled!! && settings.equalizerPreset == 6) "自定义（已回报）" else "预设 / 正常"}", 14f))
        repeat(9) { index ->
            val caption = text("频段 ${index + 1}: ${draft!![index]} dB", 15f)
            controls.addView(caption)
            controls.addView(SeekBar(context).apply {
                min = 0; max = 20; progress = draft!![index] + 10; contentDescription = "EQ band ${index + 1}"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser && !rendering) guarded {
                            if (draftSource != source) return@guarded
                            if (!valid(environment.stateProvider(address))) { dismiss(); return@guarded }
                            draft?.set(index, (progress - 10).coerceIn(-10, 10))
                            caption.text = "频段 ${index + 1}: ${draft!![index]} dB"
                        }
                    }
                })
            })
        }
        action("应用自定义曲线") {
            if (draftSource == source) draft?.toList()?.let {
                send(SamsungDailyControlRequest.SetCustomEqualizer(it, settings.model, settings.revision,
                    settings.equalizerPreset!!, settings.equalizerEnabled!!))
            }
        }
    }
    override fun dismiss() {
        if (closed) return
        runCatching { stopOwnedRing() }
        timer.removeCallbacks(stopRinging)
        closed = true; draft = null; draftSource = null
        touchDetails?.dismiss(); touchDetails = null
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
            runCatching<MiLinkToggleDetails> { SamsungDailySettingsDetails(anchor, address, environment) }.getOrElse {
                ModuleLog.warn("MiLinkUi", "Samsung settings unavailable: ${it.javaClass.simpleName}")
                object : MiLinkToggleDetails { override fun render(state: EarbudState) = Unit; override fun dismiss() = Unit }
            }
    }
}
