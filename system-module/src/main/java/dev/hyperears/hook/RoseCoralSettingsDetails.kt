package dev.hyperears.hook

import java.util.Locale
import androidx.core.view.isVisible

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.hyperears.integration.*
import java.lang.ref.WeakReference

/** Second-level panel hosted entirely by MiLink, with accepted reports as the only UI truth. */
internal class RoseCoralSettingsDetails private constructor(
    anchor: View,
    private val address: String,
    private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val token = environment.sessionTokenProvider(address)
    private val context = anchor.context
    private val rows = mutableListOf<Row>()
    private val extraRows = mutableListOf<(EarbudState) -> Unit>()
    private var closed = false
    private var rendering = false
    private var unsubscribe: (() -> Unit)? = null
    private val popup = PopupWindow(context)
    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = dismiss()
    }

    init {
        try {
            popup.contentView = buildContent()
            popup.width = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
            popup.height = (context.resources.displayMetrics.heightPixels * 0.82f).toInt()
            popup.isFocusable = true
            popup.isOutsideTouchable = true
            popup.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            popup.setOnDismissListener { dismiss() }
            anchor.addOnAttachStateChangeListener(detachListener)
            // Accommodate a provider that delivers an initial callback synchronously.
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
        } catch (error: Exception) {
            dismiss()
            throw error
        }
    }

    private fun valid(state: EarbudState): Boolean = token != null &&
        environment.sessionTokenProvider(address) == token && RoseCoralCardPolicy.available(state, address)

    override fun render(state: EarbudState) {
        if (closed) return
        if (!valid(state)) { dismiss(); return }
        val feature = state.features.get<RoseCoralSettingsFeatureState>()
        rendering = true
        try {
            rows.forEach { row ->
                val value = feature?.let(row.setting::value)
                row.container.visibility = if (value == null) View.GONE else View.VISIBLE
                if (value != null) row.render(value, true)
            }
            extraRows.forEach { it(state) }
        } finally { rendering = false }
    }

    private fun send(setting: RoseCoralSetting, value: Int) = sendRequest {
        RoseCoralCardPolicy.request(it, address, setting, value)
    }

    override fun dismiss() {
        if (closed) return
        closed = true
        anchor.get()?.removeOnAttachStateChangeListener(detachListener)
        val release = unsubscribe
        unsubscribe = null
        try { release?.invoke() } finally { if (popup.isShowing) popup.dismiss() }
    }

    private fun buildContent(): View {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(20))
            background = GradientDrawable().apply { setColor(color(false)); cornerRadius = dp(24).toFloat() }
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("耳机高级设置", 22f), LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(text("关闭", 16f).apply { setOnClickListener { dismiss() } }, LinearLayout.LayoutParams(dp(64), dp(56)))
        body.addView(header)
        val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addInfoAndAudio(controls)
        RoseCoralSetting.entries.forEach { setting ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                visibility = View.GONE
            }
            val caption = text(setting.label, 17f)
            row.addView(caption)
            val render: (Int, Boolean) -> Unit
            if (setting.isToggle) {
                val toggle = createToggle().apply {
                    contentDescription = setting.label
                    isSaveEnabled = false
                    setOnCheckedChangeListener { _, checked -> send(setting, if (checked) 1 else 0) }
                }
                row.addView(toggle)
                render = { value, enabled -> toggle.isChecked = value == 1; toggle.isEnabled = enabled }
            } else {
                var tracking = false
                val slider = SeekBar(context).apply {
                    min = setting.range.first
                    max = setting.range.last
                    contentDescription = setting.label
                    isSaveEnabled = false
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                            if (fromUser && !tracking) send(setting, progress)
                        }
                        override fun onStartTrackingTouch(bar: SeekBar) { tracking = true }
                        override fun onStopTrackingTouch(bar: SeekBar) { tracking = false; send(setting, bar.progress) }
                    })
                }
                row.addView(slider)
                render = { value, enabled ->
                    caption.text = String.format(Locale.getDefault(), "%s：%d", setting.label, value)
                    slider.progress = value
                    slider.isEnabled = enabled
                }
            }
            rows += Row(setting, row, render)
            controls.addView(row)
        }
        addTouchAndFind(controls)
        body.addView(ScrollView(context).apply { addView(controls) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return body
    }

    private fun sendRequest(request: (EarbudState) -> ControlRequest?) {
        if (rendering || closed) return
        try {
            val state = environment.stateProvider(address)
            if (!valid(state)) { dismiss(); return }
            request(state)?.let { environment.controlSender(address, it) }
            render(environment.stateProvider(address))
        } catch (_: Exception) { dismiss() }
    }

    private fun addInfoAndAudio(controls: LinearLayout) {
        val firmware = text("固件版本", 17f).apply { setPadding(0, dp(12), 0, dp(12)) }
        controls.addView(firmware)
        val audio = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        audio.addView(text("音频模式", 17f))
        var pending: RoseCoralAudioMode? = null
        val labels = mapOf(RoseCoralAudioMode.AAC_SBC to "AAC/SBC", RoseCoralAudioMode.LDAC to "LDAC",
            RoseCoralAudioMode.LHDC to "LHDC", RoseCoralAudioMode.LC3 to "LC3")
        val options = RoseCoralAudioMode.entries.map { mode ->
            text(labels.getValue(mode), 16f).apply {
                contentDescription = "音频模式 ${labels.getValue(mode)}"
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener {
                    try {
                        val state = environment.stateProvider(address)
                        if (!valid(state)) { dismiss() } else {
                            pending = mode.takeIf { RoseCoralCardPolicy.audioRequest(state, address, it) != null }
                            render(state)
                        }
                    } catch (_: Exception) { dismiss() }
                }
                audio.addView(this)
            }
        }
        val confirmation = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val prompt = text("", 16f)
        confirmation.addView(prompt)
        confirmation.addView(text("确认切换", 16f).apply {
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                val mode = pending ?: return@setOnClickListener
                pending = null
                sendRequest { RoseCoralCardPolicy.audioRequest(it, address, mode) }
            }
        })
        confirmation.addView(text("取消切换", 16f).apply {
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                pending = null
                runCatching { render(environment.stateProvider(address)) }.onFailure { dismiss() }
            }
        })
        audio.addView(confirmation)
        controls.addView(audio)
        extraRows += { state ->
            val info = state.features.get<RoseCoralInfoFeatureState>()
            firmware.visibility = if (info == null) View.GONE else View.VISIBLE
            firmware.text = String.format(Locale.getDefault(), "固件版本：%s", info?.firmwareVersion.orEmpty())
            val reported = state.features.get<RoseCoralAudioFeatureState>()?.mode
            audio.visibility = if (reported == null) View.GONE else View.VISIBLE
            if (reported == null || reported == pending) pending = null
            options.forEachIndexed { index, option ->
                val mode = RoseCoralAudioMode.entries[index]
                option.isEnabled = reported != null
                option.isSelected = reported == mode
                option.text = String.format(Locale.getDefault(), "%s%s", if (option.isSelected) "✓ " else "", labels.getValue(mode))
            }
            confirmation.visibility = if (pending == null) View.GONE else View.VISIBLE
            prompt.text = pending?.let { "切换到 ${labels.getValue(it)}？切换可能影响音频连接。" }.orEmpty()
        }
    }

    private fun addTouchAndFind(controls: LinearLayout) {
        val actionLabels = listOf("无操作", "播放 / 暂停", "上一曲", "下一曲", "音量增加", "音量降低", "游戏模式", "语音助手", "切换降噪")
        val slotLabels = listOf("左耳单击", "左耳双击", "左耳三击", "左耳长按", "右耳单击", "右耳双击", "右耳三击", "右耳长按")
        RoseCoralTouchSlot.entries.forEachIndexed { index, slot ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val choices = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            val caption = text(slotLabels[index], 17f).apply {
                setPadding(0, dp(14), 0, dp(14))
                setOnClickListener {
                    try {
                        val state = environment.stateProvider(address)
                        if (!valid(state)) { dismiss() } else {
                            choices.isVisible = !choices.isVisible
                            render(state)
                        }
                    } catch (_: Exception) { dismiss() }
                }
            }
            row.addView(caption)
            val options = RoseCoralTouchAction.entries.mapIndexed { actionIndex, action ->
                text(actionLabels[actionIndex], 16f).apply {
                    setPadding(dp(16), dp(12), dp(8), dp(12))
                    setOnClickListener { sendRequest { RoseCoralCardPolicy.touchRequest(it, address, slot, action) } }
                    choices.addView(this)
                }
            }
            row.addView(choices)
            controls.addView(row)
            extraRows += { state ->
                val feature = state.features.get<RoseCoralTouchFeatureState>()
                row.visibility = if (feature == null) View.GONE else View.VISIBLE
                val selected = feature?.action(slot)
                caption.text = String.format(Locale.getDefault(), "%s：%s ›", slotLabels[index],
                    selected?.let { actionLabels[it.ordinal] } ?: "未知")
                options.forEachIndexed { actionIndex, option ->
                    option.isEnabled = feature != null
                    option.isSelected = selected?.ordinal == actionIndex
                    option.text = String.format(Locale.getDefault(), if (option.isSelected) "✓ %s" else "%s", actionLabels[actionIndex])
                }
            }
        }
        val findRow = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val status = text("寻找耳机", 17f)
        findRow.addView(status)
        val targetLabels = listOf("响铃左耳", "响铃右耳", "响铃双耳", "停止响铃")
        RoseCoralFindTarget.entries.forEachIndexed { index, target ->
            findRow.addView(text(targetLabels[index], 16f).apply {
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { sendRequest { RoseCoralCardPolicy.findRequest(it, address, target) } }
            })
        }
        controls.addView(findRow)
        extraRows += { state ->
            val feature = state.features.get<RoseCoralFindFeatureState>()
            findRow.visibility = if (feature == null) View.GONE else View.VISIBLE
            fun reported(value: Boolean?) = when (value) { true -> "响铃中"; false -> "已停止"; null -> "未知" }
            status.text = String.format(Locale.getDefault(), "寻找耳机 · 左耳：%s · 右耳：%s",
                reported(feature?.left), reported(feature?.right))
        }
    }

    private fun createToggle(): CompoundButton = runCatching {
        Class.forName("miuix.slidingwidget.widget.SlidingButton", true, environment.hostClassLoader)
            .asSubclass(CompoundButton::class.java).getConstructor(Context::class.java).newInstance(context)
    }.getOrElse { @Suppress("DEPRECATION") Switch(context) }

    private fun text(value: String, size: Float) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color(true))
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun color(text: Boolean): Int {
        val dark = context.resources.configuration.uiMode and 0x30 == 0x20
        return if (text) { if (dark) Color.WHITE else Color.BLACK } else {
            if (dark) 0xff242424.toInt() else 0xfff5f5f5.toInt()
        }
    }
    private data class Row(val setting: RoseCoralSetting, val container: View, val render: (Int, Boolean) -> Unit)

    companion object {
        fun open(anchor: View, address: String, environment: MiLinkCardEnvironment): MiLinkToggleDetails =
            try {
                RoseCoralSettingsDetails(anchor, address, environment)
            } catch (error: Exception) {
                // Host exceptions can contain addresses or state payloads; log only their type.
                ModuleLog.warn("MiLinkUi", "Coral settings panel unavailable (${error.javaClass.simpleName})")
                ClosedDetails
            }

        private object ClosedDetails : MiLinkToggleDetails {
            override fun render(state: EarbudState) = Unit
            override fun dismiss() = Unit
        }
    }
}
