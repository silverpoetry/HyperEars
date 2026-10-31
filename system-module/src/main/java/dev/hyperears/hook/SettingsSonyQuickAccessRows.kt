package dev.hyperears.hook
import dev.hyperears.integration.*

internal object SettingsSonyQuickAccessRows {
    fun add(host:SettingsPreferenceHost,state:EarbudState,address:String,add:(Any)->Unit,
        latest:()->EarbudState?,send:(ControlRequest)->Unit) {
        val feature=state.features.get<SonyQuickAccessFeatureState>()?.takeIf{it.hasSettings} ?: return
        val definition=feature.definition ?: return
        val values=feature.current ?: return
        definition.actions.forEachIndexed { index,action ->
            val options=SonyQuickAccessServices.candidates(action,values[index])
            val row=host.choice("hyperears.sony.quick_access.${action.id}",
                "Quick Access · "+SettingsSonyAssignableRows.actionLabel(action.id),
                options.map{SonyQuickAccessServices.names[it] ?: "服务 $it"},options.map(Int::toString),
                values[index].toString(),SonyCardPolicy.ready(state,address) && feature.available==true) {raw->
                val service=raw.toIntOrNull() ?: return@choice
                val current=latest() ?: return@choice
                SonyCardPolicy.request(current,address,
                    SonyControlRequest.SetQuickAccess(definition,values,index,service))?.let(send)
            }
            add(row)
        }
    }
}
