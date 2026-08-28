package dev.hyperears.integration

import dev.hyperears.protocol.sony.SonyHeadphonesWireCodec
import java.util.ArrayDeque

/** Standard Bluetooth fallback for Sony headsets outside a confirmed private-protocol family. */
open class SonyEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "Sony headset"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.sonySoundConnect)

    override fun matches(identity: EarbudIdentity): Boolean =
        identity.isEligibleSonyHeadset() && identity.hasSonyName()

    companion object {
        const val ID = "sony-family"
    }
}

internal enum class SonyBatteryKind {
    SINGLE,
    DUAL,
    DUAL2,
    CASE,
}

internal enum class SonyAmbientDialect {
    NONE,
    STANDARD,
    WIND,
    EXTENDED,
    AMBIENT_ONLY,
    /** 2026-generation three-state control; v2 ambient frames use subtype 0x19. */
    MODERN,
    ;

    val supportsControl: Boolean get() = this != NONE
    val supportsWind: Boolean get() = this == WIND
    val supportsNoiseCancelling: Boolean get() = this != NONE && this != AMBIENT_ONLY
}

internal data class SonyAdapterConfig(
    val id: String,
    val displayName: String,
    val nameMarkers: Set<String>,
    val formFactor: HeadsetFormFactor,
    val batteryKinds: List<SonyBatteryKind>,
    val ambientDialect: SonyAmbientDialect,
    val preferServiceV2: Boolean = false,
    val exactName: Boolean = false,
    val miLinkPresentationId: MiLinkCardPresentationId? = null,
    val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH,
)

/**
 * Sony's common private-protocol layer.
 *
 * It owns the RFCOMM endpoints and the ACK-driven v1/v2 protocol, while immutable configurations only
 * declare product differences. Model identity selects a safe probe configuration; valid feature
 * reports remain the only authority that publishes private capabilities.
 */
open class SonyProtocolFamilyAdapter internal constructor(
    private val configuration: SonyAdapterConfig,
    private val matcher: (EarbudIdentity) -> Boolean,
) : SonyEarbudAdapter() {
    final override val id: String = configuration.id
    final override val displayName: String = configuration.displayName
    final override val formFactor: HeadsetFormFactor = configuration.formFactor
    final override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = configuration.miLinkPresentationId.takeIf {
            effectiveCapabilities().noiseControl
        }
    final override val privateProtocolRequired: Boolean = true
    final override val transportReadiness: TransportReadiness =
        TransportReadiness.PROTOCOL_HANDSHAKE
    final override val capabilities: EarbudCapabilities = EarbudCapabilities(
        battery = true,
        audioHandoff = true,
    )
    final override val supportedNoiseModes: Set<NoiseMode> = emptySet()
    final override val resolution: AdapterResolution = configuration.resolution
    final override val transports: List<EarbudTransportSpec> =
        if (configuration.preferServiceV2) SONY_V2_FIRST_TRANSPORTS else SONY_V1_FIRST_TRANSPORTS

    final override fun matches(identity: EarbudIdentity): Boolean =
        identity.isEligibleSonyHeadset() && matcher(identity)

    final override fun createProtocolSession(): ProtocolSession = SonyHeadphonesProtocolSession(configuration)

}

private class SonyModelAdapter(
    configuration: SonyAdapterConfig,
) : SonyProtocolFamilyAdapter(
    configuration = configuration,
    matcher = { identity ->
        val name = identity.normalizedSonyName()
        if (configuration.exactName) {
            name in configuration.nameMarkers
        } else {
            configuration.nameMarkers.any(name::contains)
        }
    },
)

/** Sony model and family composition root, ordered from exact models to safe fallbacks. */
object SonyAdapterRegistry {
    private val modelConfigs = listOf(
        sonyHeadphones("wh-1000xm2", "Sony WH-1000XM2", "wh1000xm2", ambient = SonyAmbientDialect.WIND),
        sonyHeadphones("wh-1000xm3", "Sony WH-1000XM3", "wh1000xm3", ambient = SonyAmbientDialect.WIND),
        sonyHeadphones("wh-1000xm4", "Sony WH-1000XM4", "wh1000xm4", ambient = SonyAmbientDialect.WIND),
        sonyHeadphones("wh-1000xm5", "Sony WH-1000XM5", "wh1000xm5", ambient = SonyAmbientDialect.STANDARD),
        sonyHeadphones("wh-1000xm6", "Sony WH-1000XM6", "wh1000xm6", ambient = SonyAmbientDialect.STANDARD),
        sonyHeadphones("wh-ch720n", "Sony WH-CH720N", "whch720n", ambient = SonyAmbientDialect.STANDARD),
        sonyHeadphones(
            id = "wh-ult900n",
            displayName = "Sony ULT WEAR",
            marker = "sonyult|ultwear|whult900n",
            ambient = SonyAmbientDialect.EXTENDED,
            preferServiceV2 = true,
        ),
        sonyTw("wf-1000xm3", "Sony WF-1000XM3", "wf1000xm3", SonyBatteryKind.DUAL, SonyAmbientDialect.WIND),
        sonyTw("wf-1000xm4", "Sony WF-1000XM4", "wf1000xm4", SonyBatteryKind.DUAL, SonyAmbientDialect.WIND),
        sonyTw("wf-1000xm5", "Sony WF-1000XM5", "wf1000xm5", SonyBatteryKind.DUAL, SonyAmbientDialect.STANDARD),
        sonyTw(
            id = "wf-1000xm6",
            displayName = "Sony WF-1000XM6",
            marker = "wf1000xm6",
            battery = SonyBatteryKind.DUAL,
            ambient = SonyAmbientDialect.MODERN,
            preferServiceV2 = true,
        ),
        sonyTw("wf-c500", "Sony WF-C500", "wfc500", SonyBatteryKind.DUAL2, SonyAmbientDialect.NONE, hasCase = false),
        sonyTw(
            id = "wf-c510",
            displayName = "Sony WF-C510",
            marker = "wfc510",
            battery = SonyBatteryKind.DUAL2,
            ambient = SonyAmbientDialect.AMBIENT_ONLY,
            miLinkPresentationId = SonyMiLinkPresentationIds.AMBIENT_ONLY,
        ),
        sonyTw("wf-c700n", "Sony WF-C700N", "wfc700n", SonyBatteryKind.DUAL2, SonyAmbientDialect.EXTENDED),
        sonyTw("wf-c710n", "Sony WF-C710N", "wfc710n", SonyBatteryKind.DUAL2, SonyAmbientDialect.EXTENDED),
        sonyTw("wf-sp800n", "Sony WF-SP800N", "wfsp800n", SonyBatteryKind.DUAL, SonyAmbientDialect.STANDARD),
        sonyTw(
            id = "linkbuds-s",
            displayName = "Sony LinkBuds S",
            marker = "linkbudss",
            battery = SonyBatteryKind.DUAL,
            ambient = SonyAmbientDialect.STANDARD,
            preferServiceV2 = true,
        ),
        sonyTw(
            id = "linkbuds",
            displayName = "Sony LinkBuds",
            marker = "linkbuds|sonylinkbuds",
            battery = SonyBatteryKind.DUAL,
            ambient = SonyAmbientDialect.NONE,
            preferServiceV2 = true,
            exactName = true,
        ),
        sonyHeadphones("wi-sp600n", "Sony WI-SP600N", "wisp600n", ambient = SonyAmbientDialect.WIND),
        sonyHeadphones("wi-c100", "Sony WI-C100", "wic100", ambient = SonyAmbientDialect.NONE),
    )

    private val modelFactories: List<() -> EarbudAdapter> = modelConfigs.map { configuration ->
        { SonyModelAdapter(configuration) }
    }

    private val headphonesNoiseFamilyConfig = SonyAdapterConfig(
        id = "sony-headphones-noise-protocol-family",
        displayName = "Sony headphones noise-control family",
        nameMarkers = emptySet(),
        formFactor = HeadsetFormFactor.HEADPHONES,
        batteryKinds = listOf(SonyBatteryKind.SINGLE),
        ambientDialect = SonyAmbientDialect.STANDARD,
        resolution = AdapterResolution.FAMILY_MATCH,
    )

    private val headphonesBatteryFamilyConfig = SonyAdapterConfig(
        id = "sony-headphones-protocol-family",
        displayName = "Sony headphones protocol family",
        nameMarkers = emptySet(),
        formFactor = HeadsetFormFactor.HEADPHONES,
        batteryKinds = listOf(SonyBatteryKind.SINGLE),
        ambientDialect = SonyAmbientDialect.NONE,
        resolution = AdapterResolution.FAMILY_MATCH,
    )

    private val twsNoiseFamilyConfig = SonyAdapterConfig(
        id = "sony-tws-noise-protocol-family",
        displayName = "Sony TWS noise-control family",
        nameMarkers = emptySet(),
        formFactor = HeadsetFormFactor.TWS,
        batteryKinds = listOf(SonyBatteryKind.DUAL, SonyBatteryKind.DUAL2, SonyBatteryKind.CASE),
        ambientDialect = SonyAmbientDialect.STANDARD,
        resolution = AdapterResolution.FAMILY_MATCH,
    )

    private val twsBatteryFamilyConfig = SonyAdapterConfig(
        id = "sony-tws-protocol-family",
        displayName = "Sony TWS protocol family",
        nameMarkers = emptySet(),
        formFactor = HeadsetFormFactor.TWS,
        batteryKinds = listOf(SonyBatteryKind.DUAL, SonyBatteryKind.DUAL2, SonyBatteryKind.CASE),
        ambientDialect = SonyAmbientDialect.NONE,
        resolution = AdapterResolution.FAMILY_MATCH,
    )

    val factories: List<() -> EarbudAdapter> = buildList {
        addAll(modelFactories)
        add {
            SonyProtocolFamilyAdapter(headphonesNoiseFamilyConfig) {
                it.isSonyHeadphonesForm() && it.impliesSonyNoiseControl()
            }
        }
        add {
            SonyProtocolFamilyAdapter(headphonesBatteryFamilyConfig) {
                it.isSonyHeadphonesForm() &&
                    (it.hasSonyModelName() || it.hasSonyPrivateService())
            }
        }
        add {
            SonyProtocolFamilyAdapter(twsNoiseFamilyConfig) {
                !it.isSonyHeadphonesForm() && it.impliesSonyNoiseControl()
            }
        }
        add {
            SonyProtocolFamilyAdapter(twsBatteryFamilyConfig) {
                !it.isSonyHeadphonesForm() &&
                    (it.hasSonyModelName() || it.hasSonyPrivateService())
            }
        }
        add(::SonyEarbudAdapter)
    }

    val adapters: List<EarbudAdapter> get() = factories.map { it() }

    init {
        require(adapters.map(EarbudAdapter::id).distinct().size == adapters.size)
    }
}

object SonyMiLinkPresentationIds {
    val AMBIENT_ONLY = MiLinkCardPresentationId("sony-ambient-only")
}

private class SonyHeadphonesProtocolSession(
    private val configuration: SonyAdapterConfig,
) : ProtocolSession {
    private enum class Version { V1, V2 }

    private data class Request(
        val type: SonyHeadphonesWireCodec.MessageType,
        val payload: ByteArray,
    )

    private val decoder = SonyHeadphonesWireCodec.Decoder()
    private val pendingRequests = ArrayDeque<Request>()
    private val immediateCommands = ArrayDeque<ByteArray>()
    private var version: Version? = null
    private var sequence = 0
    private var awaitingAck = false
    private var battery = EarbudBattery()

    @Synchronized
    override fun initialReadCommands(): List<ByteArray> {
        resetState()
        awaitingAck = true
        return listOf(command(byteArrayOf(0x00, 0x00)))
    }

    @Synchronized
    override fun encode(request: ControlRequest): List<ByteArray> {
        if (version == null) return emptyList()
        when {
            request === StandardControlRequest.Refresh -> enqueueStateReads()
            request is StandardControlRequest.SetNoiseMode -> enqueueNoiseWrite(request.mode)
            else -> return emptyList()
        }
        return nextRequestIfIdle()?.let(::listOf).orEmpty()
    }

    @Synchronized
    override fun drainImmediateCommands(): List<ByteArray> = drainImmediateCommandsLocked()

    @Synchronized
    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            when (frame.type) {
                SonyHeadphonesWireCodec.MessageType.ACK -> handleAck(frame.sequence)
                SonyHeadphonesWireCodec.MessageType.COMMAND_1,
                SonyHeadphonesWireCodec.MessageType.COMMAND_2,
                -> {
                    immediateCommands += SonyHeadphonesWireCodec.encode(
                        type = SonyHeadphonesWireCodec.MessageType.ACK,
                        sequence = 1 - frame.sequence,
                    )
                    addAll(handleCommand(frame.payload))
                }
            }
        }
    }

    @Synchronized
    override fun reset() = resetState()

    private fun handleAck(ackSequence: Int) {
        if (ackSequence == sequence) return
        sequence = ackSequence
        awaitingAck = false
        scheduleNextRequest()
    }

    private fun handleCommand(payload: ByteArray): List<ProtocolEvent> {
        if (payload.isEmpty()) return emptyList()
        if (payload[0] == INIT_REPLY && version == null) {
            version = when (payload.size) {
                4 -> Version.V1
                8 -> Version.V2
                else -> return listOf(ProtocolEvent.HandshakeRejected)
            }
            enqueueStateReads()
            scheduleNextRequest()
            // The init reply identifies Sony's transport dialect only. Capabilities are
            // published later from the corresponding valid state response.
            return listOf(ProtocolEvent.HandshakeAccepted)
        }

        val activeVersion = version ?: return emptyList()
        parseBattery(activeVersion, payload)?.let { updated ->
            battery = updated
            return listOf(
                ProtocolEvent.CapabilitiesIdentified(battery = true),
                ProtocolEvent.FeatureStateChanged(BatteryFeatureState(updated)),
            )
        }
        parseNoiseMode(activeVersion, payload)?.let { mode ->
            return listOf(
                ProtocolEvent.CapabilitiesIdentified(
                    battery = false,
                    noiseModes = supportedModes(configuration.ambientDialect),
                ),
                ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)),
            )
        }
        return listOf(
            ProtocolEvent.UnknownFrame(
                version = if (activeVersion == Version.V1) 1 else 2,
                vendor = SONY_COMPANY_ID,
                command = payload[0].toInt() and 0xFF,
                payloadSize = payload.size,
            ),
        )
    }

    private fun enqueueStateReads() {
        val activeVersion = version ?: return
        configuration.batteryKinds
            .map { kind -> encodeBatteryKind(activeVersion, kind) }
            .distinct()
            .forEach { encodedKind ->
                pendingRequests += Request(
                    type = SonyHeadphonesWireCodec.MessageType.COMMAND_1,
                    payload = byteArrayOf(
                        if (activeVersion == Version.V1) BATTERY_GET_V1 else BATTERY_GET_V2,
                        encodedKind,
                    ),
                )
            }
        if (configuration.ambientDialect.supportsControl) {
            pendingRequests += Request(
                type = SonyHeadphonesWireCodec.MessageType.COMMAND_1,
                payload = byteArrayOf(AMBIENT_GET, ambientSubtype(activeVersion)),
            )
        }
    }

    private fun enqueueNoiseWrite(mode: NoiseMode) {
        val activeVersion = version ?: return
        if (mode !in supportedModes(configuration.ambientDialect)) return
        pendingRequests += Request(
            type = SonyHeadphonesWireCodec.MessageType.COMMAND_1,
            payload = if (activeVersion == Version.V1) {
                encodeAmbientV1(mode)
            } else {
                encodeAmbientV2(mode)
            },
        )
    }

    private fun scheduleNextRequest() {
        nextRequestIfIdle()?.let(immediateCommands::addLast)
    }

    private fun nextRequestIfIdle(): ByteArray? {
        if (awaitingAck || pendingRequests.isEmpty()) return null
        val request = pendingRequests.removeFirst()
        val command = SonyHeadphonesWireCodec.encode(
            type = request.type,
            sequence = sequence,
            payload = request.payload,
        )
        awaitingAck = true
        return command
    }

    private fun command(payload: ByteArray): ByteArray = SonyHeadphonesWireCodec.encode(
        type = SonyHeadphonesWireCodec.MessageType.COMMAND_1,
        sequence = sequence,
        payload = payload,
    )

    private fun parseBattery(version: Version, payload: ByteArray): EarbudBattery? {
        val code = payload[0].toInt() and 0xFF
        val validCode = if (version == Version.V1) {
            code == BATTERY_REPLY_V1 || code == BATTERY_NOTIFY_V1
        } else {
            code == BATTERY_REPLY_V2 || code == BATTERY_NOTIFY_V2
        }
        if (!validCode || payload.size < 4) return null
        val kind = decodeBatteryKind(version, payload[1].toInt() and 0xFF) ?: return null
        return when (kind) {
            SonyBatteryKind.SINGLE -> battery.copy(overall = payload.readingAt(2))
            SonyBatteryKind.CASE -> battery.copy(case = payload.readingAt(2))
            SonyBatteryKind.DUAL,
            SonyBatteryKind.DUAL2,
            -> if (payload.size >= 6) {
                battery.copy(
                    left = payload.readingAt(2, zeroMeansUnavailable = true),
                    right = payload.readingAt(4, zeroMeansUnavailable = true),
                )
            } else {
                null
            }
        }
    }

    private fun parseNoiseMode(version: Version, payload: ByteArray): NoiseMode? {
        val code = payload[0].toInt() and 0xFF
        if (code != AMBIENT_REPLY && code != AMBIENT_NOTIFY) return null
        return if (version == Version.V1) parseAmbientV1(payload) else parseAmbientV2(payload)
    }

    private fun parseAmbientV1(payload: ByteArray): NoiseMode? {
        if (payload.size != 8) return null
        if (payload[2].toInt() == 0) return NoiseMode.OFF
        if (payload[2].toInt() != 1) return null
        return when (payload[3].toInt() and 0xFF) {
            0x00 -> when (payload[4].toInt() and 0xFF) {
                0x00 -> NoiseMode.TRANSPARENCY
                0x01 -> NoiseMode.ANC
                else -> null
            }
            0x02 -> when (payload[4].toInt() and 0xFF) {
                0x00 -> NoiseMode.TRANSPARENCY
                0x01 -> NoiseMode.WIND
                0x02 -> NoiseMode.ANC
                else -> null
            }
            else -> null
        }
    }

    private fun parseAmbientV2(payload: ByteArray): NoiseMode? {
        if (payload.size < 6) return null
        val subtype = payload[1].toInt() and 0xFF
        if (subtype == MODERN_AMBIENT_SUBTYPE.toInt()) {
            // 2026-generation notify: on/off at [3], ANC vs transparency at [4].
            if (payload.size != 9) return null
            if (payload[3].toInt() == 0) return NoiseMode.OFF
            if (payload[3].toInt() != 1) return null
            return if (payload[4].toInt() == 0) NoiseMode.ANC else NoiseMode.TRANSPARENCY
        }
        if (payload.size !in 6..8) return null
        if (subtype !in setOf(0x15, 0x17, 0x22)) return null
        if (payload[3].toInt() == 0) return NoiseMode.OFF
        if (payload[3].toInt() != 1) return null
        if (subtype == 0x22) return NoiseMode.TRANSPARENCY
        if (subtype == 0x17 && payload.size > 7) {
            return when (payload[5].toInt() and 0xFF) {
                0x03, 0x05 -> NoiseMode.WIND
                0x02 -> if (payload[4].toInt() == 0) NoiseMode.ANC else NoiseMode.TRANSPARENCY
                else -> null
            }
        }
        return if (payload[4].toInt() == 0) NoiseMode.ANC else NoiseMode.TRANSPARENCY
    }

    private fun encodeAmbientV1(mode: NoiseMode): ByteArray {
        val wind = configuration.ambientDialect.supportsWind
        val enabled = mode != NoiseMode.OFF
        return byteArrayOf(
            AMBIENT_SET,
            0x02,
            if (enabled) 0x11 else 0x00,
            if (wind) 0x02 else 0x00,
            when {
                wind && mode == NoiseMode.ANC -> 0x02
                wind && mode == NoiseMode.WIND -> 0x01
                !wind && mode == NoiseMode.ANC -> 0x01
                else -> 0x00
            },
            0x01,
            0x00,
            if (mode == NoiseMode.TRANSPARENCY) DEFAULT_AMBIENT_LEVEL else 0x00,
        )
    }

    private fun encodeAmbientV2(mode: NoiseMode): ByteArray {
        if (configuration.ambientDialect == SonyAmbientDialect.MODERN) {
            // The 0x19 write mirrors the device's own notify layout byte-for-byte.
            // If a device ignores the write, the third byte (0x00) is the first variant to try
            // as 0x01, matching the legacy 0x15 write convention.
            return byteArrayOf(
                AMBIENT_SET,
                MODERN_AMBIENT_SUBTYPE,
                0x00,
                if (mode == NoiseMode.OFF) 0x00 else 0x01,
                if (mode == NoiseMode.TRANSPARENCY) 0x01 else 0x00,
                0x00,
                DEFAULT_AMBIENT_LEVEL,
                0x00,
                0x00,
            )
        }
        return buildList<Byte> {
            add(AMBIENT_SET)
            add(ambientSubtype(Version.V2))
            add(0x01)
            add(if (mode == NoiseMode.OFF) 0x00 else 0x01)
            add(if (mode == NoiseMode.TRANSPARENCY) 0x01 else 0x00)
            if (configuration.ambientDialect.supportsWind) {
                add(if (mode == NoiseMode.WIND) 0x03 else 0x02)
            }
            add(0x00)
            add(if (mode == NoiseMode.TRANSPARENCY) DEFAULT_AMBIENT_LEVEL else 0x00)
        }.toByteArray()
    }

    private fun ambientSubtype(version: Version): Byte = when {
        version == Version.V1 -> 0x02
        configuration.ambientDialect == SonyAmbientDialect.MODERN -> MODERN_AMBIENT_SUBTYPE
        configuration.ambientDialect == SonyAmbientDialect.AMBIENT_ONLY -> 0x17
        configuration.ambientDialect == SonyAmbientDialect.EXTENDED ||
            configuration.ambientDialect == SonyAmbientDialect.WIND -> 0x17
        else -> 0x15
    }

    private fun encodeBatteryKind(version: Version, kind: SonyBatteryKind): Byte =
        if (version == Version.V1) {
            when (kind) {
                SonyBatteryKind.SINGLE -> 0x00
                SonyBatteryKind.DUAL,
                SonyBatteryKind.DUAL2,
                -> 0x01
                SonyBatteryKind.CASE -> 0x02
            }
        } else {
            when (kind) {
                SonyBatteryKind.SINGLE -> 0x00
                SonyBatteryKind.DUAL -> 0x09
                SonyBatteryKind.DUAL2 -> 0x01
                SonyBatteryKind.CASE -> 0x0A
            }
        }

    private fun decodeBatteryKind(version: Version, value: Int): SonyBatteryKind? =
        if (version == Version.V1) {
            when (value) {
                0x00 -> SonyBatteryKind.SINGLE
                0x01 -> SonyBatteryKind.DUAL
                0x02 -> SonyBatteryKind.CASE
                else -> null
            }
        } else {
            when (value) {
                0x00 -> SonyBatteryKind.SINGLE
                0x01 -> SonyBatteryKind.DUAL2
                0x09 -> SonyBatteryKind.DUAL
                0x0A -> SonyBatteryKind.CASE
                else -> null
            }
        }

    private fun ByteArray.readingAt(
        index: Int,
        zeroMeansUnavailable: Boolean = false,
    ): BatteryReading {
        val raw = this[index].toInt() and 0xFF
        val percent = raw.takeIf { it in 0..100 && !(zeroMeansUnavailable && it == 0) }
        return BatteryReading(
            percent = percent,
            charging = percent != null && this[index + 1].toInt() == 1,
        )
    }

    private fun drainImmediateCommandsLocked(): List<ByteArray> = buildList {
        while (immediateCommands.isNotEmpty()) add(immediateCommands.removeFirst())
    }

    private fun resetState() {
        decoder.reset()
        pendingRequests.clear()
        immediateCommands.clear()
        version = null
        sequence = 0
        awaitingAck = false
        battery = EarbudBattery()
    }

    private companion object {
        const val SONY_COMPANY_ID = 0x054C
        const val INIT_REPLY: Byte = 0x01
        const val BATTERY_GET_V1: Byte = 0x10
        const val BATTERY_REPLY_V1 = 0x11
        const val BATTERY_NOTIFY_V1 = 0x13
        const val BATTERY_GET_V2: Byte = 0x22
        const val BATTERY_REPLY_V2 = 0x23
        const val BATTERY_NOTIFY_V2 = 0x25
        const val AMBIENT_GET: Byte = 0x66
        const val AMBIENT_REPLY = 0x67
        const val AMBIENT_SET: Byte = 0x68
        const val AMBIENT_NOTIFY = 0x69
        const val DEFAULT_AMBIENT_LEVEL: Byte = 20
        const val MODERN_AMBIENT_SUBTYPE: Byte = 0x19

        fun supportedModes(dialect: SonyAmbientDialect): Set<NoiseMode> = buildSet {
            if (dialect.supportsNoiseCancelling) add(NoiseMode.ANC)
            if (dialect.supportsControl) addAll(setOf(NoiseMode.OFF, NoiseMode.TRANSPARENCY))
            if (dialect.supportsWind) add(NoiseMode.WIND)
        }
    }
}

private val SONY_V1_FIRST_TRANSPORTS: List<EarbudTransportSpec> = listOf(
    RfcommEndpointSpec.ServiceUuid(
        uuid = SonyHeadphonesWireCodec.RFCOMM_SERVICE_V1,
        id = "sony-rfcomm-v1",
    ),
    RfcommEndpointSpec.ServiceUuid(
        uuid = SonyHeadphonesWireCodec.RFCOMM_SERVICE_V2,
        id = "sony-rfcomm-v2",
    ),
)

private val SONY_V2_FIRST_TRANSPORTS: List<EarbudTransportSpec> =
    SONY_V1_FIRST_TRANSPORTS.reversed()

private fun sonyHeadphones(
    id: String,
    displayName: String,
    marker: String,
    ambient: SonyAmbientDialect,
    preferServiceV2: Boolean = false,
): SonyAdapterConfig = SonyAdapterConfig(
    id = "sony-$id",
    displayName = displayName,
    nameMarkers = marker.split('|').toSet(),
    formFactor = HeadsetFormFactor.HEADPHONES,
    batteryKinds = listOf(SonyBatteryKind.SINGLE),
    ambientDialect = ambient,
    preferServiceV2 = preferServiceV2,
)

private fun sonyTw(
    id: String,
    displayName: String,
    marker: String,
    battery: SonyBatteryKind,
    ambient: SonyAmbientDialect,
    hasCase: Boolean = true,
    preferServiceV2: Boolean = false,
    exactName: Boolean = false,
    miLinkPresentationId: MiLinkCardPresentationId? = null,
): SonyAdapterConfig = SonyAdapterConfig(
    id = "sony-$id",
    displayName = displayName,
    nameMarkers = marker.split('|').toSet(),
    formFactor = HeadsetFormFactor.TWS,
    batteryKinds = buildList {
        add(battery)
        if (hasCase) add(SonyBatteryKind.CASE)
    },
    ambientDialect = ambient,
    preferServiceV2 = preferServiceV2,
    exactName = exactName,
    miLinkPresentationId = miLinkPresentationId,
)

private fun EarbudIdentity.isEligibleSonyHeadset(): Boolean =
    standardHeadset && !nativeSystemEarbud && !deviceName.orEmpty().startsWith("LE_", ignoreCase = true)

private fun EarbudIdentity.normalizedSonyName(): String =
    deviceName.orEmpty().lowercase().filter(Char::isLetterOrDigit)

private fun EarbudIdentity.hasSonyName(): Boolean =
    normalizedSonyName().let { name -> name.startsWith("sony") || hasSonyModelName() }

private fun EarbudIdentity.hasSonyModelName(): Boolean = sonyProductName().let { name ->
    name.startsWith("wh") ||
        name.startsWith("wf") ||
        name.hasSonyWiPrefix() ||
        name.startsWith("mdr") ||
        name.startsWith("linkbuds") ||
        name.startsWith("sonyult") ||
        name.startsWith("ultwear")
}

/**
 * Sony's private control-service UUIDs. These are protocol-specific endpoints, not generic
 * Bluetooth profiles. The common Apple iAP2 accessory UUID is deliberately excluded here.
 */
private fun EarbudIdentity.hasSonyPrivateService(): Boolean = serviceUuids.any { uuid ->
    uuid.equals(SonyHeadphonesWireCodec.RFCOMM_SERVICE_V1, ignoreCase = true) ||
        uuid.equals(SonyHeadphonesWireCodec.RFCOMM_SERVICE_V2, ignoreCase = true)
}

private fun EarbudIdentity.isSonyHeadphonesForm(): Boolean {
    val name = sonyProductName()
    return bluetoothDeviceClass == 0x0418 ||
        name.startsWith("wh") ||
        name.hasSonyWiPrefix() ||
        name.startsWith("mdr") ||
        name.startsWith("sonyult") ||
        name.startsWith("ultwear")
}

private fun String.hasSonyWiPrefix(): Boolean =
    startsWith("wic") || startsWith("wisp") || startsWith("wi1000") || startsWith("wih")

private fun EarbudIdentity.impliesSonyNoiseControl(): Boolean {
    val name = sonyProductName()
    if (!hasSonyModelName()) return false
    return name.contains("1000x") ||
        name.endsWith("n") ||
        name.startsWith("sonyult") ||
        name.startsWith("ultwear") ||
        name == "linkbudss"
}

private fun EarbudIdentity.sonyProductName(): String = normalizedSonyName().removePrefix("sony")
