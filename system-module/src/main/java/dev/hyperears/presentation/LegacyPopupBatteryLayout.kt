package dev.hyperears.presentation

import android.app.Activity
import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import java.util.IdentityHashMap

/** Native TWS content plus the native single-battery row, retained for the window's lifetime. */
internal class LegacyPopupBatteryLayout {
    private data class Content(val ids: Map<String, Int>, val single: View, val headsetLayout: RelativeLayout.LayoutParams)
    private val content = IdentityHashMap<View, Content>()

    @SuppressLint("DiscouragedApi") // This plugin strips R classes; names are audited in its resource table.
    fun apply(
        activity: Activity,
        root: View,
        p: NativeHeadsetProjection,
        newStyle: Boolean,
        icons: IntArray,
        singleLabel: String,
        drawable: (Int) -> Drawable?,
    ) {
        require(icons.size >= 7)
        val ids = content[root]?.ids ?: run {
            require(root.resources.getResourceEntryName(root.id) == "groupDialog")
            val namespace = root.resources.getResourcePackageName(root.id)
            NAMES.associateWith { name ->
                root.resources.getIdentifier(name, "id", namespace).also { require(it != 0) { "Missing native ID: $name" } }
            }.also { require(it.values.distinct().size == it.size) }
        }
        fun <T : View> view(name: String): T {
            val id = ids.getValue(name)
            require(root.resources.getResourceEntryName(id) == name)
            return requireNotNull(root.findViewById<T>(id)) { "Native battery view unavailable: $name" }
        }
        val group = view<RelativeLayout>("groupConnectSuccess")
        val headset = view<RelativeLayout>("imageViewHeadsetLayout")
        val entry = content.getOrPut(root) {
            // The legacy TWS layout has no aggregate row. Reuse the row from its own single-headset
            // layout at the named success slot; never replace the Dialog or infer a parent chain.
            val name = if (newStyle) "new_fc_nzbt_dialog" else "fc_nzbt_dialog"
            val layout = root.resources.getIdentifier(name, "layout", root.resources.getResourcePackageName(root.id))
            require(layout != 0) { "Missing native single-battery layout: $name" }
            val source = activity.layoutInflater.inflate(layout, null)
            val row = requireNotNull(source.findViewById<View>(ids.getValue("headsetBatteryInfo")))
            val sourceGroup = requireNotNull(source.findViewById<ViewGroup>(ids.getValue("groupConnectSuccess")))
            require(row.parent === sourceGroup && root.findViewById<View>(row.id) == null)
            sourceGroup.removeView(row)
            val params = RelativeLayout.LayoutParams(row.layoutParams as RelativeLayout.LayoutParams).apply {
                addRule(RelativeLayout.BELOW, ids.getValue("imageViewHeadsetBox"))
                addRule(RelativeLayout.CENTER_HORIZONTAL)
            }
            group.addView(row, params)
            Content(ids, row, RelativeLayout.LayoutParams(headset.layoutParams as RelativeLayout.LayoutParams))
        }
        entry.single.visibility = if (p.singleBattery) View.VISIBLE else View.GONE
        view<View>("textViewHeadsetBattery").visibility = if (p.singleBattery) View.GONE else View.VISIBLE
        val caseVisibility = if (p.showCaseInPopup) View.VISIBLE else View.GONE
        view<View>("imageViewBoxLayout").visibility = caseVisibility
        view<View>("textViewBoxBattery").visibility = caseVisibility
        headset.layoutParams = RelativeLayout.LayoutParams(entry.headsetLayout).apply {
            if (!p.showCaseInPopup) {
                listOf(RelativeLayout.LEFT_OF, RelativeLayout.RIGHT_OF, RelativeLayout.START_OF,
                    RelativeLayout.END_OF, RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.ALIGN_PARENT_RIGHT,
                    RelativeLayout.ALIGN_PARENT_START, RelativeLayout.ALIGN_PARENT_END).forEach(::removeRule)
                addRule(RelativeLayout.CENTER_HORIZONTAL)
            }
        }
        fun reading(percent: Int?, charging: Boolean, text: String, image: String, charge: String) {
            view<TextView>(text).text = percent?.let { "$it%" } ?: "-"
            view<ImageView>(image).setImageDrawable(drawable(icons[legacyBatteryLevel(percent)]))
            view<View>(charge).visibility = if (charging && percent != null) View.VISIBLE else View.GONE
        }
        reading(p.left.percent, p.left.charging, "textViewHeadsetLBatteryPercent", "imageViewHeadsetLBattery", "imageViewHeadsetLCharge")
        reading(p.right.percent, p.right.charging, "textViewHeadsetRBatteryPercent", "imageViewHeadsetRBattery", "imageViewHeadsetRCharge")
        reading(p.chargingCase.percent, p.chargingCase.charging, "textViewBoxBatteryPercent", "imageViewBoxBattery", "imageViewBoxCharge")
        reading(p.left.percent, p.left.charging, "textViewHeadsetBatteryPercent", "imageViewHeadsetBattery", "imageViewHeadsetCharge")
        // The original single row's label is populated by its Controller, not by XML.
        view<TextView>("textViewHeadset").text = singleLabel
    }

    fun release(root: View?) { if (root != null) content.remove(root) }

    companion object {
        private val NAMES = listOf("groupConnectSuccess", "imageViewHeadsetLayout", "imageViewHeadsetBox",
            "textViewHeadsetBattery", "imageViewBoxLayout", "textViewBoxBattery", "headsetBatteryInfo",
            "textViewHeadsetLBatteryPercent", "imageViewHeadsetLBattery", "imageViewHeadsetLCharge",
            "textViewHeadsetRBatteryPercent", "imageViewHeadsetRBattery", "imageViewHeadsetRCharge",
            "textViewBoxBatteryPercent", "imageViewBoxBattery", "imageViewBoxCharge",
            "textViewHeadsetBatteryPercent", "imageViewHeadsetBattery", "imageViewHeadsetCharge", "textViewHeadset")
    }
}

/** Native setters clamp 127 to 100; unknown values must be rendered independently as unknown. */
internal fun legacyBatteryLevel(percent: Int?): Int =
    if (percent == null || percent !in 0..100) 6 else (percent + 19) / 20
