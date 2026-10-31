package dev.hyperears.presentation

import android.annotation.SuppressLint
import android.view.View

/** Replace only the authenticated popup content action, never child close/retry actions. */
internal class NativePopupNavigation(val view: View, open: () -> Unit) {
    private val previous = currentListener()
    private val clickable = view.isClickable
    private val focusable = view.isFocusable
    private val installed = View.OnClickListener { open() }
    private var released = false
    init { view.setOnClickListener(installed); view.isFocusable = true }
    fun ownsClick() = !released && currentListener() === installed
    fun release() {
        if (released) return
        val owns = ownsClick()
        released = true
        if (!owns) return
        view.setOnClickListener(previous)
        view.isClickable = clickable
        view.isFocusable = focusable
    }
    // Runs only in the LSPosed host. Resolve before mutation; the bridge retains stock
    // navigation if this private member is unavailable on a future ROM.
    @SuppressLint("DiscouragedPrivateApi")
    private fun currentListener(): View.OnClickListener? {
        val info = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible=true }.get(view) ?: return null
        return info.javaClass.getDeclaredField("mOnClickListener").apply { isAccessible=true }.get(info) as? View.OnClickListener
    }
}
