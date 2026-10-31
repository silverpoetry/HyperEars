package dev.hyperears.hook

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.hyperears.integration.EarbudState
import java.lang.ref.WeakReference

/** Small title accessory; original title/card attachment is restored on every exit. */
internal class AppleSettingsCardEntry private constructor(
    original: View,
    parent: ViewGroup,
    private val index: Int,
    private val params: ViewGroup.LayoutParams,
    wrapper: LinearLayout,
    entry: TextView,
    private val address: String,
    private val environment: MiLinkCardEnvironment,
    private val isAvailable: (EarbudState, String) -> Boolean,
    private val openDetails: (View, String, MiLinkCardEnvironment) -> MiLinkToggleDetails,
) : MiLinkCardBinding {
    private val originalRef = WeakReference(original)
    private val parentRef = WeakReference(parent)
    private val wrapperRef = WeakReference(wrapper)
    private val entryRef = WeakReference(entry)
    private var token = environment.sessionTokenProvider(address)
    private var bound = true
    private var details: MiLinkToggleDetails? = null
    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { details?.dismiss(); details = null }
    }
    init {
        wrapper.addOnAttachStateChangeListener(detachListener)
        entry.setOnClickListener {
            try {
                if (bound && available(environment.stateProvider(address))) {
                    details?.dismiss()
                    details = openDetails(entry, address, environment)
                } else { details?.dismiss(); details = null }
            } catch (_: Exception) { details?.dismiss(); details = null }
        }
    }
    private fun available(state: EarbudState) = token != null && token == environment.sessionTokenProvider(address) &&
        isAvailable(state, address)
    override fun render(state: EarbudState) {
        if (!bound) return
        val currentToken = environment.sessionTokenProvider(address)
        if (token != currentToken) { details?.dismiss(); details = null; token = currentToken }
        val available = available(state)
        val entry = entryRef.get() ?: return
        entry.visibility = if (available) View.VISIBLE else View.GONE
        entry.isEnabled = available
        if (available) details?.render(state) else { details?.dismiss(); details = null }
    }
    override fun unbind() {
        if (!bound) return
        bound = false
        details?.dismiss()
        details = null
        entryRef.get()?.setOnClickListener(null)
        val wrapper = wrapperRef.get() ?: return
        wrapper.removeOnAttachStateChangeListener(detachListener)
        val original = originalRef.get() ?: return
        val parent = parentRef.get() ?: return
        if (wrapper.parent === parent) {
            wrapper.removeView(original)
            parent.removeView(wrapper)
            original.layoutParams = params
            parent.addView(original, index.coerceAtMost(parent.childCount))
        }
    }
    companion object {
        fun bind(title: TextView?, card: View, address: String, environment: MiLinkCardEnvironment,
            isAvailable: (EarbudState, String) -> Boolean = AppleSettingsCardPolicy::available,
            openDetails: (View, String, MiLinkCardEnvironment) -> MiLinkToggleDetails = AppleSettingsDetails::open,
        ): AppleSettingsCardEntry? {
            val original = title ?: card
            val parent = original.parent as? ViewGroup ?: return null
            val index = parent.indexOfChild(original)
            if (index < 0) return null
            val params = original.layoutParams
            val wrapper = LinearLayout(original.context).apply {
                orientation = if (title != null) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val entry = TextView(original.context).apply {
                text = "高级设置 ›"
                contentDescription = "耳机高级设置"
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 12, 16, 12)
                if (title != null) { setTextColor(title.textColors); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, title.textSize) }
                visibility = View.GONE
            }
            try {
                parent.removeView(original)
                if (title == null) wrapper.addView(entry)
                wrapper.addView(original, if (title != null) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (title != null) wrapper.addView(entry)
                parent.addView(wrapper, index, params)
                return AppleSettingsCardEntry(original, parent, index, params, wrapper, entry, address, environment, isAvailable, openDetails)
            } catch (error: Exception) {
                (original.parent as? ViewGroup)?.removeView(original)
                if (wrapper.parent === parent) parent.removeView(wrapper)
                original.layoutParams = params
                parent.addView(original, index.coerceAtMost(parent.childCount))
                return null
            }
        }
    }
}
