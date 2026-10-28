package dev.hyperears.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import dev.hyperears.bridge.BroadcastIdentity
import dev.hyperears.bridge.ModuleContract

class BatteryWidgetProvider:AppWidgetProvider() {
    override fun onEnabled(context:Context){reconcile(context,true)}
    override fun onUpdate(context:Context,manager:AppWidgetManager,appWidgetIds:IntArray){reconcile(context,true)}
    override fun onAppWidgetOptionsChanged(context:Context,manager:AppWidgetManager,id:Int,options:Bundle){
        renderAll(context);requestReplay(context)
    }
    override fun onDeleted(context:Context,appWidgetIds:IntArray){
        val prefs=BatteryWidgetPreferences(context);appWidgetIds.forEach(prefs::delete)
        reconcile(context,false)
    }
    override fun onDisabled(context:Context){disable(context)}
    override fun onRestored(context:Context,oldWidgetIds:IntArray,newWidgetIds:IntArray){
        val prefs=BatteryWidgetPreferences(context)
        val restored=oldWidgetIds.zip(newWidgetIds).map{(old,new)->new to prefs.fixedAddress(old)}
        oldWidgetIds.forEach(prefs::delete)
        restored.forEach{(new,address)->prefs.select(new,address)}
        reconcile(context,true)
    }
    companion object {
        fun component(context:Context)=ComponentName(context,BatteryWidgetProvider::class.java)
        fun ids(context:Context):IntArray = runCatching {AppWidgetManager.getInstance(context).getAppWidgetIds(component(context))}.getOrDefault(intArrayOf())
        internal fun owns(context:Context,id:Int):Boolean = id!=AppWidgetManager.INVALID_APPWIDGET_ID &&
            AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider==component(context)
        internal fun reconcile(context:Context,replay:Boolean) {
            if(ids(context).isEmpty()){disable(context);return}
            context.packageManager.setComponentEnabledSetting(ComponentName(context,WidgetStateReceiver::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP)
            renderAll(context)
            if(replay)requestReplay(context)
        }
        internal fun disable(context:Context) {
            context.packageManager.setComponentEnabledSetting(ComponentName(context,WidgetStateReceiver::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)
            BatteryWidgetRuntime.release();BatteryWidgetPreferences(context).clearAll()
        }
        internal fun requestReplay(context:Context) {
            if(ids(context).isNotEmpty())BroadcastIdentity.send(context,ModuleContract.requestState(ModuleContract.MODULE_PACKAGE))
        }
        internal fun renderAll(context:Context) {
            val manager=AppWidgetManager.getInstance(context);val prefs=BatteryWidgetPreferences(context)
            val records=BatteryWidgetRuntime.records(context)
            ids(context).forEach{id->
                val fixed=prefs.fixedAddress(id)
                val selected=BatteryWidgetProjection.select(records,fixed)
                manager.updateAppWidget(id,BatteryWidgetRenderer.render(context,id,manager.getAppWidgetOptions(id),selected,fixed))
            }
        }
    }
}
