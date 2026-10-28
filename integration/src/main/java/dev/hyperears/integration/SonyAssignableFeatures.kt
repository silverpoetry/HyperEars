package dev.hyperears.integration
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient

@Serializable data class SonyAssignableAction(val id:Int,val default:Int,val functions:List<Int>)
@Serializable data class SonyAssignablePair(val action:Int,val function:Int)
@Serializable data class SonyAssignableMapping(val preset:Int,val actions:List<SonyAssignablePair>)
@Serializable data class SonyAssignableKey(val id:Int,val type:Int,val presets:List<Int>,val actions:Map<Int,List<SonyAssignableAction>> = emptyMap())
@Serializable data class SonyAssignableDefinition(val capability:List<Int>,val keys:List<SonyAssignableKey>,val version:Int=1,val inquiredType:Int=6)
@Serializable @SerialName("sony.assignable")
data class SonyAssignableFeatureState(val definition:SonyAssignableDefinition?=null,val current:List<Int>?=null,val available:List<Boolean>?=null,val mappings:List<SonyAssignableMapping>?=null):DeviceFeatureState {
    @Transient override val featureId=FEATURE_ID
    val hasSettings:Boolean get() {
        val keys=definition?.keys?:return false
        val values=current?:return false
        return keys.isNotEmpty() && values.size==keys.size && values.indices.all {values[it] in keys[it].presets}
    }
    val completeMappings:Boolean get() {
        val definition=definition?:return false
        val keys=definition.keys
        val values=current?:return false
        val reported=mappings?:return false
        if(definition.version!=2||!hasSettings||reported.size!=keys.size)return false
        return keys.indices.all { index ->
            val expected=keys[index].actions[values[index]]?:return false
            val actual=reported[index]
            actual.preset==values[index] && actual.actions.size==expected.size && actual.actions.isNotEmpty() &&
                actual.actions.map{it.action}.toSet()==expected.map{it.id}.toSet() && actual.actions.all { pair ->
                    pair.function in expected.single{it.id==pair.action}.functions
                }
        }
    }
    fun supports(request:SonyControlRequest):Boolean {
        if(!hasSettings)return false
        val def=definition?:return false
        return when(request) {
            is SonyControlRequest.SetAssignablePreset -> request.expected==def && request.expectedCurrent==current &&
                request.index in def.keys.indices && available?.getOrNull(request.index)==true && request.preset in def.keys[request.index].presets && request.preset!=current?.get(request.index)
            is SonyControlRequest.SetAssignableAction -> completeMappings && request.expected==def && request.expectedCurrent==current && request.expectedMappings==mappings &&
                request.index in def.keys.indices && available?.getOrNull(request.index)==true &&
                def.keys[request.index].actions[current!![request.index]]?.singleOrNull{it.id==request.action}?.functions?.contains(request.function)==true &&
                mappings!![request.index].actions.singleOrNull{it.action==request.action}?.function!=request.function
            else -> false
        }
    }
    companion object {const val FEATURE_ID="sony.assignable"}
}
