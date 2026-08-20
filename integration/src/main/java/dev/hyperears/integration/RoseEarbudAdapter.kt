package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseBudsFeelMk2WireCodec
import dev.hyperears.protocol.rose.RoseCeramicsXWireCodec
import dev.hyperears.protocol.rose.RoseEarfreeI5WireCodec

/** Standard Bluetooth fallback for ROSESELSA/ROSE headsets outside a known protocol family. */
open class RoseEarbudAdapter(
    transferredSession: ProtocolSession? = null,
    initialRuntimeState: AdapterRuntimeState = AdapterRuntimeState(),
) : StandardEarbudAdapter(transferredSession, initialRuntimeState) {
    override val id: String = ID
    override val displayName: String = "ROSESELSA headset"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.roseLink)

    override fun matches(identity: EarbudIdentity): Boolean {
        if (identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name.startsWith("roseselsa") ||
            name.startsWith("roseear") ||
            name.startsWith("rosebudsfeel") ||
            name.startsWith("budsfeel")
    }

    companion object {
        const val ID = "roseselsa-family"
    }
}

/**
 * EARFREE/EARFEEL product-line adapter.
 *
 * Public EARFREE i5 captures establish the service, characteristics and frame grammar. Unknown
 * models in the same named product line may reuse it, but must return a valid protocol frame
 * before the private channel becomes ready.
 */
open class RoseEarfreeProtocolFamilyAdapter(
    transferredSession: ProtocolSession? = null,
    initialRuntimeState: AdapterRuntimeState = AdapterRuntimeState(),
) : RoseEarbudAdapter(transferredSession, initialRuntimeState) {
    override val id: String = ID
    override val displayName: String = "ROSE EARFREE protocol family"
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = PRESENTATION_ID.takeIf { effectiveCapabilities().noiseControl }
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness =
        TransportReadiness.PROTOCOL_HANDSHAKE
    override val batterySource: BatterySource = BatterySource.SYSTEM_AGGREGATE
    override val capabilities: EarbudCapabilities = EarbudCapabilities(
        battery = true,
        audioHandoff = true,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        GattTransportSpec(
            serviceUuid = SERVICE_UUID,
            writeCharacteristicUuid = WRITE_CHARACTERISTIC_UUID,
            notifyCharacteristicUuid = NOTIFY_CHARACTERISTIC_UUID,
            id = "rose-earfree-family-gatt",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        val namedProductLine = name.startsWith("roseselsaearfree") ||
            name.startsWith("roseearfree") ||
            name.startsWith("roseearfeel")
        val advertisedService = identity.serviceUuids.any {
            it.equals(SERVICE_UUID, ignoreCase = true)
        }
        // The private service selects this protocol-family probe on its own. Product-line names
        // remain a second, independent entry path for devices whose UUID cache is not populated.
        return namedProductLine || advertisedService
    }

    override fun createProtocolSession(): ProtocolSession = RoseEarfreeProtocolSession()

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.FallbackTo(
            RoseEarbudAdapter(initialRuntimeState = runtimeState()),
        )

    companion object {
        const val ID = "rose-earfree-protocol-family"
        val PRESENTATION_ID = MiLinkCardPresentationId("rose-earfree-protocol")
        const val SERVICE_UUID = "011bf5da-0000-1000-8000-00805f9b34fb"
        const val WRITE_CHARACTERISTIC_UUID =
            "00007777-0000-1000-8000-00805f9b34fb"
        const val NOTIFY_CHARACTERISTIC_UUID =
            "00008888-0000-1000-8000-00805f9b34fb"
    }
}

/**
 * Verified BudsFeel implementation used by Furina Endless Solo of Solitude.
 *
 * The product name is retained as a fallback when Android has not populated the cached SDP UUIDs;
 * the read-only BudsFeel handshake remains authoritative for private capabilities.
 */
class FurinaEndlessAdapter : RoseBudsFeelProtocolFamilyAdapter() {
    override val id: String = ID
    override val displayName: String = "Furina Endless Solo of Solitude"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean {
        if (identity.nativeSystemEarbud) return false
        return normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES
    }

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.KeepDormant

    companion object {
        const val ID = "furina-endless-budsfeel"
        private val MODEL_NAMES = setOf("furinaendlesssoloofsolitude")
    }
}

class RoseLuliXAdapter : RoseEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "ROSE Ceramics X (Luli X)"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = PRESENTATION_ID.takeIf { effectiveCapabilities().noiseControl }
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val batterySource: BatterySource = BatterySource.SYSTEM_AGGREGATE
    override val capabilities: EarbudCapabilities = EarbudCapabilities(
        battery = true,
        audioHandoff = true,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        GattTransportSpec(
            serviceUuid = SERVICE_UUID,
            writeCharacteristicUuid = WRITE_CHARACTERISTIC_UUID,
            notifyCharacteristicUuid = NOTIFY_CHARACTERISTIC_UUID,
            writeInstanceId = WRITE_ATTRIBUTE_HANDLE,
            notifyInstanceId = NOTIFY_ATTRIBUTE_HANDLE,
            writeMode = GattWriteMode.WITHOUT_RESPONSE,
            peerSelection = GattPeerSelection.CompanionDevice(
                filter = GattScanFilterSpec(deviceName = COMPANION_DEVICE_NAME),
                matcher = RoseLuliXGattPeerMatcher,
                scanTimeoutMs = 20_000L,
            ),
            id = "rose-luli-x-companion-gatt",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (identity.nativeSystemEarbud) return false
        return normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES
    }

    override fun createProtocolSession(): ProtocolSession = RoseLuliXProtocolSession()

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.KeepDormant

    companion object {
        const val ID = "rose-luli-x-gatt"
        val PRESENTATION_ID = MiLinkCardPresentationId("rose-luli-x-gatt")
        const val COMPANION_DEVICE_NAME = "CERAMICS X BLE"
        const val COMPANION_MANUFACTURER_ID = 0x8418
        const val SERVICE_UUID =
            "0000fdb3-0000-1000-8000-00805f9b34fb"
        const val WRITE_ATTRIBUTE_HANDLE = 0x0015
        const val NOTIFY_ATTRIBUTE_HANDLE = 0x0017
        const val WRITE_CHARACTERISTIC_UUID =
            "0000ff16-0000-1000-8000-00805f9b34fb"
        const val NOTIFY_CHARACTERISTIC_UUID =
            "0000ff17-0000-1000-8000-00805f9b34fb"
        private val MODEL_NAMES = setOf(
            "roseceramicsx",
            "roselulix",
        )
    }
}

internal object RoseLuliXGattPeerMatcher : GattPeerMatcher {
    override fun matches(sessionDevice: GattPeerIdentity, candidate: GattPeerIdentity): Boolean =
        matchReason(sessionDevice, candidate) != null

    override fun matchReason(
        sessionDevice: GattPeerIdentity,
        candidate: GattPeerIdentity,
    ): String? {
        val sessionName = normalize(sessionDevice.deviceName)
        if (sessionName !in setOf("roseceramicsx", "roselulix")) return null

        val candidateName = normalize(candidate.deviceName)
        return when {
            candidateName == normalize(RoseLuliXAdapter.COMPANION_DEVICE_NAME) -> "device-name"
            candidate.manufacturerData.containsKey(RoseLuliXAdapter.COMPANION_MANUFACTURER_ID) ->
                "manufacturer-id"
            else -> null
        }
    }

    private fun normalize(value: String?): String =
        value.orEmpty().lowercase().filter(Char::isLetterOrDigit)
}

/**
 * ROSE Ceramics (Luli) Ultra adapter for the BudsFeel RFCOMM protocol family.
 *
 * RoseLink drives CERAMICS headsets over the same 0cf12d31 RFCOMM channel with the BudsFeel
 * frame grammar. The retail name is the primary match because HyperOS does not populate the
 * cached SDP UUIDs for this model; the read-only BudsFeel handshake remains authoritative for
 * private capabilities.
 */
class RoseLuliUltraAdapter : RoseBudsFeelProtocolFamilyAdapter() {
    override val id: String = ID
    override val displayName: String = "ROSE Ceramics Ultra (Luli Ultra)"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean {
        if (identity.nativeSystemEarbud) return false
        return normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES
    }

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.KeepDormant

    companion object {
        const val ID = "rose-luli-ultra-budsfeel"
        private val MODEL_NAMES = setOf(
            "roseceramicsu",
            "roseceramicsultra",
            "roseluliultra",
        )
    }
}

class RoseEarfreeI5Adapter : RoseEarfreeProtocolFamilyAdapter() {

    override val id: String = ID
    override val displayName: String = "ROSESELSA EARFREE i5"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean {
        if (identity.nativeSystemEarbud) return false
        return normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES
    }

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.KeepDormant

    companion object {
        const val ID = "roseselsa-earfree-i5"
        val PRESENTATION_ID = RoseEarfreeProtocolFamilyAdapter.PRESENTATION_ID
        private val MODEL_NAMES = setOf(
            "roseselsaearfreei5",
            "roseearfreei5",
            "roseearfeeli5",
        )
    }
}

/** BudsFeel product-line adapter using the public MK2 RFCOMM service and frame grammar. */
open class RoseBudsFeelProtocolFamilyAdapter(
    transferredSession: ProtocolSession? = null,
    initialRuntimeState: AdapterRuntimeState = AdapterRuntimeState(),
) : RoseEarbudAdapter(transferredSession, initialRuntimeState) {
    override val id: String = ID
    override val displayName: String = "ROSE BudsFeel protocol family"
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = PRESENTATION_ID.takeIf { effectiveCapabilities().noiseControl }
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness =
        TransportReadiness.PROTOCOL_HANDSHAKE
    override val batterySource: BatterySource = BatterySource.SYSTEM_AGGREGATE
    override val capabilities: EarbudCapabilities = EarbudCapabilities(
        battery = true,
        audioHandoff = true,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = DATA_CHANNEL_UUID,
            id = "rose-budsfeel-family-rfcomm",
        ),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        val namedProductLine = name.startsWith("rosebudsfeel") || name.startsWith("budsfeel")
        val advertisedService = identity.serviceUuids.any {
            it.equals(DATA_CHANNEL_UUID, ignoreCase = true)
        }
        // A cached private RFCOMM UUID is sufficient to try the family codec even when a
        // collaboration edition uses a completely different Bluetooth display name.
        return namedProductLine || advertisedService
    }

    override fun createProtocolSession(): ProtocolSession = RoseBudsFeelProtocolSession()

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.FallbackTo(
            RoseEarbudAdapter(initialRuntimeState = runtimeState()),
        )

    companion object {
        const val ID = "rose-budsfeel-protocol-family"
        val PRESENTATION_ID = MiLinkCardPresentationId("rose-budsfeel-protocol")
        const val DATA_CHANNEL_UUID = "0cf12d31-fac3-4553-bd80-d6832e7b3931"
    }
}

class RoseBudsFeelMk2Adapter : RoseBudsFeelProtocolFamilyAdapter() {

    override val id: String = ID
    override val displayName: String = "ROSE BudsFeel MK2"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean {
        if (identity.nativeSystemEarbud) return false
        return normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES
    }

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.KeepDormant

    companion object {
        const val ID = "rose-budsfeel-mk2"
        val PRESENTATION_ID = RoseBudsFeelProtocolFamilyAdapter.PRESENTATION_ID
        private val MODEL_NAMES = setOf("rosebudsfeelmk2", "budsfeelmk2")
    }
}

private class RoseLuliXProtocolSession : ProtocolSession {
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> =
        listOf(RoseCeramicsXWireCodec.queryNoiseMode)

    override fun encode(request: ControlRequest): List<ByteArray> = when {
        request === StandardControlRequest.Refresh -> initialReadCommands()
        request is StandardControlRequest.SetNoiseMode -> listOf(
            RoseCeramicsXWireCodec.setNoiseMode(request.mode.toLuliXMode()),
        )
        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = emptyList()

    override fun offer(bytes: ByteArray): List<ProtocolEvent> {
        val mode = RoseCeramicsXWireCodec.parseNoiseMode(bytes) ?: return emptyList()
        return buildList {
            if (!handshakePublished) {
                add(ProtocolEvent.HandshakeAccepted)
                handshakePublished = true
            }
            add(
                ProtocolEvent.CapabilitiesIdentified(
                    battery = false,
                    noiseModes = NoiseMode.entries.toSet(),
                ),
            )
            add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode.toDomainMode())))
        }
    }

    override fun reset() {
        handshakePublished = false
    }

    private fun NoiseMode.toLuliXMode(): RoseCeramicsXWireCodec.NoiseMode = when (this) {
        NoiseMode.ANC -> RoseCeramicsXWireCodec.NoiseMode.ANC
        NoiseMode.OFF -> RoseCeramicsXWireCodec.NoiseMode.OFF
        NoiseMode.TRANSPARENCY -> RoseCeramicsXWireCodec.NoiseMode.TRANSPARENCY
        NoiseMode.WIND -> RoseCeramicsXWireCodec.NoiseMode.WIND
    }

    private fun RoseCeramicsXWireCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        RoseCeramicsXWireCodec.NoiseMode.ANC -> NoiseMode.ANC
        RoseCeramicsXWireCodec.NoiseMode.OFF -> NoiseMode.OFF
        RoseCeramicsXWireCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
        RoseCeramicsXWireCodec.NoiseMode.WIND -> NoiseMode.WIND
    }
}

private class RoseEarfreeProtocolSession : ProtocolSession {
    private val decoder = RoseEarfreeI5WireCodec.Decoder()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        RoseEarfreeI5WireCodec.queryBattery,
        RoseEarfreeI5WireCodec.queryNoiseMode,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when {
        request === StandardControlRequest.Refresh -> initialReadCommands()
        request is StandardControlRequest.SetNoiseMode -> listOf(
            RoseEarfreeI5WireCodec.setNoiseMode(request.mode.toWireMode()),
        )

        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        var acceptedFrame = false
        decoder.offer(bytes).forEach { frame ->
            RoseEarfreeI5WireCodec.parseBattery(frame)?.let { battery ->
                acceptedFrame = true
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(EarbudBattery(
                            left = BatteryReading(battery.leftPercent, battery.leftCharging),
                            right = BatteryReading(battery.rightPercent, battery.rightCharging),
                            case = BatteryReading(battery.casePercent, false),
                        )),
                    ),
                )
            }
            RoseEarfreeI5WireCodec.parseNoiseMode(frame)?.let { mode ->
                acceptedFrame = true
                add(
                    ProtocolEvent.CapabilitiesIdentified(
                        battery = false,
                        noiseModes = NoiseMode.entries.toSet(),
                    ),
                )
                add(
                    ProtocolEvent.FeatureStateChanged(
                        NoiseModeFeatureState(mode.toDomainMode()),
                    ),
                )
            }
        }
        if (acceptedFrame && !handshakePublished) {
            add(0, ProtocolEvent.HandshakeAccepted)
            handshakePublished = true
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
    }

    private fun NoiseMode.toWireMode(): RoseEarfreeI5WireCodec.NoiseMode = when (this) {
        NoiseMode.ANC -> RoseEarfreeI5WireCodec.NoiseMode.ANC
        NoiseMode.OFF -> RoseEarfreeI5WireCodec.NoiseMode.OFF
        NoiseMode.TRANSPARENCY -> RoseEarfreeI5WireCodec.NoiseMode.TRANSPARENCY
        NoiseMode.WIND -> RoseEarfreeI5WireCodec.NoiseMode.WIND
    }

    private fun RoseEarfreeI5WireCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        RoseEarfreeI5WireCodec.NoiseMode.ANC -> NoiseMode.ANC
        RoseEarfreeI5WireCodec.NoiseMode.OFF -> NoiseMode.OFF
        RoseEarfreeI5WireCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
        RoseEarfreeI5WireCodec.NoiseMode.WIND -> NoiseMode.WIND
    }
}

private class RoseBudsFeelProtocolSession : ProtocolSession {
    private val decoder = RoseBudsFeelMk2WireCodec.Decoder()
    private var sequence = 0
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(queryStatus())

    override fun encode(request: ControlRequest): List<ByteArray> = when {
        request === StandardControlRequest.Refresh -> listOf(queryStatus())
        request is StandardControlRequest.SetNoiseMode -> listOf(
            RoseBudsFeelMk2WireCodec.setNoiseMode(nextSequence(), request.mode.toWireMode()),
        )

        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when {
        request === StandardControlRequest.Refresh -> emptyList()
        request is StandardControlRequest.SetNoiseMode -> listOf(queryStatus())
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> {
        val states = decoder.offer(bytes)
        if (states.isEmpty()) return emptyList()
        return buildList {
            if (!handshakePublished) {
                add(ProtocolEvent.HandshakeAccepted)
                handshakePublished = true
            }
            states.forEach { state ->
                add(
                    when (state) {
                        is RoseBudsFeelMk2WireCodec.State.Battery ->
                            ProtocolEvent.FeatureStateChanged(
                                BatteryFeatureState(EarbudBattery(
                                    left = BatteryReading(state.leftPercent, false),
                                    right = BatteryReading(state.rightPercent, false),
                                    case = BatteryReading(state.casePercent, false),
                                )),
                            ).also {
                                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                            }

                        is RoseBudsFeelMk2WireCodec.State.Noise ->
                            ProtocolEvent.FeatureStateChanged(
                                NoiseModeFeatureState(state.mode.toDomainMode()),
                            ).also {
                                add(
                                    ProtocolEvent.CapabilitiesIdentified(
                                        battery = false,
                                        noiseModes = NoiseMode.entries.toSet(),
                                    ),
                                )
                            }
                    },
                )
            }
        }
    }

    override fun reset() {
        decoder.reset()
        sequence = 0
        handshakePublished = false
    }

    private fun queryStatus(): ByteArray =
        RoseBudsFeelMk2WireCodec.queryStatus(nextSequence())

    private fun nextSequence(): Int = sequence.also { sequence = (sequence + 1) and 0xFF }

    private fun NoiseMode.toWireMode(): RoseBudsFeelMk2WireCodec.NoiseMode = when (this) {
        NoiseMode.ANC -> RoseBudsFeelMk2WireCodec.NoiseMode.ANC
        NoiseMode.OFF -> RoseBudsFeelMk2WireCodec.NoiseMode.OFF
        NoiseMode.TRANSPARENCY -> RoseBudsFeelMk2WireCodec.NoiseMode.TRANSPARENCY
        NoiseMode.WIND -> RoseBudsFeelMk2WireCodec.NoiseMode.WIND
    }

    private fun RoseBudsFeelMk2WireCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        RoseBudsFeelMk2WireCodec.NoiseMode.ANC -> NoiseMode.ANC
        RoseBudsFeelMk2WireCodec.NoiseMode.ADAPTIVE_ANC -> NoiseMode.ANC
        RoseBudsFeelMk2WireCodec.NoiseMode.EXTREME_ANC -> NoiseMode.ANC
        RoseBudsFeelMk2WireCodec.NoiseMode.OFF -> NoiseMode.OFF
        RoseBudsFeelMk2WireCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
        RoseBudsFeelMk2WireCodec.NoiseMode.WIND -> NoiseMode.WIND
    }
}
