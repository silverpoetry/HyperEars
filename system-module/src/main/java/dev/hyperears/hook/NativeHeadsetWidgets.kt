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

    fun voice(root: View, loader: ClassLoader, mode: Int?, token: String, enabled: Boolean, changed: (Int)->Boolean) {
        val card = find(root,loader,"ancCard") as? ViewGroup ?: return
        find(root,loader,"anclayout")?.visibility=View.GONE
        card.visibility=if(mode in 0..2) View.VISIBLE else View.GONE
        if(mode !in 0..2) return
        val tag="hyperears.voice_segments"
        var selector=card.findViewWithTag<ModeSegments>(tag)
        if(selector==null) {
            val dp=root.resources.displayMetrics.density
            val column=LinearLayout(root.context).apply {
                orientation=LinearLayout.VERTICAL
                setPadding((20*dp).toInt(),(16*dp).toInt(),(20*dp).toInt(),(18*dp).toInt())
            }
            column.addView(TextView(root.context).apply { text="声音模式"; textSize=16f; setTextColor(primary(context)) })
            selector=ModeSegments(root.context).apply { this.tag=tag }
            column.addView(selector,LinearLayout.LayoutParams(-1,(48*dp).toInt()).apply { topMargin=(12*dp).toInt() })
            card.addView(column,ViewGroup.LayoutParams(-1,-2))
        }
        selector.bind(requireNotNull(mode),token,enabled,changed)
    }
}

internal class ModeSegments(context: Context): FrameLayout(context) {
    private val dp=resources.displayMetrics.density
    private val thumb=View(context).apply { background=NativeHeadsetWidgets.rounded(NativeHeadsetWidgets.ACCENT,14*dp) }
    private val labels=listOf("标准","轻语","澎湃")
    private val buttons=labels.map { label -> TextView(context).apply { text=label; gravity=Gravity.CENTER; textSize=14f } }
    private val pending=CoralSliderPendingState<String>(4_000)
    private var report=0
    private var token="initial"
    private var ready=false
    private var changed: (Int)->Boolean={false}
    private val expire=Runnable { render() }
    init {
        background=NativeHeadsetWidgets.rounded(NativeHeadsetWidgets.surface(context),18*dp)
        addView(thumb,LayoutParams(0,-1).apply { topMargin=(4*dp).toInt(); bottomMargin=(4*dp).toInt() })
        val row=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; setPadding((4*dp).toInt(),0,(4*dp).toInt(),0) }
        addView(row,LayoutParams(-1,-1))
        buttons.forEachIndexed { index, button ->
            row.addView(button,LinearLayout.LayoutParams(0,-1,1f))
            button.setOnClickListener {
                if(ready && index!=report && changed(index)) {
                    pending.submit("voice",report,index,token,SystemClock.elapsedRealtime())
                    removeCallbacks(expire);postDelayed(expire,4_010);render()
                }
            }
        }
    }
    fun bind(value:Int, session:String, enabled:Boolean, action:(Int)->Boolean) {
        report=value;token=session;ready=enabled;changed=action;render()
    }
    private fun render() {
        val p=pending.project("voice",report,token,SystemClock.elapsedRealtime())
        val cell=(width-8*dp).coerceAtLeast(0f)/3
        val index=if(layoutDirection==View.LAYOUT_DIRECTION_RTL) 2-p.value else p.value
        val x=4*dp+index*cell
        if(kotlin.math.abs(thumb.translationX-x)>.5f) {
            if(isLaidOut) thumb.animate().translationX(x).setDuration(140).start() else thumb.translationX=x
        }
        thumb.alpha=if(ready&&p.enabled) 1f else .45f
        buttons.forEachIndexed { i,b ->
            b.isEnabled=ready&&p.enabled; b.isSelected=i==p.value
            b.setTextColor(if(i==p.value) Color.WHITE else NativeHeadsetWidgets.primary(context))
        }
        if(p.timedOut!=null) Toast.makeText(context,"声音模式修改失败",Toast.LENGTH_SHORT).show()
    }
    override fun onMeasure(widthSpec:Int,heightSpec:Int) {
        thumb.layoutParams.width=((View.MeasureSpec.getSize(widthSpec)-8*dp).coerceAtLeast(0f)/3).toInt()
        super.onMeasure(widthSpec,heightSpec)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { super.onSizeChanged(w,h,oldw,oldh);render() }
    override fun onDetachedFromWindow() { removeCallbacks(expire);pending.reset();super.onDetachedFromWindow() }
}
