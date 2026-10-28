package dev.hyperears.hook
import dev.hyperears.integration.*
import dev.hyperears.protocol.samsung.SamsungFitTestCodec
internal object SettingsSamsungFitRows {
    fun add(host:SettingsPreferenceHost,state:EarbudState,address:String,add:(Any)->Unit,latest:()->EarbudState?,openDetails:()->Unit) {
        if(!SamsungDailySettingsPolicy.available(state,address))return
        val feature=state.features.get<SamsungDailySettingsFeatureState>()?:return
        if(!SamsungFitTestCodec.supported(feature.sourceModel))return
        val row=host.row("hyperears.samsung.fit","耳塞贴合度测试")
        host.onClick(row) {runCatching {
            val fresh=latest()?:return@runCatching
            val current=fresh.features.get<SamsungDailySettingsFeatureState>()?:return@runCatching
            if(SamsungDailySettingsPolicy.available(fresh,address) && current.model==feature.model && current.revision==feature.revision)openDetails()
        }}
        add(row)
    }
}
