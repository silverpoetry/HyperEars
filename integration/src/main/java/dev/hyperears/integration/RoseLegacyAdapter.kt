package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseLegacyIdentityCodec as Identity
import dev.hyperears.protocol.rose.RoseLegacyWireCodec as Wire
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient

@Serializable enum class RoseLegacySetting { EQ,GAME,AUDIO,TOUCH,PROMPT,LANGUAGE,WEAR,SPATIAL,ANC_LEVEL,TRANSPARENCY_LEVEL }
@Serializable @SerialName("rose.legacy.settings")
data class RoseLegacySettingsFeatureState(val model:String,val values:Map<RoseLegacySetting,Int> = emptyMap(),val touch:List<Int>?=null):DeviceFeatureState {
    @Transient override val featureId=FEATURE_ID
    val hasSettings get()=values.isNotEmpty() || touch!=null
    fun supports(request:RoseLegacyControlRequest.SetTouch)=request.expectedModel==model && touch!=null && request.expectedMapping==touch &&
        request.slot in RoseLegacyTouch.SLOTS && request.action in RoseLegacyTouch.choices(profile) &&
        RoseLegacyTouch.action(touch,RoseLegacyTouch.SLOTS.indexOf(request.slot))!=request.action
    val profile get()=Identity.Model.entries.singleOrNull { it.name==model }
    fun supports(request:RoseLegacyControlRequest.SetValue,noise:NoiseMode?=null)=request.expectedModel==model && request.setting in values && request.value in profile?.allowed(request.setting).orEmpty() && when(request.setting) {
        RoseLegacySetting.ANC_LEVEL -> noise == NoiseMode.ANC
        RoseLegacySetting.TRANSPARENCY_LEVEL -> noise == NoiseMode.TRANSPARENCY
        else -> true
    }
    companion object {const val FEATURE_ID="rose.legacy.settings"}
}
@Serializable sealed interface RoseLegacyControlRequest:ControlRequest {
    @Serializable @SerialName("rose.legacy.set_touch")
    data class SetTouch(val expectedModel:String,val expectedMapping:List<Int>,val slot:Int,val action:Int):RoseLegacyControlRequest
    @Serializable @SerialName("rose.legacy.set_value")
    data class SetValue(val expectedModel:String,val setting:RoseLegacySetting,val value:Int):RoseLegacyControlRequest
}
fun Identity.Model.allowed(setting:RoseLegacySetting):List<Int> {
    if (this in setOf(Identity.Model.AGATE, Identity.Model.AGATE_X, Identity.Model.CERAMICS)) return when(setting) {
        RoseLegacySetting.EQ -> listOf(0,1) // Agate handler aliases raw2 to raw1; no distinct confirmed Rock slot.
        RoseLegacySetting.GAME -> if(this != Identity.Model.AGATE_X) listOf(0,1) else emptyList()
        else -> emptyList()
    }
    if (this == Identity.Model.CERAMICS_X_7034) return when(setting) {
        RoseLegacySetting.EQ,RoseLegacySetting.AUDIO,RoseLegacySetting.ANC_LEVEL,RoseLegacySetting.TRANSPARENCY_LEVEL -> listOf(0,1,2)
        RoseLegacySetting.GAME,RoseLegacySetting.TOUCH,RoseLegacySetting.LANGUAGE -> listOf(0,1)
        else -> emptyList()
    }
    return when(setting) {
        RoseLegacySetting.GAME,RoseLegacySetting.TOUCH -> listOf(0,1)
        RoseLegacySetting.EQ -> when(this){Identity.Model.EXPLORER,Identity.Model.BUDSFREE,Identity.Model.CAMBRIAN->listOf(0,1,2);Identity.Model.OPENFEEL->listOf(0,1);else->emptyList()}
        RoseLegacySetting.AUDIO -> when(this){Identity.Model.EXPLORER->listOf(0,1,2);Identity.Model.BUDSFREE->listOf(0,1);Identity.Model.CAMBRIAN->listOf(0,1,4);Identity.Model.OPENFEEL->listOf(0,1,2,3,4);Identity.Model.SWIFT->emptyList();else->listOf(0,4)}
        RoseLegacySetting.PROMPT -> if(this in setOf(Identity.Model.EXPLORER,Identity.Model.CAMBRIAN,Identity.Model.OPENFUN,Identity.Model.OPENLINK,Identity.Model.SPORTFEEL)) (1..5).toList() else emptyList()
        RoseLegacySetting.LANGUAGE,RoseLegacySetting.WEAR -> if(this==Identity.Model.EXPLORER)listOf(0,1) else emptyList()
        RoseLegacySetting.SPATIAL -> if(this==Identity.Model.SPORTFEEL)listOf(0,1) else emptyList()
        RoseLegacySetting.ANC_LEVEL,RoseLegacySetting.TRANSPARENCY_LEVEL -> emptyList()
    }
}
class RoseLegacyAdapter(val model:Identity.Model):StandardEarbudAdapter() {
    override val id=model.id
    override val displayName=model.label
    override val resolution=AdapterResolution.EXACT_MATCH
    override val formFactor=if(model.head) HeadsetFormFactor.HEADPHONES else HeadsetFormFactor.TWS
    override val privateProtocolRequired=true
    override val transportReadiness=TransportReadiness.PROTOCOL_HANDSHAKE
    override val controlApps=listOf(ControlAppCatalog.roseLink)
    override val transports:List<EarbudTransportSpec> = if(model.spp) listOf(RfcommEndpointSpec.ServiceUuid(Identity.SERVICE_UUID,id="${model.id}-spp")) else listOf(GattWriteMode.WITHOUT_RESPONSE,GattWriteMode.WITH_RESPONSE).map { mode -> GattTransportSpec(
        serviceUuid=if(model.f8) RoseLuliXAdapter.SERVICE_UUID else "000000fe-0000-1000-8000-00805f9b34fb",
        writeCharacteristicUuid=if(model.f8) RoseLuliXAdapter.WRITE_CHARACTERISTIC_UUID else "000000f1-0000-1000-8000-00805f9b34fb",
        notifyCharacteristicUuid=if(model.f8) RoseLuliXAdapter.NOTIFY_CHARACTERISTIC_UUID else "000000f2-0000-1000-8000-00805f9b34fb",
        writeMode=mode,notificationsRequired=true,id="${model.id}-gatt-${mode.name}") }
    override fun matches(identity:EarbudIdentity)=identity.standardHeadset && !identity.nativeSystemEarbud && dev.hyperears.protocol.rose.RoseManufacturerIdentityCodec.modelId(identity.manufacturerData)==model.id
    override val featureStateContract=StandardDeviceFeatureStateContract.extending { _,state->state is RoseLegacySettingsFeatureState && state.model==model.name }
    override val controlRequestContract=StandardControlRequestContract.extending { adapter,request->when(request){
        is RoseLegacyControlRequest.SetValue -> adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>()?.supports(request,adapter.runtimeState().noiseMode)==true
        is RoseLegacyControlRequest.SetTouch -> adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>()?.supports(request)==true
        else -> false
    } }
    override val miLinkCardPresentationId get()=if(runtimeState().noiseMode!=null) PRESENTATION else SETTINGS_ONLY.takeIf {runtimeState().features.get<RoseLegacySettingsFeatureState>()?.hasSettings==true}
    override fun onProtocolReset(){removeFeatureState(RoseLegacySettingsFeatureState.FEATURE_ID)}
    override fun createProtocolSession():ProtocolSession=RoseLegacySession(model)
    companion object {val PRESENTATION=MiLinkCardPresentationId("rose-legacy-settings");val SETTINGS_ONLY=MiLinkCardPresentationId("rose-legacy-settings-only")}
}
internal class RoseLegacySession(private val model:Identity.Model):ProtocolSession {
    private val f8=model.f8
    private val decoder=Wire.Decoder(f8)
    private var state=RoseLegacySettingsFeatureState(model.name)
    private var noise:NoiseMode?=null
    private var sequence=0
    private var handshake=false
    private val fields=if(f8) mapOf(RoseLegacySetting.EQ to 4,RoseLegacySetting.GAME to 8,RoseLegacySetting.AUDIO to 11,RoseLegacySetting.TOUCH to 22,RoseLegacySetting.PROMPT to 19,RoseLegacySetting.LANGUAGE to 10,RoseLegacySetting.WEAR to 9,RoseLegacySetting.ANC_LEVEL to 17,RoseLegacySetting.TRANSPARENCY_LEVEL to 18)
        else mapOf(RoseLegacySetting.EQ to 42,RoseLegacySetting.GAME to 14,RoseLegacySetting.AUDIO to 43,RoseLegacySetting.TOUCH to 7,RoseLegacySetting.PROMPT to 46,RoseLegacySetting.SPATIAL to 51)
    private fun query()=if(f8) (if(modes().isNotEmpty()) listOf(Wire.f8Query(12)) else emptyList())+fields.filterKeys {model.allowed(it).isNotEmpty()}.values.map(Wire::f8Query) else listOf(Wire.rsQuery(sequence++ and 255))
    override fun initialReadCommands()=query()
    override fun readback(request:ControlRequest)=when(request){is RoseLegacyControlRequest.SetTouch->if(!f8)query()else emptyList();is RoseLegacyControlRequest.SetValue->if(f8)listOf(Wire.f8Query(fields.getValue(request.setting)))else query();is StandardControlRequest.SetNoiseMode->if(f8)listOf(Wire.f8Query(12))else query();else->emptyList()}
    override fun encode(request:ControlRequest):List<ByteArray> = when(request){
        StandardControlRequest.Refresh->query()
        is RoseLegacyControlRequest.SetTouch->if(!f8 && state.supports(request))listOf(Wire.rsTouchWrite(sequence++ and 255,request.slot,request.action))else emptyList()
        is RoseLegacyControlRequest.SetValue->if(!state.supports(request,noise))emptyList()else if(f8){
            val command=mapOf(RoseLegacySetting.EQ to 0x20,RoseLegacySetting.GAME to 0x25,RoseLegacySetting.AUDIO to 0x2b,RoseLegacySetting.TOUCH to 0x33,RoseLegacySetting.PROMPT to 0x32,RoseLegacySetting.LANGUAGE to 0x29,RoseLegacySetting.WEAR to 0x26,RoseLegacySetting.ANC_LEVEL to 0x30,RoseLegacySetting.TRANSPARENCY_LEVEL to 0x31).getValue(request.setting)
            listOf(Wire.f8Write(command,request.value,request.setting==RoseLegacySetting.EQ))
        }else listOf(Wire.rsWrite(sequence++ and 255,fields.getValue(request.setting),request.value))
        is StandardControlRequest.SetNoiseMode->if(noise==null || request.mode !in modes())emptyList()else {
            val value=if(model==Identity.Model.CERAMICS_X_7034) when(request.mode){NoiseMode.WIND->0;NoiseMode.ANC->1;NoiseMode.TRANSPARENCY->2;NoiseMode.OFF->3}
            else if(f8)when(request.mode){NoiseMode.OFF->0;NoiseMode.ANC->1;else->2}else when(request.mode){NoiseMode.ANC->1;NoiseMode.OFF->2;NoiseMode.TRANSPARENCY->3;NoiseMode.WIND->4}
            listOf(if(f8)Wire.f8Write(0x2c,value)else Wire.rsWrite(sequence++ and 255,9,value))
        }
        else->emptyList()
    }
    private fun modes():Set<NoiseMode> = when(model){Identity.Model.CERAMICS_X_7034->NoiseMode.entries.toSet();Identity.Model.EXPLORER->setOf(NoiseMode.ANC,NoiseMode.OFF,NoiseMode.TRANSPARENCY);Identity.Model.BUDSFREE,Identity.Model.CAMBRIAN->NoiseMode.entries.toSet();else->emptySet()}
    override fun offer(bytes:ByteArray):List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { report->
            if(!f8 && report.type==1) {
                if(RoseLegacyTouch.valid(model,report.data)){state=state.copy(touch=report.data.toList());add(ProtocolEvent.FeatureStateChanged(state))}
            }else if(!f8 && report.type==12 && report.data.size==3) {
                fun reading(index:Int): BatteryReading {
                    val raw = report.data[index]
                    val percent = if (index < 2) raw and 127 else raw
                    return if (percent in 0..100) BatteryReading(percent, charging = index < 2 && raw and 128 != 0)
                        else BatteryReading(null, false)
                }
                val battery=if(model.head) EarbudBattery(overall=reading(2)) else EarbudBattery(left=reading(0),right=reading(1),case=reading(2))
                if(if(model.head) battery.overall.available else listOf(battery.left,battery.right,battery.case).any {it.available}) {
                    add(ProtocolEvent.CapabilitiesIdentified(battery=true));add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(battery)))
                }
            }else if(report.type==if(f8)12 else 9){
                val raw=report.data.singleOrNull()
                val mode=if(model==Identity.Model.CERAMICS_X_7034)when(raw){0->NoiseMode.WIND;1->NoiseMode.ANC;2->NoiseMode.TRANSPARENCY;3->NoiseMode.OFF;else->null}
                else if(f8)when(raw){0->NoiseMode.OFF;1->NoiseMode.ANC;2->NoiseMode.TRANSPARENCY;else->null}else when(raw){1->NoiseMode.ANC;2->NoiseMode.OFF;3->NoiseMode.TRANSPARENCY;4->NoiseMode.WIND;else->null}
                if(mode!=null && mode in modes()){noise=mode;add(ProtocolEvent.CapabilitiesIdentified(battery=false,noiseModes=modes()));add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))}
            }else{
                val setting=fields.entries.singleOrNull {it.value==report.type}?.key ?: return@forEach
                val reported=if(f8 && setting==RoseLegacySetting.EQ) report.data.takeIf {it.size==2 && it[0]==0}?.get(1) else report.data.singleOrNull()
                // AgateReceiveHandler reports raw1 and raw2 as the same semantic preset0.
                // Canonicalize state without adding a distinct writable raw2 option.
                val value=if(setting==RoseLegacySetting.EQ && reported==2 && model in setOf(
                    Identity.Model.AGATE,Identity.Model.AGATE_X,Identity.Model.CERAMICS)) 1 else reported
                if(value!=null && value in model.allowed(setting)){state=state.copy(values=state.values+(setting to value));add(ProtocolEvent.FeatureStateChanged(state))}
            }
        }
        if(isNotEmpty() && !handshake){handshake=true;add(0,ProtocolEvent.HandshakeAccepted)}
    }
    override fun reset(){decoder.reset();state=RoseLegacySettingsFeatureState(model.name);noise=null;handshake=false;sequence=0}
}

/** Six source offsets within the direct type01 data; other bytes remain opaque snapshot evidence. */
object RoseLegacyTouch {
    val SLOTS=listOf(1,2,3,17,18,19)
    val OFFSETS=listOf(1,3,5,11,13,15)
    fun choices(model:Identity.Model?):List<Int> = when {
        model==null || model.f8 -> emptyList()
        model==Identity.Model.BUDSFREE -> listOf(0,1,2,3,5,6)
        model==Identity.Model.OPENFEEL -> (1..5).toList() // Intersection of source firmware branches; no guessed version.
        else -> (0..5).toList()
    }
    fun action(snapshot:List<Int>,index:Int)=snapshot[OFFSETS[index]] and 15
    fun valid(model:Identity.Model,data:List<Int>):Boolean = !model.f8 && data.size in 16..254 &&
        OFFSETS.indices.all{action(data,it) in (if(model==Identity.Model.OPENFEEL)(0..5).toList()else choices(model))}
}
