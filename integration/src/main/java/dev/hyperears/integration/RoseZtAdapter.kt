package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseZtWireCodec as Wire
import dev.hyperears.protocol.rose.RoseZtIdentityCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
@SerialName("rose.zt.settings")
data class RoseZtSettingsFeatureState(val model: String, val values: Map<String, Int> = emptyMap()) : DeviceFeatureState {
    @Transient override val featureId = FEATURE_ID
    val hasSettings: Boolean get() = values.isNotEmpty()
    val profile: Wire.Model? get() = Wire.Model.entries.singleOrNull { it.name == model }
    fun supports(request: RoseZtControlRequest.SetValue): Boolean {
        val p = profile ?: return false
        val setting = Wire.Setting.entries.singleOrNull { it.name == request.setting } ?: return false
        return request.expectedModel == model && request.setting in values && request.value in setting.values(p)
    }
    companion object { const val FEATURE_ID = "rose.zt.settings" }
}
@Serializable
sealed interface RoseZtControlRequest : ControlRequest {
    @Serializable @SerialName("rose.zt.set_value")
    data class SetValue(val expectedModel: String, val setting: String, val value: Int) : RoseZtControlRequest
}

class RoseZtAdapter(private val model: Wire.Model) : StandardEarbudAdapter() {
    override val id = model.id
    override val displayName = model.display
    override val resolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired = true
    override val transportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val formFactor = if (model == Wire.Model.HEADFEEL) HeadsetFormFactor.HEADPHONES else HeadsetFormFactor.TWS
    override val controlApps = listOf(ControlAppCatalog.roseLink)
    override val transports: List<EarbudTransportSpec> = if (model == Wire.Model.HEADFEEL)
        listOf(RfcommEndpointSpec.ServiceUuid("0cf12d31-fac3-4553-bd80-d6832e7b3931", "rose-zt-headfeel")) else
        listOf(GattWriteMode.WITHOUT_RESPONSE, GattWriteMode.WITH_RESPONSE).map { mode ->
            GattTransportSpec(serviceUuid = RoseEarfreeProtocolFamilyAdapter.SERVICE_UUID,
                writeCharacteristicUuid = RoseEarfreeProtocolFamilyAdapter.WRITE_CHARACTERISTIC_UUID,
                notifyCharacteristicUuid = RoseEarfreeProtocolFamilyAdapter.NOTIFY_CHARACTERISTIC_UUID,
                writeMode = mode,
                id = "rose-zt-i3-gatt-${mode.name.lowercase(java.util.Locale.ROOT)}")
        }
    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        return if (identity.manufacturerData.isNotEmpty())
            dev.hyperears.protocol.rose.RoseManufacturerIdentityCodec.modelId(identity.manufacturerData) == id
        else RoseZtIdentityCodec.named(identity.deviceName.orEmpty()) == model
    }
    override val featureStateContract = StandardDeviceFeatureStateContract.extending { _, state ->
        state is RoseZtSettingsFeatureState && state.model == model.name
    }
    override val controlRequestContract = StandardControlRequestContract.extending { adapter, request ->
        request is RoseZtControlRequest.SetValue && adapter.runtimeState().features.get<RoseZtSettingsFeatureState>()?.supports(request) == true
    }
    override fun controlPolicy(request: ControlRequest) = if (request is RoseZtControlRequest) ControlExecutionPolicy() else super.controlPolicy(request)
    override val miLinkCardPresentationId: MiLinkCardPresentationId? get() = PRESENTATION_ID.takeIf {
        runtimeState().features.get<RoseZtSettingsFeatureState>()?.values?.isNotEmpty() == true || effectiveCapabilities().noiseControl
    }
    override fun createProtocolSession(): ProtocolSession = RoseZtProtocolSession(model)
    override fun onProtocolReset() { removeFeatureState(RoseZtSettingsFeatureState.FEATURE_ID) }
    companion object { val PRESENTATION_ID = MiLinkCardPresentationId("rose-zt-settings") }
}
internal class RoseZtProtocolSession(private val model: Wire.Model) : ProtocolSession {
    private val decoder = Wire.Decoder()
    private var settings = RoseZtSettingsFeatureState(model.name)
    private var handshake = false
    private var noise: NoiseMode? = null
    private val modes = if (model == Wire.Model.I3) setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY) else NoiseMode.entries.toSet()
    override fun initialReadCommands() = Wire.query(model)
    override fun encode(request: ControlRequest): List<ByteArray> = when(request) {
        StandardControlRequest.Refresh -> initialReadCommands()
        is StandardControlRequest.SetNoiseMode -> if (noise != null && request.mode in modes)
            listOfNotNull(Wire.writeNoise(model, noiseIndex(request.mode))) else emptyList()
        is RoseZtControlRequest.SetValue -> if (settings.supports(request)) listOfNotNull(
            Wire.write(model, Wire.Setting.valueOf(request.setting), request.value)) else emptyList()
        else -> emptyList()
    }
    override fun readback(request: ControlRequest): List<ByteArray> = when(request) {
        is StandardControlRequest.SetNoiseMode -> listOf(Wire.command(6, 2))
        is RoseZtControlRequest.SetValue -> Wire.Setting.entries.singleOrNull { it.name == request.setting }?.let {
            listOf(Wire.command(it.group, it.command)) }.orEmpty()
        else -> emptyList()
    }
    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        for (frame in decoder.offer(bytes)) {
            val events = mutableListOf<ProtocolEvent>()
            Wire.readSetting(model, frame)?.let { (setting, value) ->
                settings = settings.copy(values = settings.values + (setting.name to value))
                events += ProtocolEvent.FeatureStateChanged(settings)
            }
            Wire.noise(frame)?.let { index ->
                val mode = listOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.WIND, NoiseMode.TRANSPARENCY)[index]
                if (mode in modes) { noise = mode; events += ProtocolEvent.CapabilitiesIdentified(battery = false, noiseModes = modes)
                    events += ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)) }
            }
            if (frame.group == 1) {
                val data = frame.payload
                val battery = if (model == Wire.Model.HEADFEEL && frame.command == 3 && data.size == 1 && data[0] in 0..100)
                    EarbudBattery(overall = BatteryReading(data[0], false))
                else if (model == Wire.Model.I3 && frame.command == 1 && data.size == 7 && data[4] in 0..1 && data[5] in 0..1 &&
                    listOf(data[2], data[3], data[6]).any { it in 0..100 })
                    EarbudBattery(left = BatteryReading(data[2].takeIf { it in 0..100 }, data[4] == 1),
                        right = BatteryReading(data[3].takeIf { it in 0..100 }, data[5] == 1),
                        case = BatteryReading(data[6].takeIf { it in 0..100 }, false)) else null
                battery?.let { events += ProtocolEvent.CapabilitiesIdentified(battery = true); events += ProtocolEvent.FeatureStateChanged(BatteryFeatureState(it)) }
            }
            if (events.isNotEmpty()) {
                if (!handshake) { handshake = true; add(ProtocolEvent.HandshakeAccepted) }
                addAll(events)
            }
        }
    }
    override fun reset() { decoder.reset(); settings = RoseZtSettingsFeatureState(model.name); handshake = false; noise = null }
    private fun noiseIndex(mode: NoiseMode) = when(mode) { NoiseMode.ANC -> 0; NoiseMode.OFF -> 1; NoiseMode.WIND -> 2; NoiseMode.TRANSPARENCY -> 3 }
}
