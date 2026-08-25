package dev.hyperears.integration

import dev.hyperears.protocol.edifier.EdifierV1WireCodec
import dev.hyperears.protocol.edifier.EdifierWireCodec

/**
 * Shared Edifier (BES/恒玄) headset behavior.
 *
 * Family detection is passive: the Bluetooth name is read from the already-connected system
 * device. The private channel then queries device capabilities and battery through Edifier's
 * proprietary SPP protocol.
 */
open class EdifierEarbudAdapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "Edifier headset"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.edifierConnect)
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness =
        TransportReadiness.PROTOCOL_HANDSHAKE
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = EdifierMiLinkPresentationIds.FOUR_MODE.takeIf {
            effectiveCapabilities().windNoiseControl
        }
    protected open val wireConfig: EdifierWireConfig = EdifierWireConfig(
        batteryProjection = EdifierBatteryProjection.TWS_AGGREGATE,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.ServiceUuid(
            uuid = EDF_SPP_UUID,
            id = "edifier-spp-uuid",
        ),
        RfcommEndpointSpec.Channel(number = 1),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        val advertisedService = identity.serviceUuids.any {
            it.equals(EDF_SPP_UUID, ignoreCase = true)
        }
        return advertisedService || EDIFIER_NAME_MARKERS.any(name::contains)
    }

    override fun createProtocolSession(): ProtocolSession =
        EdifierProtocolSession(wireConfig)

    companion object {
        const val ID = "edifier-family"
        const val EDF_SPP_UUID = "EDF00000-EDFE-DFED-FEDF-EDFEDFEDFEDF"

        private val EDIFIER_NAME_MARKERS = setOf(
            "edifier",
            "漫步者",
            "w860nb",
            "w820nb",
            "w830nb",
            "evopro",
            "花再",
        )
    }
}

/**
 * Edifier over-ear headphones family.
 *
 * The W860NB PRO is a headphones form factor. Bluetooth device class or name markers
 * distinguish headphones from TWS earbuds.
 */
open class EdifierHeadphonesAdapter : EdifierEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "Edifier headphones"
    override val formFactor: HeadsetFormFactor = HeadsetFormFactor.HEADPHONES
    override val wireConfig: EdifierWireConfig = EdifierWireConfig(
        batteryProjection = EdifierBatteryProjection.OVERALL,
    )

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) && (
            identity.bluetoothDeviceClass == BLUETOOTH_DEVICE_CLASS_HEADPHONES ||
                normalizeDeviceName(identity.deviceName.orEmpty()).let { name ->
                    HEADPHONE_MARKERS.any(name::contains)
                }
        )

    companion object {
        const val ID = "edifier-headphones-family"

        // android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES
        const val BLUETOOTH_DEVICE_CLASS_HEADPHONES = 0x0418

        private val HEADPHONE_MARKERS = setOf(
            "w860nb",
            "w820nb",
            "w830nb",
            "stax",
        )
    }
}

/**
 * Concrete model adapter for Edifier W860NB PRO.
 *
 * Selected by exact normalized Bluetooth name match. The private protocol queries
 * device capabilities (D8) and battery level after connection.
 */
class EdifierW860NBProAdapter : EdifierHeadphonesAdapter() {

    override val id: String = ID
    override val displayName: String = "Edifier W860NB PRO"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val wireConfig: EdifierWireConfig = EdifierWireConfig(
        batteryQueries = listOf(EdifierBatteryQuery.BATTERY),
        ancDialects = listOf(EdifierAncDialects.W860_NB_PRO),
        preferredAncIndex = EdifierAncDialects.W860_NB_PRO.index,
    )
    /**
     * The W860NB PRO plays a voice prompt for ~1.9 s after an ANC switch and ignores commands
     * during the prompt. The request policy keeps this pacing at the session boundary.
     */
    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy =
        super.controlPolicy(request).let { policy ->
            if (request is StandardControlRequest.SetNoiseMode) {
                policy.copy(
                    confirmation = ControlConfirmationPolicy.PUBLISH_AFTER_WRITE,
                    cooldownMs = 1_800L,
                )
            } else {
                policy
            }
        }

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) == "edifierw860nbpro"

    companion object {
        const val ID = "edifier-w860nb-pro"
    }
}

/**
 * Concrete model adapter for Edifier 花再 Evo Pro.
 *
 * Contributor captures confirm the shared BES framing, an independent ANC slot (`0x1B`), the
 * six-value noise-mode dialect, and component TWS battery telemetry delivered by command `0xF2`.
 */
class EdifierEvoProAdapter : EdifierEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "Edifier 花再 Evo Pro"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val wireConfig: EdifierWireConfig = EdifierWireConfig(
        batteryQueries = listOf(EdifierBatteryQuery.DEVICE_STATE),
        batteryProjection = EdifierBatteryProjection.TWS_AGGREGATE,
        ancDialects = listOf(EdifierAncDialects.EVO_PRO),
        preferredAncIndex = EdifierAncDialects.EVO_PRO.index,
    )
    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy =
        super.controlPolicy(request).let { policy ->
            if (request is StandardControlRequest.SetNoiseMode) {
                policy.copy(confirmation = ControlConfirmationPolicy.PUBLISH_AFTER_WRITE)
            } else {
                policy
            }
        }

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            "evopro" in normalizeDeviceName(identity.deviceName.orEmpty())

    companion object {
        const val ID = "edifier-evo-pro"
    }
}

/**
 * Concrete model adapter for Edifier FitClip Ultra.
 *
 * FitClip Ultra is an open-ear clip TWS. Live on-device probing (RFCOMM to the shared Edifier
 * BES SPP service `EDF00000-...`) confirms:
 *
 *  - Battery is delivered by the TWS device-state command `0xF2` as independent left/right
 *    levels (the legacy aggregate battery command `0xD0` is not answered).
 *  - The device has no ANC: the `0xCC` ANC-state query is not answered and the headset drops
 *    the RFCOMM channel when probed. The adapter therefore disables ANC discovery entirely so
 *    the handshake never emits `0xCC`.
 */
class EdifierFitClipUltraAdapter : EdifierEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "Edifier FitClip Ultra"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val wireConfig: EdifierWireConfig = EdifierWireConfig(
        batteryQueries = listOf(EdifierBatteryQuery.DEVICE_STATE),
        batteryProjection = EdifierBatteryProjection.TWS_AGGREGATE,
        ancDialects = emptyList(),
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        return normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES
    }

    companion object {
        const val ID = "edifier-fitclip-ultra"

        private val MODEL_NAMES = setOf(
            "edifierfitclipultra",
            "fitclipultra",
        )
    }
}

/**
 * Concrete model adapter for Edifier W820NB 双金标版 (Double Gold Label).
 *
 * Verified on real hardware (Edifier Connect v8.4.48 live RFCOMM capture). The device speaks the
 * V1 (bleVersion=1) BES framing — `[0xAA][LEN][CMD][PAYLOAD][CRC16]` with the 8217-seeded 16-bit
 * checksum and no payload encryption — instead of the BES v2 framing used by the rest of the
 * family. Battery is reported by `0xD0` as a single plaintext percent byte; ANC state by `0xCC`
 * as `[mode][level]`; ANC writes by `0xC1` as `[mode]` or `[mode][level]`.
 *
 * Verified ANC dialect: 1=标准(off), 2=降噪(ANC), 3=通透(transparency, adjustable level).
 */
class EdifierW820NBDoubleGoldAdapter : EdifierHeadphonesAdapter() {
    override val id: String = ID
    override val displayName: String = "Edifier W820NB 双金标版"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val controlRequestContract: ControlRequestContract =
        StandardControlRequestContract.extending { adapter, request ->
            request is EdifierControlRequest.SetGameMode &&
                adapter.runtimeState().features.get<GameModeFeatureState>() != null
        }
    override val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract.extending { _, state ->
            state is GameModeFeatureState
        }
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = EdifierMiLinkPresentationIds.W820NB.takeIf {
            effectiveCapabilities().noiseControl
        }

    override fun createProtocolSession(): ProtocolSession = EdifierV1ProtocolSession()

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return "w820nb" in name && "双金标" in name
    }

    companion object {
        const val ID = "edifier-w820nb-double-gold"
    }
}

object EdifierMiLinkPresentationIds {
    val FOUR_MODE = MiLinkCardPresentationId("edifier-four-mode")
    val W820NB = MiLinkCardPresentationId("edifier-w820nb-double-gold")
}

enum class EdifierBatteryQuery(val commandIndex: Int) {
    BATTERY(EdifierWireCodec.CMD_BATTERY_QUERY),
    DEVICE_STATE(EdifierWireCodec.CMD_DEVICE_STATE_QUERY),
}

enum class EdifierBatteryProjection {
    /** Headphones and other single-pack devices expose one authoritative battery value. */
    OVERALL,

    /** Legacy TWS aggregate values are mirrored only when the wire response is aggregate. */
    TWS_AGGREGATE,
}

data class EdifierAncDialect(
    val index: Int,
    val readValues: Map<Int, NoiseMode>,
    val writeValues: Map<NoiseMode, Int>,
) {
    val supportedModes: Set<NoiseMode> = writeValues.keys

    init {
        require(index in 0..0xFF)
        require(writeValues.isNotEmpty())
        require(writeValues.keys.all(readValues.values::contains))
    }
}

object EdifierAncDialects {
    val W860_NB_PRO = EdifierAncDialect(
        index = EdifierWireCodec.ANC_INDEX,
        readValues = mapOf(
            1 to NoiseMode.ANC,
            2 to NoiseMode.ANC,
            3 to NoiseMode.WIND,
            4 to NoiseMode.TRANSPARENCY,
            5 to NoiseMode.OFF,
        ),
        writeValues = mapOf(
            NoiseMode.ANC to 1,
            NoiseMode.WIND to 3,
            NoiseMode.TRANSPARENCY to 4,
            NoiseMode.OFF to 5,
        ),
    )

    val EVO_PRO = EdifierAncDialect(
        index = 0x1B,
        readValues = mapOf(
            1 to NoiseMode.ANC,
            2 to NoiseMode.ANC,
            3 to NoiseMode.ANC,
            4 to NoiseMode.WIND,
            5 to NoiseMode.TRANSPARENCY,
            6 to NoiseMode.OFF,
        ),
        writeValues = mapOf(
            NoiseMode.ANC to 1,
            NoiseMode.WIND to 4,
            NoiseMode.TRANSPARENCY to 5,
            NoiseMode.OFF to 6,
        ),
    )
}

/** Wire facts known before a session starts; family candidates probe each known dialect. */
data class EdifierWireConfig(
    val batteryQueries: List<EdifierBatteryQuery> = listOf(
        EdifierBatteryQuery.BATTERY,
    ),
    val batteryProjection: EdifierBatteryProjection = EdifierBatteryProjection.OVERALL,
    val ancDialects: List<EdifierAncDialect> = listOf(
        EdifierAncDialects.W860_NB_PRO,
        EdifierAncDialects.EVO_PRO,
    ),
    val preferredAncIndex: Int? = null,
) {
    init {
        require(batteryQueries.isNotEmpty())
        require(ancDialects.map(EdifierAncDialect::index).distinct().size == ancDialects.size)
        require(preferredAncIndex == null || ancDialects.any { it.index == preferredAncIndex })
    }

    fun dialect(index: Int): EdifierAncDialect? = ancDialects.firstOrNull { it.index == index }
}

/**
 * Edifier private protocol state machine.
 *
 * Uses the BES/恒玄 SPP framing to query battery, ANC state, and device capabilities.
 * Frame format: [0xBB/0xCC][APP_CODE][CMD][LEN_H][LEN_L][PAYLOAD...][CRC8]
 */
private class EdifierProtocolSession(
    private val configuration: EdifierWireConfig,
) : ProtocolSession {
    private val decoder = EdifierWireCodec.Decoder()
    private var handshakePublished = false
    private var activeAncDialect: EdifierAncDialect? =
        configuration.preferredAncIndex?.let(configuration::dialect)

    override fun initialReadCommands(): List<ByteArray> = buildList {
        addAll(configuration.batteryQueries.map { batteryQueryPacket(it) })
        if (configuration.ancDialects.isNotEmpty()) {
            add(EdifierWireCodec.queryAnc)
        }
        add(EdifierWireCodec.queryFunction)
    }

    override fun encode(request: ControlRequest): List<ByteArray> = when {
        request === StandardControlRequest.Refresh -> buildList {
            addAll(configuration.batteryQueries.map { batteryQueryPacket(it) })
            if (configuration.ancDialects.isNotEmpty()) {
                add(EdifierWireCodec.queryAnc)
            }
        }
        request is StandardControlRequest.SetNoiseMode -> {
            val dialect = activeAncDialect
            val ancValue = dialect?.writeValues?.get(request.mode)
            if (ancValue != null) {
                listOf(EdifierWireCodec.setAnc(ancValue = ancValue, ancIndex = dialect.index))
            } else {
                emptyList()
            }
        }

        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when {
        // The W860NB PRO executes ANC writes immediately and reports state via the write
        // acknowledgement. Skip the extra readback round-trip to reduce perceived latency.
        request === StandardControlRequest.Refresh -> emptyList()
        request is StandardControlRequest.SetNoiseMode -> emptyList()
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            val acceptsBatteryCommand = configuration.batteryQueries.any {
                it.commandIndex == frame.commandIndex
            }
            EdifierWireCodec.parseBatteryState(frame)
                ?.takeIf { acceptsBatteryCommand }
                ?.let { battery ->
                    add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                    add(
                        ProtocolEvent.FeatureStateChanged(
                            BatteryFeatureState(battery.toDomainBattery()),
                        ),
                    )
                    publishHandshakeIfNeeded()
                    return@forEach
                }

            EdifierWireCodec.parseAncState(frame)?.let { anc ->
                val dialect = configuration.dialect(anc.mode) ?: return@let
                activeAncDialect = dialect
                val mode = anc.level?.let(dialect.readValues::get)
                if (mode != null) {
                    add(
                        ProtocolEvent.CapabilitiesIdentified(
                            battery = false,
                            noiseModes = dialect.supportedModes,
                        ),
                    )
                    add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
                }
                publishHandshakeIfNeeded()
                return@forEach
            }

            // Function query response (D8) — confirms device capabilities
            if (
                frame.commandIndex == EdifierWireCodec.CMD_FUNCTION_QUERY &&
                EdifierWireCodec.isProtocolResponse(frame)
            ) {
                // This proves the BES command family only. It does not itself prove that a
                // battery or ANC command is implemented by this particular headset.
                publishHandshakeIfNeeded()
                return@forEach
            }

            add(
                ProtocolEvent.UnknownFrame(
                    version = 0,
                    vendor = 0,
                    command = frame.commandIndex,
                    payloadSize = frame.payload.size,
                ),
            )
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
        activeAncDialect = configuration.preferredAncIndex?.let(configuration::dialect)
    }

    private fun MutableList<ProtocolEvent>.publishHandshakeIfNeeded() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }

    private fun EdifierWireCodec.BatteryState.toDomainBattery(): EarbudBattery = when (this) {
        is EdifierWireCodec.BatteryState.Aggregate -> when (configuration.batteryProjection) {
            EdifierBatteryProjection.OVERALL -> EarbudBattery(
                overall = BatteryReading(percent, charging = false),
            )
            EdifierBatteryProjection.TWS_AGGREGATE -> EarbudBattery.fromAggregate(percent)
        }

        is EdifierWireCodec.BatteryState.TwsComponents -> EarbudBattery(
            left = BatteryReading(leftPercent, charging = false),
            right = BatteryReading(rightPercent, charging = false),
        )
    }

    private companion object {
        fun batteryQueryPacket(query: EdifierBatteryQuery): ByteArray =
            when (query) {
                EdifierBatteryQuery.BATTERY -> EdifierWireCodec.queryBattery
                EdifierBatteryQuery.DEVICE_STATE -> EdifierWireCodec.queryDeviceState
            }
    }
}

/**
 * W820NB 双金标版 V1 protocol state machine.
 *
 * Uses the V1 (bleVersion=1) BES framing: no encryption, `[header][len][cmd][payload][crc16]`
 * with CRC16 = (8217 + byte sum) big-endian. Battery opens from a valid `0xD0` response, noise
 * modes from a valid `0xCC` response; `0xD8` confirms the BES transport only.
 */
private class EdifierV1ProtocolSession : ProtocolSession {
    private val decoder = EdifierV1WireCodec.Decoder()
    private var handshakePublished = false

    override fun initialReadCommands(): List<ByteArray> = listOf(
        EdifierV1WireCodec.queryBattery,
        EdifierV1WireCodec.queryAnc,
        EdifierV1WireCodec.queryGameState,
        EdifierV1WireCodec.queryFunction,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> listOf(
            EdifierV1WireCodec.queryBattery,
            EdifierV1WireCodec.queryAnc,
            EdifierV1WireCodec.queryGameState,
        )

        is StandardControlRequest.SetNoiseMode -> {
            val value = when (request.mode) {
                NoiseMode.OFF -> EdifierV1WireCodec.ANC_MODE_STANDARD
                NoiseMode.ANC -> EdifierV1WireCodec.ANC_MODE_NOISE_CANCELLATION
                NoiseMode.TRANSPARENCY -> EdifierV1WireCodec.ANC_MODE_TRANSPARENCY
                else -> null
            }
            if (value != null) {
                listOf(EdifierV1WireCodec.setAnc(value))
            } else {
                emptyList()
            }
        }

        is EdifierControlRequest.SetGameMode ->
            listOf(EdifierV1WireCodec.setGameState(request.enabled))

        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = emptyList()

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            EdifierV1WireCodec.parseBatteryState(frame)?.let { percent ->
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                overall = BatteryReading(percent, charging = false),
                            ),
                        ),
                    ),
                )
                publishHandshakeIfNeeded()
                return@forEach
            }

            EdifierV1WireCodec.parseAncState(frame)?.let { anc ->
                val mode = when (anc.mode) {
                    EdifierV1WireCodec.ANC_MODE_STANDARD -> NoiseMode.OFF
                    EdifierV1WireCodec.ANC_MODE_NOISE_CANCELLATION -> NoiseMode.ANC
                    EdifierV1WireCodec.ANC_MODE_TRANSPARENCY -> NoiseMode.TRANSPARENCY
                    else -> null
                }
                if (mode != null) {
                    add(
                        ProtocolEvent.CapabilitiesIdentified(
                            battery = false,
                            noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
                        ),
                    )
                    add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
                }
                publishHandshakeIfNeeded()
                return@forEach
            }

            EdifierV1WireCodec.parseGameState(frame)?.let { enabled ->
                add(ProtocolEvent.FeatureStateChanged(GameModeFeatureState(enabled)))
                publishHandshakeIfNeeded()
                return@forEach
            }

            // Function query response (D8) — proves the BES transport only.
            if (
                frame.commandIndex == EdifierV1WireCodec.CMD_FUNCTION_QUERY &&
                EdifierV1WireCodec.isProtocolResponse(frame)
            ) {
                publishHandshakeIfNeeded()
                return@forEach
            }

            add(
                ProtocolEvent.UnknownFrame(
                    version = 0,
                    vendor = 0,
                    command = frame.commandIndex,
                    payloadSize = frame.payload.size,
                ),
            )
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
    }

    private fun MutableList<ProtocolEvent>.publishHandshakeIfNeeded() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }
}
