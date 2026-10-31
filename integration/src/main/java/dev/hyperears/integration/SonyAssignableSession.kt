package dev.hyperears.integration
import dev.hyperears.protocol.sony.SonyAssignableCodec as Wire
import dev.hyperears.protocol.sony.SonyAssignableV2Codec as V2
internal class SonyAssignableSession(private val v2:Boolean) {
    private var selected:Int?=null
    var state=SonyAssignableFeatureState();private set
    fun reads(capability:Boolean=false):List<ByteArray> {
        val type=selected?:return emptyList()
        return buildList {
            if(capability)add(Wire.bytes(0xF0,type))
            add(Wire.bytes(0xF2,type));add(Wire.bytes(0xF6,type))
            if(v2)add(Wire.bytes(0xFA,type))
        }
    }
    fun accept(payload:ByteArray):SonyDailyControlsSession.Update? {
        val discovery=if(v2)V2.discovery(payload) else Wire.discovery(false,payload)?.let{if(it)setOf(6)else emptySet()}
        discovery?.let { types ->
            // Prefer the advertised limitation-aware variant; never consume the other inquiry's reports.
            val next=listOf(14,3,6).firstOrNull{it in types}
            if(next==selected)return null
            selected=next;state=SonyAssignableFeatureState()
            return SonyDailyControlsSession.Update(listOf(state),emptyList())
        }
        val type=selected?:return null
        val definition=if(v2) V2.capability(payload,type)?.let { cap ->
            SonyAssignableDefinition(cap.bytes,cap.keys.map { key -> SonyAssignableKey(key.id,key.type,key.presets,
                key.actions.mapValues{(_,actions)->actions.map{SonyAssignableAction(it.id,it.default,it.functions)}}) },2,type)
        } else Wire.capability(payload)?.let { cap ->
            SonyAssignableDefinition(cap.bytes,cap.keys.map{SonyAssignableKey(it.id,it.type,it.presets)})
        }
        definition?.let {
            if(it!=state.definition)state=SonyAssignableFeatureState(it)
            return SonyDailyControlsSession.Update(listOf(state),reads())
        }
        val keys=state.definition?.keys?:return null
        val statuses=if(v2)V2.vector(payload,type,true)else Wire.vector(payload,true)
        statuses?.let { vector ->
            if(vector.size!=keys.size)return null
            state=state.copy(available=vector.map{it==0})
            return SonyDailyControlsSession.Update(listOf(state),emptyList())
        }
        val presets=if(v2)V2.vector(payload,type,false)else Wire.vector(payload,false)
        presets?.let { vector ->
            if(vector.size!=keys.size)return null
            val changed=vector!=state.current
            state=state.copy(current=vector,mappings=state.mappings.takeUnless{changed})
            return SonyDailyControlsSession.Update(listOf(state),if(v2&&changed)listOf(Wire.bytes(0xFA,type))else emptyList())
        }
        if(v2)V2.mappings(payload,type)?.let { report ->
            // A structurally valid partial report revokes old action evidence; defaults are not reports.
            state=state.copy(mappings=report.map{mapping->SonyAssignableMapping(mapping.preset,mapping.actions.map{SonyAssignablePair(it.first,it.second)})})
            return SonyDailyControlsSession.Update(listOf(state),emptyList())
        }
        return null
    }
    fun encode(request:SonyControlRequest):List<ByteArray> {
        if(!state.supports(request))return emptyList()
        val type=selected?:return emptyList()
        val body=when(request) {
            is SonyControlRequest.SetAssignablePreset -> {
                val values=request.expectedCurrent.toMutableList().apply{this[request.index]=request.preset}
                listOf(0xF8,type,values.size)+values
            }
            is SonyControlRequest.SetAssignableAction -> {
                if(!v2)return emptyList()
                val mappings=request.expectedMappings.map { mapping ->
                    if(mapping.preset!=request.expectedCurrent[request.index])mapping else mapping.copy(actions=mapping.actions.map { pair ->
                        if(pair.action==request.action)pair.copy(function=request.function)else pair
                    })
                }
                listOf(0xFC,type,mappings.size)+mappings.flatMap { mapping ->
                    listOf(mapping.preset,mapping.actions.size)+mapping.actions.flatMap{listOf(it.action,it.function)}
                }
            }
            else -> return emptyList()
        }
        return listOf(body.map(Int::toByte).toByteArray())+reads()
    }
}
