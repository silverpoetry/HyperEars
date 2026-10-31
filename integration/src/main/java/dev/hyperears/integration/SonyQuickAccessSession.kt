package dev.hyperears.integration
import dev.hyperears.protocol.sony.SonyQuickAccessCodec as Wire

internal class SonyQuickAccessSession(private val v2:Boolean) {
    private var supported=false
    var state=SonyQuickAccessFeatureState();private set
    fun reads(capability:Boolean=false):List<ByteArray> = if(!supported)emptyList() else buildList {
        if(capability || state.definition==null)add(Wire.bytes(0xF0,13))
        add(Wire.bytes(0xF2,13));add(Wire.bytes(0xF6,13))
    }
    fun accept(payload:ByteArray):SonyDailyControlsSession.Update? {
        if(!v2)return null
        Wire.discovery(payload)?.let {next->
            if(next==supported)return null
            supported=next;state=SonyQuickAccessFeatureState()
            return SonyDailyControlsSession.Update(listOf(state),emptyList())
        }
        if(!supported)return null
        Wire.capability(payload)?.let {cap->
            val definition=SonyQuickAccessDefinition(cap.key,cap.type,cap.actions.map{SonyQuickAccessAction(it.id,it.default,it.services)})
            if(state.definition==definition)return null
            state=SonyQuickAccessFeatureState(definition)
            return SonyDailyControlsSession.Update(listOf(state),reads())
        }
        if(state.definition==null)return null
        Wire.values(payload)?.let {values->
            state=state.copy(current=values.takeIf{it.size==state.definition!!.actions.size})
            return SonyDailyControlsSession.Update(listOf(state),emptyList())
        }
        Wire.availability(payload)?.let {available->
            state=state.copy(available=available)
            return SonyDailyControlsSession.Update(listOf(state),emptyList())
        }
        return null
    }
    fun encode(request:SonyControlRequest):List<ByteArray> {
        if(request !is SonyControlRequest.SetQuickAccess || !state.supports(request))return emptyList()
        val values=request.baseline.toMutableList().apply{this[request.index]=request.service}
        return listOf((listOf(0xF8,13,values.size)+values).map(Int::toByte).toByteArray())+reads()
    }
}
