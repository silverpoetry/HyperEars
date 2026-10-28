package dev.hyperears.integration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import dev.hyperears.protocol.sony.SonyVirtualSoundCodec as Wire

@Serializable data class SonyVirtualSoundDefinition(val version:Int=1,val types:List<Int>)
@Serializable @SerialName("sony.virtual_sound")
data class SonyVirtualSoundFeatureState(val definition:SonyVirtualSoundDefinition?=null,
    val values:Map<Int,Int> = emptyMap(),val awaitingReadback:Boolean=false):DeviceFeatureState {
    @Transient override val featureId=FEATURE_ID
    val hasSettings get()=definition?.let{it.version==1 && it.types.isNotEmpty() && it.types==it.types.distinct().sorted() &&
        it.types.all{type->type in 1..2} && values.keys==it.types.toSet() && values.all{(type,value)->value in Wire.values(type)}}==true
    fun supports(request:SonyControlRequest)=request is SonyControlRequest.SetVirtualSound && hasSettings && !awaitingReadback &&
        request.expected==definition && request.expectedValues==values && request.type in values &&
        request.value in Wire.values(request.type) && request.value!=values[request.type]
    companion object {const val FEATURE_ID="sony.virtual_sound"}
}
internal class SonyVirtualSoundSession(private val v2:Boolean) {
    private var types=emptyList<Int>()
    private var awaiting=emptySet<Int>()
    var state=SonyVirtualSoundFeatureState();private set
    fun accept(payload:ByteArray):SonyDailyControlsSession.Update? {
        if(v2)return null
        Wire.functions(payload)?.let{next ->
            if(types==next)return null
            types=next;awaiting=emptySet()
            state=SonyVirtualSoundFeatureState(next.takeIf{it.isNotEmpty()}?.let{SonyVirtualSoundDefinition(types=it)})
            return SonyDailyControlsSession.Update(listOf(state),reads())
        }
        val (type,value)=Wire.report(payload)?:return null
        if(type !in types)return null
        awaiting=awaiting-type
        state=state.copy(values=state.values+(type to value),awaitingReadback=awaiting.isNotEmpty())
        return SonyDailyControlsSession.Update(listOf(state),emptyList())
    }
    fun reads()=types.map(Wire::read)
    fun encode(request:SonyControlRequest.SetVirtualSound)=if(!v2 && state.supports(request))
        listOf(Wire.write(request.type,request.value))+reads()else emptyList()
    /** Only successful transport write arms readback serialization; encoding is side-effect free. */
    fun written(request:SonyControlRequest.SetVirtualSound) {
        if(state.supports(request)) {awaiting=types.toSet();state=state.copy(awaitingReadback=true)}
    }
}
