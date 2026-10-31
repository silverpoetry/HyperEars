package dev.hyperears.hook
import dev.hyperears.integration.*
internal object NativeHeadsetPageModel {
    fun tozo(state:EarbudState)=state.adapter?.id=="tozo-nc20-pro"
    fun eligible(state:EarbudState)=tozo(state)&&state.sessionActive
    fun modes(state:EarbudState):List<NoiseMode> = emptyList()
    fun ready(state:EarbudState,address:String)=false
    fun noiseRequest(state:EarbudState,address:String,mode:NoiseMode):StandardControlRequest.SetNoiseMode?=null
    fun support(address:String,state:EarbudState)="$address,${"0".repeat(24)}"
    fun anc(state:EarbudState):String?=null
    fun battery(state:EarbudState):String {
        fun encode(r:BatteryReading)=if(!r.available)255 else requireNotNull(r.percent) or if(r.charging)128 else 0
        return listOf(state.battery.left,state.battery.right,state.battery.case).joinToString(",") {encode(it).toString()}
    }
    fun artwork(state:EarbudState)=HeadsetArtworkId("headset_tozo_nc20_pro")
}
