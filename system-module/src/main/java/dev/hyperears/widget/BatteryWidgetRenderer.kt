package dev.hyperears.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import dev.hyperears.R
import dev.hyperears.integration.BatteryReading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object BatteryWidgetRenderer {
    fun layout(options:Bundle):Int = if(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,0)>=250)
        R.layout.battery_widget_wide else R.layout.battery_widget_compact
    fun render(context:Context,id:Int,options:Bundle,record:BatteryWidgetRecord?,fixed:String?):RemoteViews {
        val views=RemoteViews(context.packageName,layout(options))
        val compact=layout(options)==R.layout.battery_widget_compact
        val short=compact && options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,100) in 1..110
        if(short) {
            views.setViewVisibility(R.id.widget_status,View.GONE)
            views.setViewVisibility(R.id.widget_configure,View.GONE)
            fun dp(value:Int)=(value*context.resources.displayMetrics.density).toInt()
            views.setViewPadding(R.id.widget_body,dp(8),dp(4),dp(8),dp(4))
            views.setViewPadding(R.id.widget_batteries,0,dp(2),0,0)
        }
        views.setTextViewText(R.id.widget_name,record?.name?:context.getString(if(fixed==null)R.string.widget_waiting else R.string.widget_fixed_unavailable))
        val status=when(record?.status) {
            BatteryWidgetStatus.CONNECTED->R.string.widget_recorded_connected
            BatteryWidgetStatus.DISCONNECTED->R.string.widget_recorded_disconnected
            BatteryWidgetStatus.EXTERNAL->R.string.widget_recorded_external
            BatteryWidgetStatus.SYSTEM->R.string.widget_system_owned
            else->R.string.widget_unconfirmed
        }
        views.setTextViewText(R.id.widget_status,context.getString(status))
        views.setTextViewText(R.id.widget_time,record?.recordedAtMillis?.let{
            context.getString(R.string.widget_recorded_time,SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(Date(it)))
        }?:context.getString(R.string.widget_time_unknown))
        val overall=record?.battery?.overall?.available==true && !record.battery.left.available && !record.battery.right.available
        val slots=if(overall)listOf(Triple(R.id.widget_left, R.string.widget_overall,record!!.battery.overall),Triple(R.id.widget_case,R.string.widget_case,record.battery.case))
            else listOf(Triple(R.id.widget_left,R.string.widget_left,record?.battery?.left),
                Triple(R.id.widget_right,R.string.widget_right,record?.battery?.right),Triple(R.id.widget_case,R.string.widget_case,record?.battery?.case))
        views.setViewVisibility(R.id.widget_right,if(overall)View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_case,if(overall && record?.battery?.case?.available!=true)View.GONE else View.VISIBLE)
        slots.forEach{(view,label,battery)->
            val compactLabel=if(compact)when(label){
                R.string.widget_left->R.string.widget_left_short
                R.string.widget_right->R.string.widget_right_short
                R.string.widget_case->R.string.widget_case_short
                else->label
            }else label
            views.setTextViewText(view,reading(context,context.getString(compactLabel),battery,compact))
        }
        views.setOnClickPendingIntent(R.id.widget_configure,pending(context,id,true))
        views.setOnClickPendingIntent(R.id.widget_body,pending(context,id,false))
        views.setContentDescription(R.id.widget_body,context.getString(R.string.widget_open_settings))
        return views
    }
    private fun reading(context:Context,label:String,battery:BatteryReading?,compact:Boolean):String {
        val value=battery?.percent?.let{"$it%"}?:context.getString(R.string.widget_unknown)
        return context.getString(if(compact)R.string.widget_component_compact else R.string.widget_component_value,label,value,if(battery?.charging==true&&battery.available)context.getString(R.string.widget_charging)else "")
    }
    internal fun pending(context:Context,id:Int,configure:Boolean):PendingIntent {
        val target=if(configure)BatteryWidgetConfigurationActivity::class.java else BatteryWidgetClickActivity::class.java
        val intent=Intent(context,target).setData("hyperears://battery-widget/$id/${if(configure)"configure" else "open"}".toUri())
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
        return PendingIntent.getActivity(context,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
