package dev.hyperears.integration

import dev.hyperears.protocol.oppo.OppoWireCodec

/** Shared classic OPO protocol behavior. Each brand supplies its own identity and matching rule. */
abstract class OpoProtocolAdapter : StandardEarbudAdapter() {
    abstract override val id: String
    abstract override val displayName: String
    abstract override val resolution: AdapterResolution
    abstract override fun matches(identity: EarbudIdentity): Boolean
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
            uuid = RFCOMM_UUID,
            id = "oppo-private-rfcomm",
        ),
    )
    open val wireConfig: OppoWireConfig = OppoWireConfig.STANDARD

    override fun createProtocolSession(): ProtocolSession =
        OppoProtocolSession(wireConfig)

    companion object {
        const val RFCOMM_UUID = "0000079a-d102-11e1-9b23-00025b00a5a5"
    }
}

/** OPPO/Enco names select a candidate; valid protocol reports unlock private capabilities. */
open class OppoEarbudAdapter : OpoProtocolAdapter() {
    override val id: String = ID
    override val displayName: String = "OPPO Enco headset"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name.contains("oppo") || name.contains("enco")
    }

    companion object {
        const val ID = "oppo-enco-family"
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
            normalizeDeviceName(identity.deviceName.orEmpty()).contains("encoair2pro")

    companion object { const val ID = "oppo-enco-air2-pro" }
}

/** Named configuration reserved for Free4's documented adaptive/spatial extensions. */
class OppoEncoFree4Adapter : OppoModelEarbudAdapter() {

    override val id: String = ID
    override val displayName: String = "OPPO Enco Free4"

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()).contains("encofree4")

    companion object { const val ID = "oppo-enco-free4" }
}

/** Named configuration reserved for Enco X3's documented spatial-audio extensions. */
class OppoEncoX3Adapter : OppoModelEarbudAdapter() {

    override val id: String = ID
    override val displayName: String = "OPPO Enco X3"

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()).contains("encox3")

    companion object { const val ID = "oppo-enco-x3" }
}

/** Named configuration reserved for Enco Air5's documented spatial-sound extensions. */
class OppoEncoAir5Adapter : OppoModelEarbudAdapter() {

    override val id: String = ID
    override val displayName: String = "OPPO Enco Air5"

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()).contains("encoair5")

    companion object { const val ID = "oppo-enco-air5" }
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

private class OppoProtocolSession(
    private val configuration: OppoWireConfig,
) : ProtocolSession {
    private val decoder = OppoWireCodec.Decoder()
    private var handshakePublished = false
    private var battery = EarbudBattery()
    private var pendingNotificationRegistration: ByteArray? = null

    override fun initialReadCommands(): List<ByteArray> = listOf(
        OppoWireCodec.queryNotificationSupport,
        OppoWireCodec.queryBattery,
        OppoWireCodec.queryAnc,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when {
        request === StandardControlRequest.Refresh -> initialReadCommands()
        request is StandardControlRequest.SetNoiseMode -> request.mode.toWireCommand()?.let(::listOf).orEmpty()
        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when {
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
