package dev.hyperears.hook

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference

/** Transactional replacement of a resolved native ANC row, shared by FloS2 and Apple. */
internal class NativeAncModeHost(
    title: TextView?,
    row: NativeAncModeRow,
) {
    private data class OriginalItem(
        val view: View,
        val index: Int,
        val layoutParams: ViewGroup.LayoutParams,
        val visibility: Int,
    )

    private val layout = row.layout
    val generation: String = layout.logName
    val sources: List<View> = row.items

    private val title = title?.let(::WeakReference)
    private val container = WeakReference(row.container)
    private val originalTitle = title?.text
    private val originals = row.items.map { item ->
        OriginalItem(
            view = item,
            index = row.container.indexOfChild(item),
            layoutParams = item.layoutParams,
            visibility = item.visibility,
        )
    }

    private val replacements = mutableListOf<WeakReference<View>>()

    fun createItem(root: View, source: View, label: String, icon: Drawable?): View? {
        val item = layout.createItem(root, source, label) ?: return null
        val drawable = icon ?: return item
        (item.findMiLinkView(layout.itemIconId) as? ImageView)?.setImageDrawable(drawable)
        return item
    }

    fun install(titleText: String, items: List<View>): Boolean {
        val container = container.get() ?: return false
        try {
            title?.get()?.text = titleText
            originals.sortedByDescending(OriginalItem::index).forEach { original ->
                if (original.view.parent === container) container.removeView(original.view)
            }
            val insertion = originals.minOfOrNull(OriginalItem::index)
                ?.coerceIn(0, container.childCount)
                ?: container.childCount
            items.forEachIndexed { offset, item ->
                // A host callback may throw after addView has already attached the item.
                replacements += WeakReference(item)
                container.addView(item, insertion + offset)
            }
            return true
        } catch (error: Exception) {
            runCatching { restore() }.onFailure(error::addSuppressed)
            ModuleLog.warn("MiLinkUi", "native card installation failed; restoring host", error)
            return false
        }
    }

    fun restore() {
        val container = container.get() ?: return
        replacements.mapNotNull(WeakReference<View>::get).forEach { replacement ->
            if (replacement.parent === container) container.removeView(replacement)
        }
        originals.sortedBy(OriginalItem::index).forEach { original ->
            if (original.view.parent == null) {
                original.view.layoutParams = original.layoutParams
                original.view.visibility = original.visibility
                container.addView(
                    original.view,
                    original.index.coerceIn(0, container.childCount),
                )
            }
        }
        title?.get()?.text = originalTitle
        replacements.clear()
    }
}
