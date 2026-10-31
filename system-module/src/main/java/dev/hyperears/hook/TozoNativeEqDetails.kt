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
internal class TozoNativeEqDetails private constructor(
    anchor: View, private val address: String, private val environment: MiLinkCardEnvironment,
    private val eqBaseline: TozoEqualizerFeatureState,
) : MiLinkToggleDetails {
    private val context = anchor.context
    private val token = environment.sessionTokenProvider(address)
    private var closed = false
    private var dialog: Dialog? = null
    private val draft = eqBaseline.bands.map { it.gainTenths }.toMutableList()
    init {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        eqBaseline.bands.forEachIndexed { index, band ->
            val label = "${band.frequencyHz} Hz"
            val caption = TextView(context).apply { text = String.format(java.util.Locale.getDefault(), "%s：%+.1f dB", label, draft[index] / 10.0); textSize = 16f }
            body.addView(caption)
            body.addView(SeekBar(context).apply {
                max = 100
                progress = band.gainTenths + 50
                contentDescription = label
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser && !closed) { draft[index] = progress - 50; caption.text = String.format(java.util.Locale.getDefault(), "%s：%+.1f dB", label, draft[index] / 10.0) }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            })
            body.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf("−5 dB", "0 dB", "+5 dB").forEachIndexed { mark, value ->
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
                if (draft == eqBaseline.bands.map { it.gainTenths }) Unit
                else if (request == null) Toast.makeText(context, "耳机设置已变化，请重新打开均衡器", Toast.LENGTH_SHORT).show()
                else runCatching { environment.controlSender(address, request) }.onFailure {
                    Toast.makeText(context, "设置未发送，请检查连接", Toast.LENGTH_SHORT).show()
                }
            })
        dialog = (type.getMethod("show").invoke(builder) as Dialog).also { it.setOnDismissListener { closed = true; dialog = null } }
    }
    override fun render(state: EarbudState) {
        if (!ready(state, address) || environment.sessionTokenProvider(address) != token) dismiss()
    }
    override fun dismiss() { if (closed) return; closed = true; dialog?.dismiss(); dialog = null }
    companion object {
        private fun ready(state: EarbudState, address: String) = NativeHeadsetPageModel.tozo(state) &&
            state.address.equals(address, true) && state.sessionActive && state.privateChannelConnected &&
            state.handshakeAccepted && state.lifecycle.controlOwnership == ControlOwnership.MODULE

        fun request(state: EarbudState, address: String, baseline: TozoEqualizerFeatureState, gains: List<Int>): TozoControlRequest.SetEqualizer? {
            if (!ready(state, address)) return null
            val current = state.features.get<TozoEqualizerFeatureState>() ?: return null
            return TozoControlRequest.SetEqualizer(baseline, gains.toList()).takeIf(current::supports)
        }

        fun open(anchor: View, address: String, environment: MiLinkCardEnvironment): MiLinkToggleDetails? {
            val state = environment.stateProvider(address)
            val feature = state.features.get<TozoEqualizerFeatureState>()?.takeIf { it.writableGains } ?: return null
            if (!ready(state, address) || environment.sessionTokenProvider(address).isNullOrBlank()) return null
            return TozoNativeEqDetails(anchor, address, environment, feature)
        }
    }
}
