package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseRsV2IdentityCodec as Identity
import dev.hyperears.protocol.rose.RoseRsV2WireCodec as Wire
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient

@Serializable @SerialName("rose.rsv2_settings")
data class RoseRsV2SettingsFeatureState(val model: String, val eq: Int? = null, val bass: Int? = null,
    val game: Boolean? = null, val multipoint: Boolean? = null, val audio: Int? = null,
    val touchEnabled: Boolean? = null, val touch: List<Int>? = null, val prompt: Int? = null,
    val findLeft: Boolean? = null, val findRight: Boolean? = null,
    val gain: Int? = null, val hearingProtection: Int? = null) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    val hasSettings: Boolean get() = listOf(eq,bass,game,multipoint,audio,touchEnabled,touch,prompt,findLeft,findRight,gain,hearingProtection).any { it != null }
    val profile: Identity.Model? get() = Identity.Model.entries.singleOrNull { it.name == model }
    fun supports(request: RoseRsV2ControlRequest): Boolean {
        val p=profile ?: return false
        return when(request) {
            is RoseRsV2ControlRequest.SetGain -> p.gainSupported && gain != null && request.expectedModel == model && request.level in 0..2
            is RoseRsV2ControlRequest.SetHearingProtection -> p.hearingProtectionSupported && hearingProtection != null && request.expectedModel == model && request.level in 0..6
            is RoseRsV2ControlRequest.SetEqualizer -> eq != null && request.preset in p.eqPresets
            is RoseRsV2ControlRequest.SetBass -> bass != null && request.level in p.bassLevels
            is RoseRsV2ControlRequest.SetGame -> game != null
            is RoseRsV2ControlRequest.SetMultipoint -> multipoint != null
            is RoseRsV2ControlRequest.SetAudio -> p.audio && audio != null && request.mode in p.audioModes
            is RoseRsV2ControlRequest.SetTouchEnabled -> p.touchMaster && touchEnabled != null
            is RoseRsV2ControlRequest.SetTouch -> touch != null && request.slot in SLOTS && request.action in p.touchActions
            is RoseRsV2ControlRequest.SetPrompt -> prompt != null && request.level in 1..5
            is RoseRsV2ControlRequest.Find -> p.findSupported && (findLeft != null || findRight != null) && request.target in 1..4
        }
    }
    companion object { const val FEATURE_ID="rose.rsv2_settings"; val SLOTS=listOf(1,2,3,4,17,18,19,20) }
}
@Serializable sealed interface RoseRsV2ControlRequest:ControlRequest {
    @Serializable @SerialName("rose.rsv2_gain") data class SetGain(val expectedModel:String,val level:Int):RoseRsV2ControlRequest
    @Serializable @SerialName("rose.rsv2_hearing_protection") data class SetHearingProtection(val expectedModel:String,val level:Int):RoseRsV2ControlRequest
    @Serializable @SerialName("rose.rsv2_eq") data class SetEqualizer(val preset:Int):RoseRsV2ControlRequest
    @Serializable @SerialName("rose.rsv2_bass") data class SetBass(val level:Int):RoseRsV2ControlRequest
    @Serializable @SerialName("rose.rsv2_game") data class SetGame(val enabled:Boolean):RoseRsV2ControlRequest
    @Serializable @SerialName("rose.rsv2_multipoint") data class SetMultipoint(val enabled:Boolean):RoseRsV2ControlRequest
    @Serializable @SerialName("rose.rsv2_audio") data class SetAudio(val mode:Int):RoseRsV2ControlRequest
    @Serializable @SerialName("rose.rsv2_touch_enable") data class SetTouchEnabled(val enabled:Boolean):RoseRsV2ControlRequest
    @Serializable @SerialName("rose.rsv2_touch") data class SetTouch(val slot:Int,val action:Int):RoseRsV2ControlRequest
    @Serializable @SerialName("rose.rsv2_prompt") data class SetPrompt(val level:Int):RoseRsV2ControlRequest
    @Serializable @SerialName("rose.rsv2_find") data class Find(val target:Int):RoseRsV2ControlRequest
}
object RoseRsV2PresentationIds { val SETTINGS=MiLinkCardPresentationId("rose-rsv2-settings") }

class RoseRsV2Adapter(private val model:Identity.Model):StandardEarbudAdapter() {
    override val id=model.id
    override val displayName=model.label
    override val resolution=AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired=true
    override val transportReadiness=TransportReadiness.PROTOCOL_HANDSHAKE
    override val formFactor get() = if (model.aggregateBattery) HeadsetFormFactor.HEADPHONES else super.formFactor
    override val transports=listOf(RfcommEndpointSpec.ServiceUuid(Identity.SERVICE_UUID,"rose-rsv2-rfcomm"))
    override val controlApps=listOf(ControlAppCatalog.roseLink)
    override val featureStateContract=StandardDeviceFeatureStateContract.extending { _,state -> state is RoseRsV2SettingsFeatureState && state.model==model.name }
    override val controlRequestContract=StandardControlRequestContract.extending { adapter,request ->
        request is RoseRsV2ControlRequest && adapter.runtimeState().features.get<RoseRsV2SettingsFeatureState>()?.supports(request)==true
    }
    override fun matches(identity:EarbudIdentity)=identity.standardHeadset && !identity.nativeSystemEarbud && dev.hyperears.protocol.rose.RoseManufacturerIdentityCodec.modelId(identity.manufacturerData)==model.id
    override val miLinkCardPresentationId:MiLinkCardPresentationId? get()=RoseRsV2PresentationIds.SETTINGS.takeIf { runtimeState().features.get<RoseRsV2SettingsFeatureState>()?.hasSettings == true || (effectiveCapabilities().noiseControl && runtimeState().noiseMode != null) }
    override fun createProtocolSession():ProtocolSession=RoseRsV2Session(model)
    override fun onProtocolReset() { removeFeatureState(RoseRsV2SettingsFeatureState.FEATURE_ID) }
}
internal class RoseRsV2Session(private val model:Identity.Model):ProtocolSession {
    private val decoder=Wire.Decoder(model)
    private var settings=RoseRsV2SettingsFeatureState(model.name)
    private var seq=0
    private var handshake=false
    private var noiseConfirmed=false
    private fun command(vararg payload:Int)=Wire.command(seq.also { seq=(seq+1) and 255 },payload.map(Int::toByte).toByteArray())
    override fun initialReadCommands()=listOf(Wire.query(seq.also {seq=(seq+1) and 255},model))
    override fun encode(request:ControlRequest):List<ByteArray> {
        if(request is RoseRsV2ControlRequest && !settings.supports(request)) return emptyList()
        return when(request) {
            StandardControlRequest.Refresh->initialReadCommands()
            is StandardControlRequest.SetNoiseMode->{
                val value=when(request.mode){NoiseMode.ANC->1;NoiseMode.OFF->2;NoiseMode.TRANSPARENCY->3;NoiseMode.WIND->4}
                if(noiseConfirmed && value in model.noiseValues) listOf(command(9,value)) else emptyList()
            }
            is RoseRsV2ControlRequest.SetGain->listOf(command(0x45,request.level))
            is RoseRsV2ControlRequest.SetHearingProtection->listOf(command(0x46,request.level))
            is RoseRsV2ControlRequest.SetEqualizer->listOf(command(0x2A,when(request.preset){0->1;1->0;else->request.preset}))
            is RoseRsV2ControlRequest.SetBass->listOf(command(0x2A,5,request.level))
            is RoseRsV2ControlRequest.SetGame->listOf(command(0x0E,if(request.enabled)1 else 0))
            is RoseRsV2ControlRequest.SetMultipoint->listOf(command(0x32,if(request.enabled)1 else 0))
            is RoseRsV2ControlRequest.SetAudio->listOf(command(0x2B,request.mode))
            is RoseRsV2ControlRequest.SetTouchEnabled->listOf(command(7,if(request.enabled)1 else 0))
            is RoseRsV2ControlRequest.SetTouch->listOf(command(1,request.slot,request.action))
            is RoseRsV2ControlRequest.SetPrompt->listOf(command(0x2E,request.level))
            is RoseRsV2ControlRequest.Find->listOf(command(0x2F,request.target))
            else->emptyList()
        }
    }
    override fun readback(request:ControlRequest):List<ByteArray> {
        val feature=when(request) {
            is StandardControlRequest.SetNoiseMode->9
            is RoseRsV2ControlRequest.SetGain->0x45
            is RoseRsV2ControlRequest.SetHearingProtection->0x46
            is RoseRsV2ControlRequest.SetEqualizer,is RoseRsV2ControlRequest.SetBass->0x2A
            is RoseRsV2ControlRequest.SetGame->0x0E
            is RoseRsV2ControlRequest.SetMultipoint->0x32
            is RoseRsV2ControlRequest.SetAudio->0x2B
            is RoseRsV2ControlRequest.SetTouchEnabled->7
            is RoseRsV2ControlRequest.SetTouch->1
            is RoseRsV2ControlRequest.SetPrompt->0x2E
            is RoseRsV2ControlRequest.Find->0x2F
            else->return emptyList()
        };return listOf(command(0xFA,feature))
    }
    override fun offer(bytes:ByteArray):List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { report ->
            if(!handshake){handshake=true;add(ProtocolEvent.HandshakeAccepted)}
            when(report) {
                is Wire.State.Firmware->Unit
                is Wire.State.Battery->{
                    fun reading(raw:Int)=BatteryReading((raw and 127).takeIf{it<=100},(raw and 127)<=100 && (raw and 128)!=0)
                    val battery=if(model.aggregateBattery)
                        EarbudBattery(overall=reading(report.case)) else EarbudBattery(left=reading(report.left),right=reading(report.right),case=if(model.caseBattery) reading(report.case) else BatteryReading(null,false))
                    add(ProtocolEvent.CapabilitiesIdentified(battery=true));add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(battery)))
                }
                is Wire.State.Touch->{settings=settings.copy(touch=report.actions);add(ProtocolEvent.FeatureStateChanged(settings))}
                is Wire.State.Value->{
                    if(report.type==9){noiseConfirmed=true;add(ProtocolEvent.CapabilitiesIdentified(false,model.noiseValues.map { when(it){1->NoiseMode.ANC;2->NoiseMode.OFF;3->NoiseMode.TRANSPARENCY;else->NoiseMode.WIND} }.toSet()));add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(when(report.value){1->NoiseMode.ANC;2->NoiseMode.OFF;3->NoiseMode.TRANSPARENCY;else->NoiseMode.WIND})))}
                    else {settings=when(report.type){
                        0x2A->settings.copy(eq=report.value);0x105->settings.copy(bass=report.value)
                        0x0E->settings.copy(game=report.value==1);0x32->settings.copy(multipoint=report.value==1)
                        0x2B->settings.copy(audio=report.value);7->settings.copy(touchEnabled=report.value==1)
                        0x2E->settings.copy(prompt=report.value)
                        0x45->settings.copy(gain=report.value);0x46->settings.copy(hearingProtection=report.value)
                        0x2F->when(report.value){1->settings.copy(findLeft=true);2->settings.copy(findRight=true);3->settings.copy(findLeft=true,findRight=true);4->settings.copy(findLeft=false,findRight=false);5->settings.copy(findLeft=false);else->settings.copy(findRight=false)}
                        else->settings};add(ProtocolEvent.FeatureStateChanged(settings))}
                }
            }
        }
    }
    override fun reset(){decoder.reset();settings=RoseRsV2SettingsFeatureState(model.name);seq=0;handshake=false;noiseConfirmed=false}
}
