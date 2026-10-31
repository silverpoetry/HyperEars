package dev.hyperears.hook

import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/** Restore recycled host holders before normal binding; only module-owned rows get custom content. */
internal object CustomPreferenceRows {
    private data class Content(val view: View, val minimumHeight: Int)
    private data class Saved(val children: List<Pair<View,Int>>, val minimumHeight: Int, val custom: View, val layout: View.OnLayoutChangeListener)
    private val installed=mutableSetOf<java.lang.reflect.Method>()
    @Synchronized fun install(module:io.github.libxposed.api.XposedModule,loader:ClassLoader) {
        val type=loader.loadClass("androidx.preference.Preference")
        val method=type.declaredMethods.single {it.name=="onBindViewHolder"&&it.parameterCount==1}
        if(method in installed)return
        module.hook(method).intercept {chain->
            val holder=requireNotNull(chain.args[0]);restore(holder)
            val result=chain.proceed()
            chain.thisObject?.let {bind(it,holder)}
            result
        }
        installed.add(method)
    }
    private val contents=WeakHashMap<Any,Content>()
    private val tag get()=dev.hyperears.R.id.hyperears_custom_preference_state
    fun put(row:Any,view:View,height:Int) { contents[row]=Content(view,height) }
    fun remove(row:Any) { contents.remove(row) }
    private fun root(holder:Any)=holder.javaClass.getField("itemView").get(holder) as? ViewGroup
    fun restore(holder:Any) {
        val root=root(holder) ?: return
        val saved=root.getTag(tag) as? Saved ?: return
        root.removeOnLayoutChangeListener(saved.layout)
        root.removeView(saved.custom)
        saved.children.forEach { (view,visibility)->view.visibility=visibility }
        root.minimumHeight=saved.minimumHeight
        root.setTag(tag,null)
    }
    fun bind(row:Any,holder:Any) {
        val content=contents[row] ?: return
        val root=root(holder) ?: return
        val children=(0 until root.childCount).map { root.getChildAt(it).let { v->v to v.visibility } }
        fun layoutContent() {
            if((root.getTag(tag) as? Saved)?.custom !== content.view) return
            val inset=maxOf(root.paddingLeft,(16*root.resources.displayMetrics.density).toInt())
            val width=(root.width-inset*2).coerceAtLeast(0)
            content.view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.height,View.MeasureSpec.AT_MOST))
            val top=((root.height-content.view.measuredHeight)/2).coerceAtLeast(0)
            content.view.layout(inset,top,inset+width,top+content.view.measuredHeight)
        }
        val listener=View.OnLayoutChangeListener { _,_,_,_,_,_,_,_,_->layoutContent() }
        val saved=Saved(children,root.minimumHeight,content.view,listener)
        children.forEach { it.first.visibility=View.GONE }
        (content.view.parent as? ViewGroup)?.removeView(content.view)
        root.addView(content.view,ViewGroup.LayoutParams(-1,-2))
        root.minimumHeight=content.minimumHeight
        root.setTag(tag,saved)
        root.addOnLayoutChangeListener(listener)
        root.post { layoutContent() }
    }
}
