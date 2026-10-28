package dev.hyperears.integration
import dev.hyperears.protocol.sony.SonyAssignableCodec as Wire
internal class SonyAssignableSession(private val v2:Boolean) {
    private var discovered=false
    var state=SonyAssignableFeatureState();private set
    fun reads(capability:Boolean=false)=if(!discovered)emptyList()else buildList {
        if(capability)add(Wire.bytes(0xF0,6))
        add(Wire.bytes(0xF2,6));add(Wire.bytes(0xF6,6))
    }
    fun accept(payload:ByteArray):SonyDailyControlsSession.Update? {
        if(v2)return null
        Wire.discovery(false,payload)?.let { next ->
            if(next==discovered)return null
            discovered=next;state=SonyAssignableFeatureState()
            return SonyDailyControlsSession.Update(listOf(state),emptyList())
        }
        if(!discovered)return null
        Wire.capability(payload)?.let { cap ->
            val definition=SonyAssignableDefinition(cap.bytes,cap.keys.map{SonyAssignableKey(it.id,it.type,it.presets)})
            if(definition!=state.definition)state=SonyAssignableFeatureState(definition)
            return SonyDailyControlsSession.Update(listOf(state),reads())
        }
        val keys=state.definition?.keys?:return null
        Wire.vector(payload,true)?.let { vector ->
            if(vector.size!=keys.size)return null
            state=state.copy(available=vector.map{it==0})
            return SonyDailyControlsSession.Update(listOf(state),emptyList())
        }
        Wire.vector(payload,false)?.let { vector ->
            if(vector.size!=keys.size)return null
            state=state.copy(current=vector)
            return SonyDailyControlsSession.Update(listOf(state),emptyList())
        }
        return null
    }
    fun encode(request:SonyControlRequest.SetAssignablePreset):List<ByteArray> {
        if(!state.supports(request))return emptyList()
        val values=request.expectedCurrent.toMutableList().apply{this[request.index]=request.preset}
        return listOf((listOf(0xF8,6,values.size)+values).map(Int::toByte).toByteArray())+reads()
    }
}
