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
internal class OppoSettingsDetails private constructor(
    anchor: View, private val address: String, private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val context = anchor.context
    private val token = environment.sessionTokenProvider(address)
    private val popup = PopupWindow(context)
    private val handler = Handler(Looper.getMainLooper())
    private val renders = mutableListOf<(OppoSettingsFeatureState) -> Unit>()
    private var unsubscribe: (() -> Unit)? = null
    private var closed = false
    private var rendering = false
    private var pending: OppoControlRequest? = null
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
        token == environment.sessionTokenProvider(address) && OppoCardPolicy.available(state, address)
    override fun render(state: EarbudState) {
        if (closed) return
        try { renderState(state) } catch (_: Exception) { dismiss() }
    }
    private fun renderState(state: EarbudState) {
        if (closed) return
        if (!valid(state)) { dismiss(); return }
        val settings = state.features.get<OppoSettingsFeatureState>() ?: return
        pending?.let {
            if (OppoCardPolicy.matches(settings, it)) {
                pending = null; handler.removeCallbacks(timeout); status.text = "耳机已确认设置。"
            }
        }
        rendering = true
        try { renders.forEach { it(settings) } } finally { rendering = false }
    }
    private fun send(request: OppoControlRequest) {
        if (closed || rendering) return
        try {
            val state = environment.stateProvider(address)
            if (!valid(state)) { dismiss(); return }
            val accepted = OppoCardPolicy.request(state, address, request) ?: run { render(state); return }
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
        body.addView(text("耳机高级设置", 22f))
        body.addView(text("关闭", 16f).apply { setOnClickListener { dismiss() } })
        body.addView(status)
        val controls = column()
        val labels = mapOf(OppoToggle.AUTO_PLAY_PAUSE to "佩戴自动播放／暂停",
            OppoToggle.LOW_LATENCY to "低延迟模式", OppoToggle.MULTIPOINT to "双设备连接",
            OppoToggle.GAME_MODE to "游戏模式")
        OppoToggle.entries.forEach { setting ->
            val label = labels.getValue(setting)
            val row = column()
            row.addView(text(label, 17f))
            val toggle = createToggle().apply {
                contentDescription = label
                isSaveEnabled = false
                setOnCheckedChangeListener { _, enabled -> send(OppoControlRequest.SetToggle(setting, enabled)) }
            }
            row.addView(toggle); controls.addView(row)
            renders += { state ->
                val value = state.toggles[setting]
                row.visibility = if (value == null) View.GONE else View.VISIBLE
                toggle.isEnabled = value != null
                if (value != null) toggle.isChecked = value
            }
        }
        addPromptAndSpatial(controls)
        addNoiseOptions(controls)
        addEqualizers(controls)
        body.addView(ScrollView(context).apply { addView(controls) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return body
    }
    private fun addPromptAndSpatial(parent: LinearLayout) {
        val volumeRow=column();val caption=text("提示音音量",17f)
        val slider=SeekBar(context).apply { max=9;contentDescription="提示音音量";isSaveEnabled=false }
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean) {
                if(fromUser) caption.text=String.format(Locale.getDefault(),"提示音音量：%d（待确认）",value+1)
            }
            override fun onStartTrackingTouch(bar:SeekBar)=Unit
            override fun onStopTrackingTouch(bar:SeekBar) { send(OppoControlRequest.SetPromptVolume(bar.progress+1)) }
        })
        volumeRow.addView(caption);volumeRow.addView(slider);parent.addView(volumeRow)
        val spatialRow=column();spatialRow.addView(text("空间音效",17f))
        var shownProduct:Int?=null
        val spatial=createToggle().apply {
            contentDescription="空间音效";isSaveEnabled=false
            setOnCheckedChangeListener { _,enabled -> shownProduct?.let { send(OppoControlRequest.SetSpatialSound(enabled,it)) } }
        }
        spatialRow.addView(spatial);parent.addView(spatialRow)
        renders += { state ->
            volumeRow.visibility=if(state.promptVolume==null) View.GONE else View.VISIBLE
            slider.isEnabled=state.promptVolume!=null
            state.promptVolume?.let { slider.progress=it-1;caption.text=String.format(Locale.getDefault(),"提示音音量：%d",it) }
            spatialRow.visibility=if(state.spatialSound==null) View.GONE else View.VISIBLE
            shownProduct=state.spatialProductId
            spatial.isEnabled=state.spatialSound!=null
            spatial.isChecked=state.spatialSound==true
        }
    }
    private fun addNoiseOptions(parent: LinearLayout) {
        val row = column(); val title = text("降噪与通透",17f); val entries = column()
        row.addView(title); row.addView(entries); parent.addView(row)
        var shown: OppoNoiseDefinition? = null
        val choices = mutableListOf<Pair<TextView,OppoNoiseOption>>()
        renders += { state ->
            val noise = state.noise
            row.visibility = if (noise == null) View.GONE else View.VISIBLE
            if (shown != noise?.definition) {
                shown = noise?.definition; entries.removeAllViews(); choices.clear()
                noise?.definition?.let { definition ->
                    definition.options.filter { it.writable }.forEach { option ->
                        val view = text(option.mode.title,16f).apply {
                            contentDescription = "降噪与通透 " + option.mode.title
                            setOnClickListener { send(OppoControlRequest.SelectNoiseOption(definition,option.index)) }
                        }
                        entries.addView(view); choices += view to option
                    }
                }
            }
            title.text = noise?.selected?.mode?.title?.let { String.format(Locale.getDefault(),"当前模式：%s",it) } ?: "降噪与通透"
            choices.forEach { (view,option) ->
                view.isEnabled = noise != null
                view.isSelected = noise?.selected?.mode == option.mode
                view.text = String.format(Locale.getDefault(),"%s%s",if (view.isSelected) "✓ " else "",option.mode.title)
            }
        }
    }
    private fun addEqualizers(parent: LinearLayout) {
        val row = column()
        val current = text("", 17f)
        val entries = column()
        row.addView(current); row.addView(entries); parent.addView(row)
        var shape: List<OppoEqualizer>? = null
        var editable = emptySet<Int>()
        val entryRenders = mutableListOf<(OppoSettingsFeatureState) -> Unit>()
        renders += { state ->
            row.visibility = if (state.currentEqualizer == null && state.equalizers == null) View.GONE else View.VISIBLE
            current.text = state.currentEqualizer?.let { id ->
                String.format(Locale.getDefault(), "当前均衡器：%s",
                    state.equalizers?.firstOrNull { it.id == id }?.name?.takeIf(String::isNotBlank) ?: id.toString())
            } ?: "选择耳机均衡器"
            // Preserve unsent drafts when only gains/selection change; rebuild after identity/range changes.
            val nextShape = state.equalizers?.map { it.copy(gains = emptyList()) }
            if (shape != nextShape || editable != state.editableEqualizers) {
                shape = nextShape; editable = state.editableEqualizers
                entries.removeAllViews(); entryRenders.clear()
                state.equalizers.orEmpty().forEach { eq ->
                    val label = eq.name.ifBlank { "预设 ${eq.id}" }
                    val choice = text(label, 17f).apply {
                        contentDescription = "选择均衡器 ${eq.id}"
                        setOnClickListener { send(OppoControlRequest.SelectEqualizer(eq.id)) }
                    }
                    entries.addView(choice)
                    entryRenders += { latest -> choice.text =
                        String.format(Locale.getDefault(), "%s%s", if (latest.currentEqualizer == eq.id) "✓ " else "", label) }
                    if (eq.id in editable) {
                        val draft = eq.gains.toMutableList()
                        val sliders = mutableListOf<SeekBar>()
                        val captions = mutableListOf<TextView>()
                        val report = text("", 14f); entries.addView(report)
                        eq.frequencies.forEachIndexed { index, frequency ->
                            val caption = text("$frequency Hz：${draft[index]} dB（草稿）", 16f)
                            captions += caption; entries.addView(caption)
                            val slider = SeekBar(context).apply {
                                max = eq.maximum - eq.minimum; progress = draft[index] - eq.minimum
                                contentDescription = "均衡器 ${eq.id} $frequency Hz 增益"
                                isSaveEnabled = false
                                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                                        if (fromUser) {
                                            draft[index] = value + eq.minimum
                                            caption.text = String.format(Locale.getDefault(), "%d Hz：%d dB（草稿）", frequency, draft[index])
                                        }
                                    }
                                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                                })
                            }
                            sliders += slider; entries.addView(slider)
                        }
                        entries.addView(text("应用均衡器草稿", 17f).apply {
                            contentDescription = "应用均衡器 ${eq.id} 草稿"
                            setOnClickListener { send(OppoControlRequest.UpdateEqualizer(eq.id, draft.toList(), eq.definition())) }
                        })
                        entries.addView(text("从耳机回报重新载入", 16f).apply {
                            setOnClickListener {
                                runCatching {
                                val latest = environment.stateProvider(address)
                                if (!valid(latest)) { dismiss(); return@setOnClickListener }
                                val reported = latest.features.get<OppoSettingsFeatureState>()?.equalizers?.firstOrNull { it.id == eq.id }
                                if (reported != null && reported.frequencies == eq.frequencies &&
                                    reported.minimum == eq.minimum && reported.maximum == eq.maximum) {
                                    reported.gains.forEachIndexed { index, gain ->
                                        draft[index] = gain; sliders[index].progress = gain - eq.minimum
                                        captions[index].text = String.format(Locale.getDefault(), "%d Hz：%d dB（草稿）", eq.frequencies[index], gain)
                                    }
                                }
                                }.onFailure { dismiss() }
                            }
                        })
                        entryRenders += { latest ->
                            report.text = String.format(Locale.getDefault(), "耳机回报增益：%s dB",
                                latest.equalizers?.firstOrNull { it.id == eq.id }?.gains.orEmpty().joinToString(", "))
                        }
                    }
                }
            }
            entryRenders.forEach { it(state) }
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
            try { OppoSettingsDetails(anchor, address, environment) } catch (error: Exception) {
                ModuleLog.warn("MiLinkUi", "OPPO settings unavailable (${error.javaClass.simpleName})")
                ClosedDetails
            }
        private object ClosedDetails : MiLinkToggleDetails {
            override fun render(state: EarbudState) = Unit
            override fun dismiss() = Unit
        }
    }
}
