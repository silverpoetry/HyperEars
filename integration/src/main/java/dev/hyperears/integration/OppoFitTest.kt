package dev.hyperears.integration
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient
import dev.hyperears.protocol.oppo.OppoFitCodec as Wire
import dev.hyperears.protocol.oppo.OppoWireCodec
@Serializable @SerialName("oppo.fit")
data class OppoFitFeatureState(val productId:Int,val revision:Long,val advertised:Boolean=false,val leftWorn:Boolean?=null,val rightWorn:Boolean?=null,val resultLease:Long?=null,val leftResult:Int?=null,val rightResult:Int?=null,val error:Int?=null,val timedOut:Boolean=false):DeviceFeatureState {
    @Transient override val featureId=FEATURE_ID
    val ready get()=advertised && leftWorn==true && rightWorn==true
    companion object{const val FEATURE_ID="oppo.fit"}
}
@Serializable sealed interface OppoFitControlRequest:ControlRequest {
    @Serializable @SerialName("oppo.fit.start") data class Start(val expected:OppoFitFeatureState,val lease:Long):OppoFitControlRequest
    @Serializable @SerialName("oppo.fit.stop") data class Stop(val productId:Int,val lease:Long):OppoFitControlRequest
}
internal object OppoFitCatalog {
    // Leaf929d813f device_models.json executable fitDetection + supportSpp intersection.
    val products=setOf(0x067014,0x06D810,0x06C410,0x066414,0x066814,0x061410,0x062010,0x060C14,0x062014,0x063014,0x063414,0x065C10,0x066010,0x061014,0x064010,0x063C10,0x050C10,0x050C14,0x063C14,0x066810,0x067410,0x064014,0x067C10,0x064414,0x069C10,0x068C10,0x06C010,0x06BC10,0x065414,0x06EC10,0x067814)
}
internal class OppoFitSession {
    var state:OppoFitFeatureState?=null;private set
    private var revision=0L
    private var advertised=false
    private var lease:Long?=null
    private val commands=mutableListOf<ByteArray>()
    fun identify(id:Int):OppoFitFeatureState? {commands+=release();state=if(id in OppoFitCatalog.products)OppoFitFeatureState(id,++revision,advertised)else null;return state}
    fun queries()=if(state!=null)listOf(Wire.queryWear)else emptyList()
    fun supported(request:OppoFitControlRequest)=when(request){
        is OppoFitControlRequest.Start->state==request.expected && state?.ready==true && lease==null && request.lease>0
        is OppoFitControlRequest.Stop->state?.productId==request.productId && lease==request.lease
    }
    fun encode(request:OppoFitControlRequest):List<ByteArray> {
        if(!supported(request))return emptyList()
        return when(request){is OppoFitControlRequest.Start->{lease=request.lease;listOf(Wire.start)};is OppoFitControlRequest.Stop->release()}
    }
    val activeLease:Long? get()=lease
    fun release():List<ByteArray> {if(lease==null)return emptyList();lease=null;return listOf(Wire.stop)}
    fun expire(expectedLease:Long):List<ByteArray> {
        if(lease!=expectedLease)return emptyList()
        state=state?.copy(revision=++revision,resultLease=expectedLease,leftResult=null,rightResult=null,error=null,timedOut=true)
        return release()
    }
    fun drain()=commands.toList().also{commands.clear()}
    fun offer(frame:OppoWireCodec.Frame):OppoFitFeatureState? {
        OppoWireCodec.parseNotificationSupport(frame)?.let {
            if(frame.payload.size!=it.size+2 || it.distinct().size!=it.size)return null
            advertised=4.toByte() in it
            state=state?.copy(advertised=advertised,revision=++revision)
            if(!advertised)commands+=release()
            return state
        }
        val old=state?:return null
        Wire.wear(frame)?.let {wear->
            state=old.copy(leftWorn=wear.first,rightWorn=wear.second,revision=++revision)
            if(!wear.first||!wear.second)commands+=release()
            return state
        }
        val active=lease?:return null
        Wire.result(frame)?.let {result->
            state=old.copy(revision=++revision,resultLease=active,leftResult=result.first,rightResult=result.second,error=null,timedOut=false)
            commands+=release();return state
        }
        Wire.error(frame)?.let {error->
            state=old.copy(revision=++revision,resultLease=active,leftResult=null,rightResult=null,error=error,timedOut=false)
            commands+=release();return state
        }
        return null
    }
    fun reset(){state=null;advertised=false;lease=null;commands.clear();revision++}
}
