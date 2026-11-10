package dev.hyperears.integration

import dev.hyperears.protocol.jieli.JlAuthCrypto
import dev.hyperears.protocol.jieli.JlRcspWireCodec

/**
 * Concrete model adapter for the DaiLing T3 TWS earbuds.
 *
 * The earbuds carry a JieLi (JL) RCSP control channel over Bluetooth Classic SPP. Matching stays
 * limited to the confirmed retail name; default capabilities remain the Android standard mix
 * (system aggregate battery, media handoff, no noise control, no model card) until the device
 * answers with legal RCSP frames:
 *
 * - a well-formed device-information response confirms the protocol handshake;
 * - a `0xC1`/`0xC2` battery report opens private component battery;
 * - a `FF 0A 0D` noise attribute report opens the three-state noise control and exposes the
 *   model-specific ANC depth selector.
 *
 * Evidence comes from a real-device HCI capture; see `docs/dailing-t3-protocol.md`.
 */
class DailingT3Adapter : StandardEarbudAdapter() {
    override val id: String = ID
    override val displayName: String = "戴灵 T3"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val batterySource: BatterySource = BatterySource.SYSTEM_AGGREGATE
    override val capabilities: EarbudCapabilities = EarbudCapabilities(
        battery = true,
        audioHandoff = true,
    )
    override val transports: List<EarbudTransportSpec> = listOf(
        // The vendor app opens the JL SPP record insecurely on RFCOMM channel 1 (SDP `JL_SPP`).
        // An authenticated socket connects but the headset then ignores every RCSP frame.
        RfcommEndpointSpec.Channel(number = 1, secure = false),
        RfcommEndpointSpec.ServiceUuid(
            uuid = SPP_UUID,
            id = "dailing-t3-spp",
        ),
    )
    override val controlApps: List<ControlAppSpec> = listOf(ControlAppCatalog.dailing)

    override val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract.extending { _, state ->
            state is DailingAncDepthFeatureState ||
                state is DailingGameModeFeatureState ||
                state is DailingDualConnectionFeatureState
        }

    override val controlRequestContract: ControlRequestContract =
        StandardControlRequestContract.extending { adapter, request ->
            when (request) {
                is DailingControlRequest.SetAncDepth ->
                    adapter.effectiveCapabilities().noiseControl &&
                        request.depth in (
                            adapter.runtimeState().features
                                .get<DailingAncDepthFeatureState>()
                                ?.supported
                                .orEmpty()
                            )

                is DailingControlRequest.SetGameMode ->
                    adapter.runtimeState().features
                        .get<DailingGameModeFeatureState>() != null

                is DailingControlRequest.SetDualConnection ->
                    adapter.runtimeState().features
                        .get<DailingDualConnectionFeatureState>() != null

                else -> false
            }
        }

    /** Card extension only after the device confirms the capabilities it extends. */
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = DailingMiLinkPresentationIds.CARD.takeIf {
            runtimeState().features.get<DailingAncDepthFeatureState>() != null
        }

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy = when (request) {
        // The headset acknowledges the depth write without echoing the level, so the accepted
        // request publishes the documented state directly.
        is DailingControlRequest.SetAncDepth -> ControlExecutionPolicy(
            confirmation = ControlConfirmationPolicy.PUBLISH_AFTER_WRITE,
            stateAfterWrite = DailingAncDepthFeatureState(
                current = request.depth,
                supported = DailingAncDepthFeatureState.ALL_DEPTHS,
            ),
            readbackDelayMs = 0L,
        )

        is DailingControlRequest.SetGameMode -> ControlExecutionPolicy(
            confirmation = ControlConfirmationPolicy.PUBLISH_AFTER_WRITE,
            stateAfterWrite = DailingGameModeFeatureState(enabled = request.enabled),
            readbackDelayMs = 0L,
        )

        is DailingControlRequest.SetDualConnection -> ControlExecutionPolicy(
            confirmation = ControlConfirmationPolicy.PUBLISH_AFTER_WRITE,
            stateAfterWrite = DailingDualConnectionFeatureState(enabled = request.enabled),
            readbackDelayMs = 0L,
        )

        else -> super.controlPolicy(request)
    }

    override fun onProtocolReset() {
        super.onProtocolReset()
        removeFeatureState(DailingAncDepthFeatureState.FEATURE_ID)
        removeFeatureState(DailingGameModeFeatureState.FEATURE_ID)
        removeFeatureState(DailingDualConnectionFeatureState.FEATURE_ID)
    }

    /**
     * Last depth level chosen by the user, injected by the host runtime from module settings.
     *
     * The captured vendor app sends no depth on a plain mode switch, so the module keeps the
     * user's choice and re-applies it; without a stored choice the first ANC activation uses
     * [DailingAncDepth.DEEP], because the earbuds default to the lightest level.
     */
    var preferredAncDepth: DailingAncDepth? = null

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES

    override fun createProtocolSession(): ProtocolSession = DailingT3ProtocolSession(
        storedDepth = { preferredAncDepth },
    )

    companion object {
        const val ID = "dailing-t3"

        /** Bluetooth SIG Serial Port Profile. */
        const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

        /** Retail name reported by the earbuds (`DaiLing T3`). */
        private val MODEL_NAMES = setOf(
            "dailingt3",
            "戴灵t3",
        )
    }
}

/**
 * Protocol session for the DaiLing T3 RCSP channel.
 *
 * The session owns the frame decoder, the auth handshake state and the request sequence. It never
 * opens sockets or sleeps: the runtime drives [initialReadCommands], [encode], [query], [offer]
 * and [drainImmediateCommands] on the shared transport.
 *
 * A fresh JL connection starts with a device authentication handshake (see [JlAuthCrypto]); until
 * it completes the earbuds discard every RCSP frame. After authentication the vendor init sequence
 * is replayed, and the headset starts reporting device information, battery and noise state.
 */
internal class DailingT3ProtocolSession(
    private val storedDepth: () -> DailingAncDepth? = { null },
) : ProtocolSession {
    private val decoder = JlRcspWireCodec.Decoder()
    private val pendingCommands = ArrayDeque<ByteArray>()
    private var authBuffer = ByteArray(0)
    private var authCompleted = false
    private var handshakePublished = false
    private var noiseCapabilityPublished = false
    private var sequence = 0
    private var ancDepth: DailingAncDepth? = null
    private var pendingDepthPublication: DailingAncDepth? = null

    override fun initialReadCommands(): List<ByteArray> {
        val random = ByteArray(AUTH_RANDOM_SIZE)
        kotlin.random.Random.nextBytes(random)
        return listOf(JlAuthCrypto.authStart(random))
    }

    override fun followUpCommands(event: ProtocolEvent): List<ByteArray> = when (event) {
        // The headset does not push the noise attribute on connect; read it once so the
        // three-state control can be opened.
        ProtocolEvent.HandshakeAccepted -> listOf(
            JlRcspWireCodec.queryStatus(),
            JlRcspWireCodec.currentNoiseModeQuery(nextSequence()),
            JlRcspWireCodec.queryDualConnection(nextSequence()),
            // The vendor app reads the work mode at connect; without this sync the headset can
            // mute on the first game-mode write after a restart.
            JlRcspWireCodec.queryAdvSettings(nextSequence()),
        )

        else -> emptyList()
    }

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> listOf(JlRcspWireCodec.queryStatus())

        is StandardControlRequest.SetNoiseMode -> {
            val state = request.mode.toWireState()
            if (state == null) {
                emptyList()
            } else {
                val commands = mutableListOf(JlRcspWireCodec.setNoiseMode(state, nextSequence()))
                if (state == JlRcspWireCodec.NoiseState.ANC) {
                    // The headset keeps its own level, but the vendor default is the lightest one;
                    // apply the stored (or first-run deep) level together with ANC.
                    val depth = ancDepth ?: storedDepth() ?: DailingAncDepth.DEEP
                    ancDepth = depth
                    pendingDepthPublication = depth
                    commands += JlRcspWireCodec.setAncDepth(nextSequence(), depth.toWire())
                }
                commands
            }
        }

        is DailingControlRequest.SetAncDepth -> {
            ancDepth = request.depth
            listOf(JlRcspWireCodec.setAncDepth(nextSequence(), request.depth.toWire()))
        }

        is DailingControlRequest.SetGameMode ->
            listOf(JlRcspWireCodec.setGameMode(nextSequence(), request.enabled))

        is DailingControlRequest.SetDualConnection ->
            listOf(JlRcspWireCodec.setDualConnection(nextSequence(), request.enabled))

        else -> emptyList()
    }

    override fun query(request: TelemetryQuery): List<ByteArray> = when (request) {
        TelemetryQuery.RefreshAll -> listOf(JlRcspWireCodec.queryStatus())

        is TelemetryQuery.RefreshFeature -> when (request.featureId) {
            BatteryFeatureState.FEATURE_ID,
            NoiseModeFeatureState.FEATURE_ID,
            -> listOf(JlRcspWireCodec.queryStatus())

            else -> emptyList()
        }
    }

    override fun readback(request: ControlRequest): List<ByteArray> =
        if (request is StandardControlRequest.SetNoiseMode) {
            listOf(JlRcspWireCodec.queryStatus())
        } else {
            emptyList()
        }

    override fun drainImmediateCommands(): List<ByteArray> {
        if (pendingCommands.isEmpty()) return emptyList()
        val drained = pendingCommands.toList()
        pendingCommands.clear()
        return drained
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        if (!authCompleted) {
            handleAuthBytes(bytes)
            return@buildList
        }
        var depthPublished = false
        decoder.offer(bytes).forEach { frame ->
            // The earbuds answer custom commands with the app flag, so this parser runs before the
            // direction filter and does not rely on it.
            JlRcspWireCodec.parseDualConnection(frame)?.let { enabled ->
                add(ProtocolEvent.FeatureStateChanged(DailingDualConnectionFeatureState(enabled)))
                return@forEach
            }

            JlRcspWireCodec.parseWorkMode(frame)?.let { enabled ->
                add(ProtocolEvent.FeatureStateChanged(DailingGameModeFeatureState(enabled)))
                return@forEach
            }

            // Frames written by the vendor app are not device evidence.
            if (!frame.fromDevice) return@forEach

            if (!depthPublished) {
                pendingDepthPublication?.let { depth ->
                    depthPublished = true
                    pendingDepthPublication = null
                    add(
                        ProtocolEvent.FeatureStateChanged(
                            DailingAncDepthFeatureState(
                                current = depth,
                                supported = DailingAncDepthFeatureState.ALL_DEPTHS,
                            ),
                        ),
                    )
                }
            }

            if (JlRcspWireCodec.isDeviceInfo(frame)) {
                publishHandshakeIfNeeded()
                return@forEach
            }

            JlRcspWireCodec.parseBattery(frame)?.let { report ->
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                left = BatteryReading(report.leftPercent, charging = false),
                                right = BatteryReading(report.rightPercent, charging = false),
                            ),
                        ),
                    ),
                )
                publishHandshakeIfNeeded()
                return@forEach
            }

            JlRcspWireCodec.parseNoiseState(frame)?.let { state ->
                val mode = state.toNoiseMode() ?: return@forEach
                if (!noiseCapabilityPublished) {
                    noiseCapabilityPublished = true
                    add(
                        ProtocolEvent.CapabilitiesIdentified(
                            battery = false,
                            noiseModes = SUPPORTED_NOISE_MODES,
                        ),
                    )
                    // The RCSP channel never reports the current depth, so the feature starts
                    // without a current value; a user-selected level is published after write.
                    add(
                        ProtocolEvent.FeatureStateChanged(
                            DailingAncDepthFeatureState(
                                current = storedDepth(),
                                supported = DailingAncDepthFeatureState.ALL_DEPTHS,
                            ),
                        ),
                    )
                    add(
                        ProtocolEvent.FeatureStateChanged(
                            DailingGameModeFeatureState(enabled = null),
                        ),
                    )
                    add(
                        ProtocolEvent.FeatureStateChanged(
                            DailingDualConnectionFeatureState(enabled = null),
                        ),
                    )
                }
                add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
                publishHandshakeIfNeeded()
            }
        }
    }

    override fun reset() {
        decoder.reset()
        pendingCommands.clear()
        authBuffer = ByteArray(0)
        authCompleted = false
        handshakePublished = false
        noiseCapabilityPublished = false
        sequence = 0
    }

    /**
     * Handles the authentication exchange, which uses bare packets instead of RCSP frames.
     *
     * Packet layout follows the vendor SDK: a one-byte stage tag (`0x00` start, `0x01` proof,
     * `0x02` acknowledgement) followed by a 16-byte payload for the first two stages and the
     * literal `pass` for the last one. Unknown bytes are dropped one at a time so the parser
     * resynchronizes; the buffer is bounded so a flooding peer cannot grow it without limit.
     */
    private fun handleAuthBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        authBuffer = (authBuffer + bytes).copyOfLast(MAX_AUTH_BUFFER)
        while (authBuffer.isNotEmpty()) {
            val stage = authBuffer[0].toInt() and 0xFF
            val length = when (stage) {
                AUTH_STAGE_START, AUTH_STAGE_PROOF -> AUTH_PACKET_SIZE
                AUTH_STAGE_ACK -> AUTH_ACK_SIZE
                else -> 0
            }
            if (length == 0) {
                authBuffer = authBuffer.copyOfRange(1, authBuffer.size)
                continue
            }
            if (authBuffer.size < length) return
            val packet = authBuffer.copyOfRange(0, length)
            authBuffer = authBuffer.copyOfRange(length, authBuffer.size)
            when (stage) {
                AUTH_STAGE_START -> pendingCommands.addLast(
                    JlAuthCrypto.authResponse(packet.copyOfRange(1, length)),
                )

                AUTH_STAGE_PROOF -> pendingCommands.addLast(JlAuthCrypto.authOk())

                else -> {
                    authCompleted = true
                    val commands = JlRcspWireCodec.appInitialization(startSequence = 0)
                    sequence = (commands.size - 1) and 0xFF
                    pendingCommands.addAll(commands)
                    return
                }
            }
        }
    }

    private fun MutableList<ProtocolEvent>.publishHandshakeIfNeeded() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }

    private fun nextSequence(): Int {
        sequence = (sequence + 1) and 0xFF
        return sequence
    }

    private fun DailingAncDepth.toWire(): JlRcspWireCodec.AncDepth = when (this) {
        DailingAncDepth.DEEP -> JlRcspWireCodec.AncDepth.DEEP
        DailingAncDepth.MIDDLE -> JlRcspWireCodec.AncDepth.MIDDLE
        DailingAncDepth.LIGHT -> JlRcspWireCodec.AncDepth.LIGHT
    }

    private fun JlRcspWireCodec.NoiseState.toNoiseMode(): NoiseMode? = when (this) {
        JlRcspWireCodec.NoiseState.ANC -> NoiseMode.ANC
        JlRcspWireCodec.NoiseState.OFF -> NoiseMode.OFF
        JlRcspWireCodec.NoiseState.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    }

    private fun NoiseMode.toWireState(): JlRcspWireCodec.NoiseState? = when (this) {
        NoiseMode.ANC -> JlRcspWireCodec.NoiseState.ANC
        NoiseMode.OFF -> JlRcspWireCodec.NoiseState.OFF
        NoiseMode.TRANSPARENCY -> JlRcspWireCodec.NoiseState.TRANSPARENCY
        // The model has no wind-noise mode and no captured attribute block for it.
        NoiseMode.WIND -> null
    }

    private companion object {
        const val AUTH_STAGE_START = 0x00
        const val AUTH_STAGE_PROOF = 0x01
        const val AUTH_STAGE_ACK = 0x02
        const val AUTH_PACKET_SIZE = 17
        const val AUTH_ACK_SIZE = 5
        const val AUTH_RANDOM_SIZE = 16
        const val MAX_AUTH_BUFFER = 64

        val SUPPORTED_NOISE_MODES = setOf(
            NoiseMode.ANC,
            NoiseMode.OFF,
            NoiseMode.TRANSPARENCY,
        )
    }
}

private fun ByteArray.copyOfLast(maxSize: Int): ByteArray =
    if (size <= maxSize) this else copyOfRange(size - maxSize, size)
