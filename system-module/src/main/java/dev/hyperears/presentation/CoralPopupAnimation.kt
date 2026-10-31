package dev.hyperears.presentation

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView

/** One entrance per popup, using the stock image slots. No extra overlay, timer or frame cache. */
internal class CoralPopupAnimation(private val headset: ImageView, private val box: ImageView) : AutoCloseable {
    private var started = false
    private var closed = false
    private val listener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = start()
        override fun onViewDetachedFromWindow(view: View) = close()
    }

    fun attach() {
        if (closed || started) return
        headset.addOnAttachStateChangeListener(listener)
        if (headset.isAttachedToWindow) start()
    }

    private fun start() {
        if (closed || started) return
        started = true
        if (!ValueAnimator.areAnimatorsEnabled()) return
        headset.alpha = 0f
        headset.translationY = 28f * headset.resources.displayMetrics.density
        box.alpha = 0f
        box.animate().alpha(1f).setDuration(300).start()
        headset.animate().alpha(1f).translationY(0f).setDuration(800)
            .setInterpolator(DecelerateInterpolator(1.5f)).start()
    }

    override fun close() {
        if (closed) return
        closed = true
        headset.removeOnAttachStateChangeListener(listener)
        listOf(headset, box).forEach { it.animate().cancel(); it.alpha = 1f; it.translationY = 0f }
    }

    companion object {
        fun resolve(root: View, loader: ClassLoader): CoralPopupAnimation {
            for (name in listOf("com.xiaomi.bluetooth.peripheral.R\$id", "com.xiaomi.bluetooth.sample.preloadedFeature.R\$id")) {
                val pair = runCatching {
                    val type = Class.forName(name, false, loader)
                    fun image(key: String): ImageView {
                        val id = type.getDeclaredField(key).getInt(null)
                        require(root.resources.getResourceEntryName(id) == key)
                        return requireNotNull(root.findViewById<ImageView>(id))
                    }
                    image("imageViewHeadset") to image("imageViewBox")
                }.getOrNull() ?: continue
                return CoralPopupAnimation(pair.first, pair.second)
            }
            error("Native Coral image slots unavailable")
        }
    }
}
