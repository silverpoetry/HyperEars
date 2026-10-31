package dev.hyperears.integration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import dev.hyperears.protocol.sony.SonyVoiceGuidanceCodec as Wire

@Serializable data class SonyVoiceGuidanceDefinition(val version:Int=1,val table:Int=2,val inquiry:Int=1,val setting:Int=1)
@Serializable @SerialName("sony.voice_guidance")
data class SonyVoiceGuidanceFeatureState(val definition:SonyVoiceGuidanceDefinition?=null,val enabled:Boolean?=null):DeviceFeatureState {
    @Transient override val featureId=FEATURE_ID
    val hasSettings get()=definition==SonyVoiceGuidanceDefinition() && enabled!=null
    fun supports(request:SonyControlRequest)=request is SonyControlRequest.SetVoiceGuidance && hasSettings &&
        request.expected==definition && request.expectedCurrent==enabled && request.enabled!=enabled
    companion object {const val FEATURE_ID="sony.voice_guidance"}
}
internal class SonyVoiceGuidanceSession(private val v2:Boolean) {
    private var discovered=false
    var state=SonyVoiceGuidanceFeatureState();private set
    fun functions(payload:ByteArray):Boolean {
        if(v2)return false
        val next=Wire.function(payload)?:return false
        if(discovered==next)return false
        discovered=next
        state=SonyVoiceGuidanceFeatureState()
        return true
    }
    fun accept(payload:ByteArray):SonyVoiceGuidanceFeatureState? {
        if(v2 || !discovered)return null
        val enabled=Wire.report(payload)?:return null
        state=SonyVoiceGuidanceFeatureState(SonyVoiceGuidanceDefinition(),enabled)
        return state
    }
    fun reads()=if(discovered && !v2)listOf(Wire.read())else emptyList()
    fun encode(request:SonyControlRequest.SetVoiceGuidance):List<ByteArray> =
        if(!v2 && discovered && state.supports(request))listOf(Wire.write(request.enabled),Wire.read())else emptyList()
}
