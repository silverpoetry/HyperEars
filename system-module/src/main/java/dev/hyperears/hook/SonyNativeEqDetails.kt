package dev.hyperears.hook

import android.app.Dialog
import android.content.DialogInterface
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import dev.hyperears.integration.*

/** Native themed dialog: local edits are a draft, applied as one definition-checked EQ vector. */
internal class SonyNativeEqDetails private constructor(
    anchor: View, private val address: String, private val environment: MiLinkCardEnvironment,
    private val eqBaseline: SonyEqFeatureState,
) : MiLinkToggleDetails {
    private val context = anchor.context
    private val token = environment.sessionTokenProvider(address)
    private var closed = false
    private var dialog: Dialog? = null
    private val draft = eqBaseline.rawSteps.map { it - eqBaseline.center }.toMutableList()
    init {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        eqBaseline.bands.forEachIndexed { index, band ->
            val label = if (band.kind == 0x10) "Clear Bass" else "${if (band.kind == 2) band.value * 1000 else band.value} Hz"
            val caption = TextView(context).apply { text = String.format(java.util.Locale.getDefault(), "%s：%d", label, draft[index]); textSize = 16f }
            body.addView(caption)
            body.addView(SeekBar(context).apply {
                max = eqBaseline.center * 2
                progress = eqBaseline.rawSteps[index]
                contentDescription = label
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser && !closed) { draft[index] = progress - eqBaseline.center; caption.text = String.format(java.util.Locale.getDefault(), "%s：%d", label, draft[index]) }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            })
            body.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(-eqBaseline.center, 0, eqBaseline.center).map { if (it == 0) "0" else String.format(java.util.Locale.getDefault(), "%+d", it) }.forEachIndexed { mark, value ->
                    addView(TextView(context).apply {
                        text = value; textSize = 12f
                        gravity = when (mark) { 0 -> android.view.Gravity.START; 1 -> android.view.Gravity.CENTER; else -> android.view.Gravity.END }
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
            })
        }
        val scroll = ScrollView(context).apply { addView(body) }
        val type = runCatching { environment.hostClassLoader.loadClass("miuix.appcompat.app.AlertDialog\$Builder") }.getOrElse { android.app.AlertDialog.Builder::class.java }
        val builder = type.getConstructor(android.content.Context::class.java).newInstance(context)
        type.getMethod("setTitle", CharSequence::class.java).invoke(builder, "自定义均衡器")
        type.getMethod("setView", View::class.java).invoke(builder, scroll)
        type.getMethod("setNegativeButton", CharSequence::class.java, DialogInterface.OnClickListener::class.java).invoke(builder, "取消", null)
        type.getMethod("setPositiveButton", CharSequence::class.java, DialogInterface.OnClickListener::class.java).invoke(builder, "应用",
            DialogInterface.OnClickListener { _, _ ->
                val state = runCatching { environment.stateProvider(address) }.getOrNull()
                val request = state?.takeIf { !closed && environment.sessionTokenProvider(address) == token }
                    ?.let { request(it, address, eqBaseline, draft) }
                if (request == null) Toast.makeText(context, "耳机设置已变化，请重新打开均衡器", Toast.LENGTH_SHORT).show()
                else runCatching { environment.controlSender(address, request) }.onFailure {
                    Toast.makeText(context, "设置未发送，请检查连接", Toast.LENGTH_SHORT).show()
                }
            })
        dialog = (type.getMethod("show").invoke(builder) as Dialog).also { it.setOnDismissListener { closed = true; dialog = null } }
    }
    override fun render(state: EarbudState) {
        if (!SonyCardPolicy.ready(state, address) || environment.sessionTokenProvider(address) != token) dismiss()
    }
    override fun dismiss() { if (closed) return; closed = true; dialog?.dismiss(); dialog = null }
    companion object {
        fun request(state: EarbudState, address: String, eqBaseline: SonyEqFeatureState, steps: List<Int>): SonyControlRequest? {
            val current = state.features.get<SonyEqFeatureState>() ?: return null
            if (current.definition != eqBaseline.definition || current.rawSteps != eqBaseline.rawSteps) return null
            val definition = eqBaseline.definition ?: return null
            return SonyCardPolicy.request(state, address, SonyControlRequest.SetEqBands(steps.toList(), definition))
        }
        fun open(anchor: View, address: String, environment: MiLinkCardEnvironment): MiLinkToggleDetails? {
            val state = environment.stateProvider(address)
            val feature = state.features.get<SonyEqFeatureState>()?.takeIf { it.writableBands } ?: return null
            if (!SonyCardPolicy.ready(state, address)) return null
            return SonyNativeEqDetails(anchor, address, environment, feature)
        }
    }
}
