package dev.hyperears.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import dev.hyperears.R

/** The launcher owns allocation; a foreign/unbound widget ID cannot change preferences. */
class BatteryWidgetConfigurationActivity:Activity() {
    private var dialog:AlertDialog?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        if(!BatteryWidgetProvider.owns(this,id)){finish();return}
        val prefs=BatteryWidgetPreferences(this)
        val records=BatteryWidgetRuntime.records(this).sortedBy{it.name}
        val fixed=prefs.fixedAddress(id)
        val targets=(records.map{it.address}+listOfNotNull(fixed)).distinct()
        val labels=arrayOf(getString(R.string.widget_auto))+targets.map {address->
            val name=records.firstOrNull{it.address==address}?.name?:getString(R.string.widget_fixed_unavailable)
            getString(R.string.widget_device_label,name,address.takeLast(5))
        }.toTypedArray()
        val selected=if(fixed==null)0 else targets.indexOf(fixed)+1
        dialog=AlertDialog.Builder(this).setTitle(R.string.widget_choose_device)
            .setSingleChoiceItems(labels,selected) {choice,index->
                if(!BatteryWidgetProvider.owns(this,id)){finish();return@setSingleChoiceItems}
                prefs.select(id,if(index==0)null else targets[index-1])
                BatteryWidgetProvider.reconcile(this,true)
                setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id))
                choice.dismiss();finish()
            }.setNegativeButton(android.R.string.cancel){_,_->finish()}.setOnCancelListener{finish()}.show()
    }
    override fun onDestroy(){dialog?.dismiss();dialog=null;super.onDestroy()}
}
