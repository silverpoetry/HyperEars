package dev.hyperears.hook

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import dev.hyperears.integration.EarbudState

internal object NativeHeadsetWidgets {
    fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    fun dark(context: Context) = context.resources.configuration.uiMode and 0x30 == 0x20
    fun primary(context: Context) = if (dark(context)) Color.WHITE else Color.rgb(25,25,25)
    fun surface(context: Context) = if (dark(context)) Color.rgb(50,50,50) else Color.rgb(239,240,243)
    const val ACCENT = 0xff3482ff.toInt()
    fun find(root: View, loader: ClassLoader, name: String): View? = runCatching {
        root.findViewById<View>(loader.loadClass("plugin.settings.java.R\$id").getDeclaredField(name).getInt(null))
    }.getOrNull()

    fun singleBattery(root: View, loader: ClassLoader, state: EarbudState) {
        val image = find(root,loader,"tic") as? ImageView ?: return
        val dp = root.resources.displayMetrics.density
        val tag = "hyperears.single_battery"
        var badge = root.findViewWithTag<TextView>(tag)
        if (badge == null) {
            val parent = image.parent as? ViewGroup ?: return
            val original = image.layoutParams
            val index = parent.indexOfChild(image)
            parent.removeView(image)
            val frame = FrameLayout(root.context)
            parent.addView(frame,index,original)
            frame.addView(image,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
            badge = TextView(root.context).apply {
                this.tag = tag; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(primary(context)); setPadding((12*dp).toInt(),(7*dp).toInt(),(12*dp).toInt(),(7*dp).toInt())
                background = rounded(if(dark(context)) 0xdd303030.toInt() else 0xddffffff.toInt(),20*dp)
            }
            frame.addView(badge,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply {
                marginEnd=(12*dp).toInt(); bottomMargin=(12*dp).toInt()
            })
        }
        val battery = state.battery.overall
        val text = if(battery.available) (if(battery.charging) "⚡ " else "") + "${battery.percent}%" else "电量未知"
        if(badge.text.toString()!=text) badge.text=text
        badge.contentDescription="设备电量 $text"
        find(root,loader,"groupLeft")?.visibility=View.GONE
        find(root,loader,"batteryCard")?.visibility=View.GONE
    }

}
