package dev.hyperears.hook

import java.util.Locale

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.hyperears.integration.AppleSettingsFeatureState
import dev.hyperears.integration.EarbudState
import java.lang.ref.WeakReference

/** Second-level panel hosted entirely by MiLink, with accepted reports as the only UI truth. */
internal class AppleSettingsDetails private constructor(
    anchor: View,
    private val address: String,
    private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val token = environment.sessionTokenProvider(address)
    private val context = anchor.context
    private val rows = mutableListOf<Row>()
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
        environment.sessionTokenProvider(address) == token && AppleSettingsCardPolicy.available(state, address)

    override fun render(state: EarbudState) {
        if (closed) return
        if (!valid(state)) { dismiss(); return }
        val feature = state.features.get<AppleSettingsFeatureState>() ?: return
        rendering = true
        try {
            rows.forEach { row ->
                val value = row.setting.value(feature)
                row.container.visibility = if (value == null) View.GONE else View.VISIBLE
                if (value != null) row.render(value, AppleSettingsCardPolicy.enabled(state, row.setting))
            }
        } finally { rendering = false }
    }

    private fun send(setting: AppleSetting, value: Int) {
        if (rendering || closed) return
        val state = environment.stateProvider(address)
        if (!valid(state)) { dismiss(); return }
        try {
            AppleSettingsCardPolicy.request(state, address, setting, value)?.let {
                runCatching { environment.controlSender(address, it) }
                    .onFailure { ModuleLog.warn("MiLinkUi", "Apple setting send failed", it) }
            }
        } finally {
            // Widgets locally change on touch; restore accepted state until the headset replies.
            render(environment.stateProvider(address))
        }
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
        AppleSetting.entries.forEach { setting ->
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
            } else if (setting.choices.isNotEmpty()) {
                val group = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val options = setting.choices.mapIndexed { index, label ->
                    text(label, 15f).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(12), 0, dp(12))
                        setOnClickListener { send(setting, index) }
                        group.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                }
                row.addView(group)
                render = { value, enabled -> options.forEachIndexed { index, option ->
                    option.text = String.format(Locale.getDefault(), if (index == value) "✓ %s" else "%s", setting.choices[index])
                    option.isSelected = value == index
                    option.isEnabled = enabled
                } }
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
                    caption.text = String.format(Locale.getDefault(), "%s：%d%s", setting.label, value,
                        if (enabled) "" else "（仅自适应模式可调）")
                    slider.progress = value
                    slider.isEnabled = enabled
                }
            }
            rows += Row(setting, row, render)
            controls.addView(row)
        }
        body.addView(ScrollView(context).apply { addView(controls) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return body
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
    private data class Row(val setting: AppleSetting, val container: View, val render: (Int, Boolean) -> Unit)

    companion object {
        fun open(anchor: View, address: String, environment: MiLinkCardEnvironment): MiLinkToggleDetails =
            try {
                AppleSettingsDetails(anchor, address, environment)
            } catch (error: Exception) {
                // Host exceptions can contain addresses or state payloads; log only their type.
                ModuleLog.warn("MiLinkUi", "Apple settings panel unavailable (${error.javaClass.simpleName})")
                ClosedDetails
            }

        private object ClosedDetails : MiLinkToggleDetails {
            override fun render(state: EarbudState) = Unit
            override fun dismiss() = Unit
        }
    }
}
