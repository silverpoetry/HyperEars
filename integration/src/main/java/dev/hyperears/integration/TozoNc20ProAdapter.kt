package dev.hyperears.integration

import dev.hyperears.protocol.tozo.TozoDailyCodec
import dev.hyperears.protocol.tozo.TozoTouchCodec
import dev.hyperears.protocol.tozo.TozoEqualizerCodec
import dev.hyperears.protocol.tozo.TozoHeadsetCodec
import dev.hyperears.protocol.tozo.TozoWireCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class TozoNoise { OFF, ANC, TRANSPARENCY, WIND, LEISURE, CUSTOM, ADAPTIVE }

@Serializable
sealed interface TozoControlRequest : ControlRequest {
    @Serializable @SerialName("tozo.nc20pro.noise")
    data class SetNoise(val mode: TozoNoise) : TozoControlRequest
    @Serializable @SerialName("tozo.nc20pro.daily.set")
    data class SetDaily(val setting: TozoDailySetting, val expected: Boolean, val enabled: Boolean, val expectedPeer: Boolean? = null) : TozoControlRequest
    @Serializable @SerialName("tozo.nc20pro.touch.set")
    data class SetTouch(val expected: TozoTouchFeatureState, val slot: TozoTouchSlot, val action: TozoTouchAction) : TozoControlRequest
    @Serializable @SerialName("tozo.nc20pro.equalizer.set")
    data class SetEqualizer(val expected: TozoEqualizerFeatureState, val gainsTenths: List<Int>) : TozoControlRequest
}

@Serializable @SerialName("tozo.nc20pro.settings")
data class TozoNc20ProFeatureState(
    val noise: TozoNoise? = null,
    val supportedModes: Set<TozoNoise> = emptySet(),
    val firmwareLeft: String? = null,
    val firmwareRight: String? = null,
) : DeviceFeatureState {
    override val featureId: String get() = "tozo.nc20pro.settings"
}

/** Candidate name selects a bounded protocol probe; only valid reports unlock controls. */
class TozoNc20ProAdapter : EarbudAdapter() {
    override val id = "tozo-nc20-pro"
    override val displayName = "TOZO NC20 Pro"
    override val artwork = HeadsetArtwork(HeadsetArtworkId("headset_tozo_nc20_pro_earbuds"), HeadsetArtworkId("headset_tozo_nc20_pro_case"))
    override val resolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired = true
    override val batterySource = BatterySource.SYSTEM_AGGREGATE
    override val capabilities = EarbudCapabilities(battery = true, audioHandoff = true)
    override val controlApps = listOf(ControlAppCatalog.tozo)
    override val transports = listOf(GattTransportSpec(
        serviceUuid = "00004d45-0000-1000-8000-00805f9b34fb",
        writeCharacteristicUuid = "0000b611-0000-1000-8000-00805f9b34fb",
        notifyCharacteristicUuid = "0000b612-0000-1000-8000-00805f9b34fb",
        writeMode = GattWriteMode.WITHOUT_RESPONSE,
        notificationsRequired = true,
        requestedMtu = 220,
        minimumMtu = 48,
        id = "tozo-nc20-pro-control",
    ))
    override fun matches(identity: EarbudIdentity) = identity.standardHeadset &&
        !identity.nativeSystemEarbud && normalizeDeviceName(identity.deviceName.orEmpty()) in
        setOf("tozonc20pro", "nc20pro")
    override fun createProtocolSession(): ProtocolSession = TozoNc20ProSession()
    override val featureStateContract = StandardDeviceFeatureStateContract.extending { _, state -> state is TozoNc20ProFeatureState || state is TozoEqualizerFeatureState && state.valid || state is TozoTouchFeatureState && state.valid || state is TozoDailyFeatureState }
    override val controlRequestContract = ControlRequestContract { adapter, request ->
        val mode = when(request) {
            is TozoControlRequest.SetNoise -> request.mode
            is StandardControlRequest.SetNoiseMode -> TozoNoise.valueOf(request.mode.name)
            else -> null
        }
        if(mode != null) mode in adapter.runtimeState().features.get<TozoNc20ProFeatureState>()?.supportedModes.orEmpty()
        else if (request is TozoControlRequest.SetDaily) adapter.runtimeState().features.get<TozoDailyFeatureState>()?.supports(request) == true
        else if (request is TozoControlRequest.SetTouch) adapter.runtimeState().features.get<TozoTouchFeatureState>()?.supports(request) == true
        else if (request is TozoControlRequest.SetEqualizer) adapter.runtimeState().features.get<TozoEqualizerFeatureState>()?.supports(request) == true
        else StandardControlRequestContract.supports(adapter, request)
    }
    override fun onProtocolReset() { removeFeatureState("tozo.nc20pro.settings"); removeFeatureState(TozoEqualizerFeatureState.ID); removeFeatureState(TozoTouchFeatureState.ID); removeFeatureState(TozoDailyFeatureState.ID) }
    override fun onFeatureReported(state: DeviceFeatureState, scope: AdapterEventScope): FeatureReportDecision {
        if (state is TozoTouchFeatureState) scope.cancelStateRequest(TozoTouchFeatureState.ID)
        if(state is TozoNc20ProFeatureState && state.noise?.standard() == null)
            removeFeatureState(NoiseModeFeatureState.FEATURE_ID)
        return FeatureReportDecision.ACCEPT
    }
    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy {
        val touch = runtimeState().features.get<TozoTouchFeatureState>()
        return if (request is TozoControlRequest.SetTouch && touch != null) {
            ControlExecutionPolicy(
                confirmation = ControlConfirmationPolicy.PUBLISH_AFTER_WRITE_THEN_REFRESH,
                stateAfterWrite = touch.copy(awaitingReadback = true),
            )
        } else ControlExecutionPolicy()
    }
    override fun onControlWritten(request: ControlRequest, scope: AdapterEventScope) {
        if (request is TozoControlRequest.SetTouch) {
            // One bounded retry; never unlock a full-vector write using an unconfirmed baseline.
            scope.requestState(TozoTouchFeatureState.ID, 2_000L)
        }
    }
}

private fun TozoNoise.standard(): NoiseMode? = NoiseMode.entries.find { it.name == name }

private class TozoNc20ProSession : ProtocolSession {
    private var started = false
    private var batterySeen = false
    private var state = TozoNc20ProFeatureState()
    private var equalizer: TozoEqualizerFeatureState? = null
    private var touch: TozoTouchFeatureState? = null
    private var touchWritePending = false
    private var daily = TozoDailyFeatureState()
    // TOZO 5.4.3 FunctionSupportHelp + CommonAncBean.initAncArrayV2: custom is absent.
    private val modes = TozoNoise.entries.toSet() - TozoNoise.CUSTOM
    override fun initialReadCommands() = TozoHeadsetCodec.reads() + listOf(TozoEqualizerCodec.read(), TozoTouchCodec.read()) + TozoDailyCodec.Setting.entries.map(TozoDailyCodec::read)
    override fun encode(request: ControlRequest): List<ByteArray> {
        if(request === StandardControlRequest.Refresh) return initialReadCommands()
        if (request is TozoControlRequest.SetDaily) {
            if (!daily.supports(request)) return emptyList()
            return listOf(TozoDailyCodec.write(request.setting.wire(),request.enabled))
        }
        if (request is TozoControlRequest.SetTouch) {
            if (touchWritePending) return emptyList()
            val current = touch?.takeIf { it.supports(request) } ?: return emptyList()
            touchWritePending = true
            return listOf(TozoTouchCodec.write(current.raw.map { it.toByte() }.toByteArray(), request.slot.offset, request.action.code))
        }
        if (request is TozoControlRequest.SetEqualizer) {
            val current = equalizer?.takeIf { it.supports(request) } ?: return emptyList()
            val bands = current.bands.mapIndexed { i, band -> band.copy(gainTenths=request.gainsTenths[i]).wire() }
            return listOf(TozoEqualizerCodec.write(bands, persist=true))
        }
        val mode = when(request) {
            is TozoControlRequest.SetNoise -> request.mode
            is StandardControlRequest.SetNoiseMode -> TozoNoise.valueOf(request.mode.name)
            else -> return emptyList()
        }
        if(mode !in state.supportedModes) return emptyList()
        return listOf(TozoHeadsetCodec.setNoise(TozoHeadsetCodec.Noise.valueOf(mode.name)))
    }
    override fun query(request: TelemetryQuery): List<ByteArray> = when (request) {
        TelemetryQuery.RefreshAll -> initialReadCommands()
        is TelemetryQuery.RefreshFeature -> if (request.featureId == TozoTouchFeatureState.ID && touchWritePending)
            listOf(TozoTouchCodec.read()) else emptyList()
    }
    override fun readback(request: ControlRequest) = when(request) {
        is TozoControlRequest.SetDaily -> listOfNotNull(request.setting, request.setting.peer).map { TozoDailyCodec.read(it.wire()) }
        is TozoControlRequest.SetTouch -> listOf(TozoTouchCodec.read())
        is TozoControlRequest.SetEqualizer -> listOf(TozoEqualizerCodec.read())
        is TozoControlRequest.SetNoise, is StandardControlRequest.SetNoiseMode -> listOf(TozoWireCodec.encode(0x0030, byteArrayOf()))
        else -> emptyList()
    }
    override fun offer(bytes: ByteArray): List<ProtocolEvent> {
        TozoDailyCodec.parse(bytes)?.let { report ->
            daily = daily.copy(values=daily.values + (TozoDailySetting.valueOf(report.setting.name) to report.enabled))
            return buildList {
                if (!started) { started=true; add(ProtocolEvent.HandshakeAccepted) }
                add(ProtocolEvent.FeatureStateChanged(daily))
            }
        }
        TozoTouchCodec.parse(bytes)?.let { raw ->
            val report = TozoTouchFeatureState(raw.map { it.toInt() and 255 })
            touch = report
            touchWritePending = false
            return buildList {
                if (!started) { started=true; add(ProtocolEvent.HandshakeAccepted) }
                add(ProtocolEvent.FeatureStateChanged(report))
            }
        }
        TozoEqualizerCodec.parse(bytes)?.let { bands ->
            val report = TozoEqualizerFeatureState(bands.map { TozoEqBand(it.frequencyHz,it.gainTenths,it.qValue) })
            equalizer = report
            return buildList {
                if (!started) { started=true; add(ProtocolEvent.HandshakeAccepted) }
                add(ProtocolEvent.FeatureStateChanged(report))
            }
        }
        val report = TozoHeadsetCodec.parse(bytes) ?: return emptyList()
        // Write status 0 is success, never the current ANC mode. Await 0030/2030.
        if(report is TozoHeadsetCodec.Report.Acknowledgement) return emptyList()
        return buildList {
            if(!started) { started = true; add(ProtocolEvent.HandshakeAccepted) }
            when(report) {
                is TozoHeadsetCodec.Report.Battery -> {
                    batterySeen = true
                    add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(EarbudBattery(
                        left = BatteryReading(report.left, false), right = BatteryReading(report.right, false),
                    ))))
                }
                is TozoHeadsetCodec.Report.Firmware -> state = state.copy(firmwareLeft=report.left, firmwareRight=report.right)
                is TozoHeadsetCodec.Report.Noise -> {
                    val mode = TozoNoise.valueOf(report.mode.name)
                    state = state.copy(noise=mode, supportedModes=if(mode in modes) modes else emptySet())
                    mode.standard()?.let { add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(it))) }
                }
                is TozoHeadsetCodec.Report.Acknowledgement -> Unit
            }
            add(ProtocolEvent.FeatureStateChanged(state))
            add(ProtocolEvent.CapabilitiesIdentified(battery=batterySeen, noiseModes=state.supportedModes.mapNotNull { it.standard() }.toSet()))
        }
    }
    override fun reset() { started=false; batterySeen=false; state=TozoNc20ProFeatureState(); equalizer=null; touch=null; touchWritePending=false; daily=TozoDailyFeatureState() }
}
