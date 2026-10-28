package dev.hyperears.integration

import dev.hyperears.protocol.oppo.OppoWireCodec

/**
 * Shared OPPO Enco family adapter.
 *
 * OppoPods uses the same private RFCOMM service and standard three-state ANC mapping for the
 * family, with explicitly documented model exceptions. Unknown OPPO/Enco headsets therefore use
 * this candidate configuration, but private capabilities remain closed until a valid OPPO response;
 * a concrete adapter overrides only a verified difference.
 */
open class OppoEarbudAdapter(
    transferredSession: ProtocolSession? = null,
    initialRuntimeState: AdapterRuntimeState = AdapterRuntimeState(),
) : StandardEarbudAdapter(transferredSession, initialRuntimeState) {
    override val id: String = ID
    override val displayName: String = "OPPO Enco headset"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val controlApps: List<ControlAppSpec> = listOf(
        ControlAppCatalog.heyMelody,
        ControlAppCatalog.oplusWirelessEarphones,
        ControlAppCatalog.colorOsWirelessEarphones,
    )
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness =
        TransportReadiness.PROTOCOL_HANDSHAKE
    override val supportedNoiseModes: Set<NoiseMode> = emptySet()
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = OPPO_RFCOMM_UUID,
            id = "oppo-private-rfcomm",
        ),
    )
    open val wireConfig: OppoWireConfig = OppoWireConfig.STANDARD
    override val featureStateContract = StandardDeviceFeatureStateContract.extending { _, state ->
        state is OppoSettingsFeatureState
    }
    override val controlRequestContract = StandardControlRequestContract.extending { adapter, request ->
        request is OppoControlRequest &&
            adapter.runtimeState().features.get<OppoSettingsFeatureState>()?.supports(request) == true
    }
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = when {
            effectiveCapabilities().noiseControl -> PRESENTATION_ID
            runtimeState().features.get<OppoSettingsFeatureState>() != null -> SETTINGS_PRESENTATION_ID
            else -> null
        }
    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy =
        if (request is OppoControlRequest) ControlExecutionPolicy() else super.controlPolicy(request)
    override fun onProtocolReset() { removeFeatureState(OppoSettingsFeatureState.FEATURE_ID) }


    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        val catalog = OppoProductCatalog.named(identity.deviceName.orEmpty())
        if (catalog.isNotEmpty()) return catalog.any { it.privateProbeCandidate }
        return name.contains("oppo") || name.contains("enco")
    }

    override fun createProtocolSession(): ProtocolSession =
        OppoProtocolSession(wireConfig, ::isAdapterEnabled)

    override fun onProductIdentified(productId: Int): HandshakeResult? {
        val session = protocolSession as? OppoProtocolSession ?: return null
        val product = OppoProductCatalog.find(productId)?.takeIf { it.privateProbeCandidate }
            ?: return null
        if (!isAdapterEnabled(product.adapterId)) return null
        if (session.confirmedNoiseMode == null) removeFeatureState(NoiseModeFeatureState.FEATURE_ID)
        val next = OppoCatalogEarbudAdapter(product, session, runtimeState())
        // The transferred conversation must consult the current adapter's policy, including
        // policy updates made after an earlier identity refinement.
        session.productAdmission = { next.isAdapterEnabled(it) }
        next.acceptReportedModes(session.confirmedNoiseMode != null)
        return selectReplacement(next, AdapterActivation.KEEP_CHANNEL_READY)
    }

    internal fun acceptReportedModes(reported: Boolean) {
        if (reported) {
            onCapabilitiesIdentified(false, setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY))
        }
    }

    companion object {
        const val ID = "oppo-enco-family"
        val PRESENTATION_ID = MiLinkCardPresentationId("oppo-settings-noise")
        val SETTINGS_PRESENTATION_ID = MiLinkCardPresentationId("oppo-settings-only")
        const val OPPO_RFCOMM_UUID = "0000079a-d102-11e1-9b23-00025b00a5a5"
    }
}

/** Exact retail models whose documented OPPO wire mapping may be probed safely. */
abstract class OppoModelEarbudAdapter : OppoEarbudAdapter() {
    final override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
}

/** OPPO Enco Air2 Pro reverses the family's standard ANC and off values. */
class OppoEncoAir2ProAdapter : OppoModelEarbudAdapter() {

    override val id: String = ID
    override val displayName: String = "OPPO Enco Air2 Pro"
    override val wireConfig: OppoWireConfig = OppoWireConfig.COMPATIBLE

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            OppoProductCatalog.exact(identity.deviceName.orEmpty())?.productId == 0x063810

    companion object { const val ID = "oppo-enco-air2-pro" }
}

/** Named configuration reserved for Free4's documented adaptive/spatial extensions. */
class OppoEncoFree4Adapter : OppoModelEarbudAdapter() {

    override val id: String = ID
    override val displayName: String = "OPPO Enco Free4"

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            OppoProductCatalog.exact(identity.deviceName.orEmpty())?.productId == 0x068C10

    companion object { const val ID = "oppo-enco-free4" }
}

/** Named configuration reserved for Enco X3's documented spatial-audio extensions. */
class OppoEncoX3Adapter : OppoModelEarbudAdapter() {

    override val id: String = ID
    override val displayName: String = "OPPO Enco X3"

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            OppoProductCatalog.exact(identity.deviceName.orEmpty())?.productId == 0x067410

    companion object { const val ID = "oppo-enco-x3" }
}

/** Named configuration reserved for Enco Air5's documented spatial-sound extensions. */
class OppoEncoAir5Adapter : OppoModelEarbudAdapter() {

    override val id: String = ID
    override val displayName: String = "OPPO Enco Air5"

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            OppoProductCatalog.exact(identity.deviceName.orEmpty())?.productId == 0x06D810

    companion object { const val ID = "oppo-enco-air5" }
}

/** Exact catalog identity; shared protocol flags never grant capabilities. */
class OppoCatalogEarbudAdapter internal constructor(
    private val product: OppoProductCatalog.Product,
    transferredSession: ProtocolSession? = null,
    initialRuntimeState: AdapterRuntimeState = AdapterRuntimeState(),
) : OppoEarbudAdapter(transferredSession, initialRuntimeState) {
    override val id: String = product.adapterId
    override val displayName: String = product.displayName
    private val identifiedSession = transferredSession as? OppoProtocolSession
    override val resolution: AdapterResolution
        get() = if (identifiedSession?.productId == product.productId) {
            AdapterResolution.PROTOCOL_CONFIRMED
        } else {
            AdapterResolution.EXACT_MATCH
        }
    override val wireConfig: OppoWireConfig = product.wireConfig

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) && product.privateProbeCandidate &&
            OppoProductCatalog.exact(identity.deviceName.orEmpty())?.productId == product.productId
}

/**
 * Model-owned interpretation of the raw ANC values.
 *
 * Adaptive and intensity variants remain read-compatible, but HyperEars intentionally exposes
 * only MiLink's native ANC/off/transparency controls until the common domain gains a distinct
 * adaptive mode.
 */
data class OppoWireConfig(
    val ancPrimary: Int,
    val offPrimary: Int,
) {
    companion object {
        val STANDARD = OppoWireConfig(ancPrimary = 0x02, offPrimary = 0x01)
        val COMPATIBLE = OppoWireConfig(ancPrimary = 0x01, offPrimary = 0x02)
    }
}

internal class OppoProtocolSession(
    initialConfiguration: OppoWireConfig,
    internal var productAdmission: (String) -> Boolean,
) : ProtocolSession {
    private var configuration = initialConfiguration
    var productId: Int? = null
        private set
    var confirmedNoiseMode: NoiseMode? = null
        private set
    private val settings = OppoSettingsSession()
    private val decoder = OppoWireCodec.Decoder()
    private var handshakePublished = false
    private var battery = EarbudBattery()
    private var pendingNotificationRegistration: ByteArray? = null

    override fun initialReadCommands(): List<ByteArray> = listOf(
        OppoWireCodec.queryProductId,
        OppoWireCodec.queryNotificationSupport,
        OppoWireCodec.queryBattery,
        OppoWireCodec.queryAnc,
    ) + settings.initialQueries

    override fun encode(request: ControlRequest): List<ByteArray> = when {
        request is OppoControlRequest -> settings.encode(request)
        request === StandardControlRequest.Refresh -> initialReadCommands()
        request is StandardControlRequest.SetNoiseMode -> request.mode.toWireCommand()?.let(::listOf).orEmpty()
        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when {
        request is OppoControlRequest -> settings.readback(request)
        request === StandardControlRequest.Refresh -> emptyList()
        request is StandardControlRequest.SetNoiseMode -> listOf(OppoWireCodec.queryAnc)
        else -> emptyList()
    }

    override fun followUpCommands(event: ProtocolEvent): List<ByteArray> {
        if (event !== ProtocolEvent.HandshakeAccepted) return emptyList()
        return pendingNotificationRegistration
            ?.also { pendingNotificationRegistration = null }
            ?.let(::listOf)
            .orEmpty()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> =
        decoder.offer(bytes).flatMap { frame ->
            buildList {
                OppoWireCodec.parseProductId(frame)?.let { identified ->
                    val product = OppoProductCatalog.find(identified)?.takeIf { it.privateProbeCandidate }
                    // Reject a disabled identity before it can alter the active dialect or
                    // invalidate report evidence. Adapter replacement applies the same gate.
                    if (product != null && !productAdmission(product.adapterId)) return@buildList
                    val nextConfiguration = product?.wireConfig ?: configuration
                    if (configuration != nextConfiguration) confirmedNoiseMode = null
                    configuration = nextConfiguration
                    publishHandshakeIfNeeded()
                    if (productId != identified) {
                        productId = identified
                        settings.identify(productId)?.let { add(ProtocolEvent.FeatureStateChanged(it)) }
                        add(ProtocolEvent.ProductIdentified(identified))
                    }
                    return@buildList
                }
                settings.offer(frame, productId)?.let {
                    publishHandshakeIfNeeded()
                    add(ProtocolEvent.FeatureStateChanged(it))
                    return@buildList
                }
                OppoWireCodec.parseNotificationSupport(frame)?.let { advertisedIds ->
                    val subscribableIds = advertisedIds.filterNot { it.isDebugNotification() }
                        .toByteArray()
                    pendingNotificationRegistration = subscribableIds
                        .takeIf(ByteArray::isNotEmpty)
                        ?.let(OppoWireCodec::registerNotifications)
                    handshakePublished = true
                    add(ProtocolEvent.HandshakeAccepted)
                    return@buildList
                }
                OppoWireCodec.parseBatteryState(frame)?.let { state ->
                    publishHandshakeIfNeeded()
                    add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                    battery = battery.copy(
                        left = state.left?.toDomainReading() ?: battery.left,
                        right = state.right?.toDomainReading() ?: battery.right,
                        case = state.case?.toDomainReading() ?: battery.case,
                    )
                    add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(battery)))
                    return@buildList
                }
                OppoWireCodec.parseAncState(frame)
                    ?.toDomainMode()
                    ?.let { mode ->
                        confirmedNoiseMode = mode
                        publishHandshakeIfNeeded()
                        add(
                            ProtocolEvent.CapabilitiesIdentified(
                                battery = false,
                                noiseModes = OPPO_NOISE_MODES,
                            ),
                        )
                        add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
                        return@buildList
                    }
                add(
                    ProtocolEvent.UnknownFrame(
                        version = 1,
                        vendor = OPPO_LOG_VENDOR,
                        command = frame.command,
                        payloadSize = frame.payload.size,
                    ),
                )
            }
        }

    override fun reset() {
        decoder.reset()
        settings.reset()
        productId = null
        confirmedNoiseMode = null
        handshakePublished = false
        battery = EarbudBattery()
        pendingNotificationRegistration = null
    }

    private fun MutableList<ProtocolEvent>.publishHandshakeIfNeeded() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }

    private fun NoiseMode.toWireCommand(): ByteArray? = when (this) {
        NoiseMode.ANC -> OppoWireCodec.setAnc(configuration.ancPrimary)
        NoiseMode.OFF -> OppoWireCodec.setAnc(configuration.offPrimary)
        NoiseMode.TRANSPARENCY -> OppoWireCodec.setAnc(primary = 0x04)
        NoiseMode.WIND -> null
    }

    private fun OppoWireCodec.AncState.toDomainMode(): NoiseMode? = when {
        primary == configuration.ancPrimary && secondary in setOf(null, 0x00) -> NoiseMode.ANC
        primary == configuration.offPrimary && secondary in setOf(null, 0x00) -> NoiseMode.OFF
        primary == 0x04 && secondary in setOf(null, 0x00) -> NoiseMode.TRANSPARENCY
        primary == 0x00 && secondary in setOf(0x01, 0x02) -> NoiseMode.TRANSPARENCY
        primary == 0x00 && secondary == 0x08 -> NoiseMode.ANC
        primary == 0x08 && secondary in setOf(null, 0x00) -> NoiseMode.OFF
        primary in ANC_INTENSITY_VALUES && secondary in setOf(null, 0x00) -> NoiseMode.ANC
        else -> null
    }

    private fun OppoWireCodec.BatteryReading.toDomainReading(): BatteryReading =
        BatteryReading(percent = percent, charging = charging)

    private fun Byte.isDebugNotification(): Boolean =
        (toInt() and 0xF0) == 0xF0

    private companion object {
        const val OPPO_LOG_VENDOR = 0x4F50
        val ANC_INTENSITY_VALUES = setOf(0x10, 0x20, 0x40, 0x80)
        val OPPO_NOISE_MODES = setOf(
            NoiseMode.ANC,
            NoiseMode.OFF,
            NoiseMode.TRANSPARENCY,
        )
    }
}
