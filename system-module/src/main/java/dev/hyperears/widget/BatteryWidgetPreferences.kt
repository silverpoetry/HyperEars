package dev.hyperears.widget

import android.content.Context
import androidx.core.content.edit
import dev.hyperears.integration.*
import org.json.JSONArray
import org.json.JSONObject

internal class BatteryWidgetPreferences(context:Context) {
    private val preferences=context.applicationContext.getSharedPreferences("battery_widgets",Context.MODE_PRIVATE)
    fun fixedAddress(id:Int):String?=BatteryWidgetProjection.address(preferences.getString("widget.$id",null))
    fun select(id:Int,address:String?){preferences.edit {if(address==null)remove("widget.$id") else putString("widget.$id",requireNotNull(BatteryWidgetProjection.address(address)))}}
    fun delete(id:Int){preferences.edit {remove("widget.$id")}}
    fun records():List<BatteryWidgetRecord> = runCatching {
        val array=JSONArray(preferences.getString("display","[]"))
        (0 until minOf(array.length(),BatteryWidgetProjection.MAX_DEVICES)).mapNotNull {i->runCatching record@{
            val o=array.getJSONObject(i);val address=BatteryWidgetProjection.address(o.getString("address"))?:return@record null
            fun reading(key:String):BatteryReading {val p=o.optInt(key,-1).takeIf{it in 0..100};return BatteryReading(p,p!=null&&o.optBoolean(key+"Charging"))}
            BatteryWidgetRecord(address,o.getString("name").take(128),EarbudBattery(reading("left"),reading("right"),reading("case"),reading("overall")),
                HeadsetFormFactor.valueOf(o.getString("form")),BatteryWidgetStatus.valueOf(o.getString("status")),o.optLong("recorded",0).takeIf{it>0})
        }.getOrNull()}
    }.getOrDefault(emptyList())
    fun save(records:Collection<BatteryWidgetRecord>) {
        val array=JSONArray()
        records.takeLastBounded().forEach {r->
            val o=JSONObject().put("address",r.address).put("name",r.name).put("form",r.formFactor.name).put("status",r.status.name)
            r.recordedAtMillis?.let{o.put("recorded",it)}
            listOf("left" to r.battery.left,"right" to r.battery.right,"case" to r.battery.case,"overall" to r.battery.overall).forEach{(key,b)->
                b.percent?.let{o.put(key,it);o.put(key+"Charging",b.charging)}
            }
            array.put(o)
        }
        preferences.edit {putString("display",array.toString())}
    }
    fun clearDisplay(){preferences.edit {remove("display")}}
    fun clearAll(){preferences.edit {clear()}}
    private fun Collection<BatteryWidgetRecord>.takeLastBounded()=toList().takeLast(BatteryWidgetProjection.MAX_DEVICES)
}
