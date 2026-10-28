package dev.hyperears.hook

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.hyperears.integration.*
import java.lang.ref.WeakReference

internal class HonorAncDepthDetails private constructor(
    anchor: View, private val address: String, private val environment: MiLinkCardEnvironment,
) : MiLinkToggleDetails {
    private val anchor = WeakReference(anchor)
    private val context = anchor.context
    private val token = environment.sessionTokenProvider(address)
    private val popup = PopupWindow(context)
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var unsubscribe: (() -> Unit)? = null
    private var pending: HonorAncDepth? = null
    private val caption = text("降噪强度")
    private val status = text("")
    private val choices = linkedMapOf<HonorAncDepth, TextView>()
    private val timeout = Runnable { if (!closed && pending != null) {
        pending = null; status.text = "尚未收到耳机确认，请检查连接后重试。"
    } }
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = dismiss()
    }
    init {
        try {
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
                background = GradientDrawable().apply { setColor(backgroundColor()); cornerRadius = dp(20).toFloat() }
            }
            column.addView(caption); column.addView(status)
            HonorAncDepth.entries.forEach { depth ->
                val item = text(label(depth)).apply {
                    contentDescription = "降噪强度 ${label(depth)}"
                    setOnClickListener { send(depth) }
                }
                choices[depth] = item; column.addView(item)
            }
            column.addView(text("关闭").apply { setOnClickListener { dismiss() } })
            popup.contentView = ScrollView(context).apply { addView(column) }
            popup.width = (context.resources.displayMetrics.widthPixels * .86f).toInt()
            popup.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
    private fun valid(state: EarbudState) = token != null && token == environment.sessionTokenProvider(address) &&
        HonorAncDepthPolicy.available(state, address)
    private inline fun guarded(action: () -> Unit) { try { action() } catch (_: Exception) { dismiss() } }
    override fun render(state: EarbudState) = guarded {
        if (closed) return@guarded
        if (!valid(state)) { dismiss(); return@guarded }
        val feature = state.features.get<HonorAncDepthFeatureState>() ?: return@guarded
        val anc = state.noiseMode == NoiseMode.ANC && feature.current in feature.supported
        caption.text = if (anc) "降噪强度" else "请先开启降噪，再选择强度"
        if (pending != null && anc && feature.current == pending) {
            pending = null; handler.removeCallbacks(timeout); status.text = "耳机已确认降噪强度。"
        } else if (!anc) { pending = null; handler.removeCallbacks(timeout); status.text = "" }
        choices.forEach { (depth, item) ->
            item.visibility = if (depth in feature.supported) View.VISIBLE else View.GONE
            item.isEnabled = anc && depth in feature.supported
            item.isSelected = anc && feature.current == depth
        }
    }
    private fun send(depth: HonorAncDepth) = guarded {
        if (closed) return@guarded
        val current = environment.stateProvider(address)
        if (!valid(current)) { dismiss(); return@guarded }
        val request = HonorAncDepthPolicy.request(current, address, depth) ?: run { render(current); return@guarded }
        pending = depth; status.text = "已发送，等待耳机确认…"
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, 5000)
        environment.controlSender(address, request)
        render(environment.stateProvider(address))
    }
    override fun dismiss() {
        if (closed) return
        closed = true; pending = null; handler.removeCallbacks(timeout)
        anchor.get()?.removeOnAttachStateChangeListener(detach)
        val release = unsubscribe; unsubscribe = null; runCatching { release?.invoke() }
        runCatching { if (popup.isShowing) popup.dismiss() }
    }
    private fun dp(value: Int) = (context.resources.displayMetrics.density * value).toInt()
    private fun dark() = context.resources.configuration.uiMode and 0x30 == 0x20
    private fun backgroundColor() = if (dark()) 0xff242424.toInt() else 0xfff5f5f5.toInt()
    private fun text(value: String) = TextView(context).apply {
        text = value; textSize = 17f; setTextColor(if (dark()) Color.WHITE else Color.BLACK)
        setPadding(0, dp(12), 0, dp(12))
    }
    companion object {
        fun label(depth: HonorAncDepth) = when (depth) {
            HonorAncDepth.SMART -> "智能"; HonorAncDepth.LIGHT -> "轻度"
            HonorAncDepth.MEDIUM -> "均衡"; HonorAncDepth.DEEP -> "深度"
        }
        fun open(anchor: View, address: String, environment: MiLinkCardEnvironment): MiLinkToggleDetails =
            runCatching { HonorAncDepthDetails(anchor, address, environment) }.getOrElse { Closed }
        private object Closed : MiLinkToggleDetails {
            override fun render(state: EarbudState) = Unit
            override fun dismiss() = Unit
        }
    }
}
