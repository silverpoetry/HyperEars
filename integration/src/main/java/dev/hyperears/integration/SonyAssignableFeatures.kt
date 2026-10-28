package dev.hyperears.integration
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient

@Serializable data class SonyAssignableKey(val id:Int,val type:Int,val presets:List<Int>)
@Serializable data class SonyAssignableDefinition(val capability:List<Int>,val keys:List<SonyAssignableKey>,val version:Int=1)
@Serializable @SerialName("sony.assignable")
data class SonyAssignableFeatureState(val definition:SonyAssignableDefinition?=null,val current:List<Int>?=null,val available:List<Boolean>?=null):DeviceFeatureState {
    @Transient override val featureId=FEATURE_ID
    val hasSettings:Boolean get() {
        val keys=definition?.keys?:return false
        val values=current?:return false
        return keys.isNotEmpty() && values.size==keys.size && values.indices.all {values[it] in keys[it].presets}
    }
    fun supports(request:SonyControlRequest):Boolean {
        if(request !is SonyControlRequest.SetAssignablePreset||!hasSettings||request.expected!=definition||request.expectedCurrent!=current)return false
        val keys=definition?.keys?:return false
        return request.index in keys.indices && available?.getOrNull(request.index)==true && request.preset in keys[request.index].presets && request.preset!=current?.get(request.index)
    }
    companion object {const val FEATURE_ID="sony.assignable"}
}
