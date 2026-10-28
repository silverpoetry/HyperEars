package dev.hyperears.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import dev.hyperears.bridge.BluetoothDeviceSettings

/** User-triggered activity PendingIntent preserves the platform's foreground launch allowance. */
class BatteryWidgetClickActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        if(!BatteryWidgetProvider.owns(this,id)){finish();return}
        val fixed=BatteryWidgetPreferences(this).fixedAddress(id)
        val address=BatteryWidgetProjection.select(BatteryWidgetRuntime.records(this),fixed)?.address?:fixed
        if(address!=null)BluetoothDeviceSettings.open(this,address)
        else startActivity(Intent(this,BatteryWidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id))
        BatteryWidgetProvider.requestReplay(this)
        finish()
    }
}
