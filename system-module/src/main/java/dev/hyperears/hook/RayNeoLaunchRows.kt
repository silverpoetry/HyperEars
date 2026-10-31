package dev.hyperears.hook

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import dev.hyperears.integration.*

/** Three stable rows: shortcuts, app chooser, and a transient acknowledgement log. */
internal class RayNeoLaunchRows(private val context:Context, private val invalidate:()->Unit) {
    private fun message(resource:Int,name:String):String =
        context.createPackageContext("dev.hyperears",0).getString(resource,name)
    private val dp=context.resources.displayMetrics.density
    private val shortcuts=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private val buttons=RayNeoQuickLaunch.entries.map { app -> TextView(context).apply {
        text=app.label; textSize=14f; gravity=Gravity.CENTER
        setTextColor(NativeHeadsetWidgets.primary(context))
        background=NativeHeadsetWidgets.rounded(NativeHeadsetWidgets.surface(context),12*dp)
        shortcuts.addView(this,LinearLayout.LayoutParams(0,(46*dp).toInt(),1f).apply { marginStart=(3*dp).toInt();marginEnd=(3*dp).toInt() })
    } }
    private val notice=TextView(context).apply { textSize=12f; gravity=Gravity.CENTER_VERTICAL; setTextColor(NativeHeadsetWidgets.primary(context)) }
    private var token:String?=null
    private var pendingPackage:String?=null
    private var baseline=0L
    private var timeout:Runnable?=null
    private var clearing:Runnable?=null
    private fun clearTasks() { timeout?.let(notice::removeCallbacks);clearing?.let(notice::removeCallbacks);timeout=null;clearing=null }
    fun close() { clearTasks();pendingPackage=null;token=null;notice.text="" }
    private fun result(text:String) {
        clearTasks();pendingPackage=null;notice.text=text
        clearing=Runnable { notice.text="" }.also { notice.postDelayed(it,3_000) }
    }
    fun add(host:SettingsPreferenceHost,state:EarbudState,address:String,session:String,
        add:(Any)->Unit,latest:()->EarbudState?,send:(ControlRequest)->Unit) {
        val feature=state.features.get<RayNeoAppsFeatureState>()
        if(token!=session) { close();token=session }
        if(pendingPackage!=null && feature!=null && feature.lastLaunchPackage==pendingPackage && feature.lastLaunchSequence>baseline) {
            val name=RayNeoAppLabels.label(requireNotNull(pendingPackage))
            result(if(feature.lastLaunchStatus in 0..1) message(dev.hyperears.R.string.rayneo_launch_success,name) else message(dev.hyperears.R.string.rayneo_launch_failed,name))
        }
        fun launch(packageName:String) {
            if(pendingPackage!=null) return
            val fresh=latest()?.takeIf { SettingsRayNeoRows.ready(it,address) } ?: return
            val current=fresh.features.get<RayNeoAppsFeatureState>() ?: return
            val request=RayNeoControlRequest.LaunchInstalledApp(packageName)
            if(!current.supports(request)) return
            clearTasks();pendingPackage=packageName;baseline=current.lastLaunchSequence
            val name=RayNeoAppLabels.label(packageName)
            notice.text=message(dev.hyperears.R.string.rayneo_launch_pending,name)
            timeout=Runnable {
                result(message(dev.hyperears.R.string.rayneo_launch_timeout,name))
                invalidate()
            }.also { notice.postDelayed(it,6_000) }
            try { send(request) } catch(error:Exception) { result(message(dev.hyperears.R.string.rayneo_launch_failed,name));invalidate() }
        }
        val enabled=SettingsRayNeoRows.ready(state,address)&&pendingPackage==null
        buttons.forEachIndexed { index,button ->
            val app=RayNeoQuickLaunch.entries[index]
            button.isEnabled=enabled&&feature?.installedPackages?.contains(app.packageName)==true
            button.alpha=if(button.isEnabled) 1f else .4f
            button.setOnClickListener { launch(app.packageName) }
        }
        add(host.customRow("hyperears.rayneo.shortcuts",shortcuts,58))
        val collator=java.text.Collator.getInstance()
        val packages=feature?.installedPackages.orEmpty().minus(RayNeoQuickLaunch.entries.map { it.packageName }.toSet())
            .sortedWith { a,b->collator.compare(RayNeoAppLabels.label(a),RayNeoAppLabels.label(b)).takeIf { it!=0 } ?: a.compareTo(b) }
        add(host.choice("hyperears.rayneo.launch.other","其他应用",packages.map(RayNeoAppLabels::label),packages,null,
            enabled&&packages.isNotEmpty(),::launch))
        add(host.customRow("hyperears.rayneo.launch.notice",notice,28))
    }
}
