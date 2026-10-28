package dev.hyperears.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.hyperears.bridge.BroadcastIdentity
import dev.hyperears.bridge.ModuleContract

/** No manifest intent-filter: only the reduced explicit Bluetooth delivery may wake this receiver. */
class WidgetStateReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        accept(context,intent,sentFromPackage,sentFromUid)
    }
    internal fun accept(context:Context,intent:Intent,sender:String?,uid:Int):Boolean {
        if(!BroadcastIdentity.accepts(context,sender,uid,ModuleContract.BLUETOOTH_PACKAGE))return false
        if(BatteryWidgetProvider.ids(context).isEmpty()){BatteryWidgetProvider.reconcile(context,false);return false}
        return BatteryWidgetRuntime.accept(context,intent)
    }
}

internal object BatteryWidgetRuntime {
    private var store:BatteryWidgetStateStore?=null
    fun records(context:Context):List<BatteryWidgetRecord> = store?.records()?:BatteryWidgetPreferences(context).records()
    fun accept(context:Context,intent:Intent):Boolean {
        val app=context.applicationContext
        val active=store?:BatteryWidgetStateStore(BatteryWidgetPreferences(app).records()) {records->
            BatteryWidgetPreferences(app).save(records)
            BatteryWidgetProvider.renderAll(app)
        }.also{store=it}
        return active.accept(intent)
    }
    fun release(){store?.close();store=null}
}
