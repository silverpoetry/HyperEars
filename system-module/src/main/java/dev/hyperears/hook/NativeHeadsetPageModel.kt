package dev.hyperears.hook
import dev.hyperears.integration.*
internal object NativeHeadsetPageModel {
    fun rayneo(state:EarbudState)=state.adapter?.id=="rayneo-x3-pro"
    fun eligible(state:EarbudState)=rayneo(state)&&state.sessionActive
    fun modes(state:EarbudState):List<NoiseMode> = emptyList()
    fun ready(state:EarbudState,address:String)=false
    fun noiseRequest(state:EarbudState,address:String,mode:NoiseMode):StandardControlRequest.SetNoiseMode?=null
    fun support(address:String,state:EarbudState)="$address,${"0".repeat(24)}"
    fun anc(state:EarbudState):String?=null
    fun battery(state:EarbudState):String {
        val reading=state.battery.overall
        val encoded=if(!reading.available)255 else requireNotNull(reading.percent) or if(reading.charging)128 else 0
        return "255,$encoded,255"
    }
    fun artwork(state:EarbudState)=requireNotNull(state.adapter).artwork.headset
}
