package dev.hyperears.integration

import dev.hyperears.protocol.jieli.JlAuthCrypto
import dev.hyperears.protocol.jieli.JlRcspWireCodec

/** One authenticated RCSP conversation, driven by the existing transport and request events. */
internal class DailingT3ProtocolSession : ProtocolSession {
    private enum class AuthStage(val tag: Int, val size: Int) {
        CHALLENGE(0, 17), PROOF(1, 17), ACK(2, 5), COMPLETE(-1, 0), FAILED(-1, 0),
    }

    private val decoder = JlRcspWireCodec.Decoder()
    private val pendingCommands = ArrayDeque<ByteArray>()
    private val authPacket = ByteArray(17)
    private var authSize = 0
    private var authStage = AuthStage.CHALLENGE
    private var handshakePublished = false
    private var batteryConfirmed = false
    private var sequence = 0

    override fun initialReadCommands(): List<ByteArray> =
        listOf(JlAuthCrypto.authStart(kotlin.random.Random.nextBytes(16)))

    override fun followUpCommands(event: ProtocolEvent): List<ByteArray> =
        if (event === ProtocolEvent.HandshakeAccepted) refreshCommands() else emptyList()

    override fun encode(request: ControlRequest): List<ByteArray> {
        if (authStage != AuthStage.COMPLETE || !handshakePublished) return emptyList()
        return when (request) {
            StandardControlRequest.Refresh -> refreshCommands()
            is StandardControlRequest.SetNoiseMode -> request.mode.toWireState()?.let {
                listOf(JlRcspWireCodec.setNoiseMode(it, nextSequence()))
            }.orEmpty()
            is DailingControlRequest.SetGameMode ->
                listOf(JlRcspWireCodec.setGameMode(nextSequence(), request.enabled))
            else -> emptyList()
        }
    }

    override fun query(request: TelemetryQuery): List<ByteArray> {
        if (authStage != AuthStage.COMPLETE || !handshakePublished) return emptyList()
        return when (request) {
            TelemetryQuery.RefreshAll -> refreshCommands()
            is TelemetryQuery.RefreshFeature -> when (request.featureId) {
                BatteryFeatureState.FEATURE_ID -> listOf(JlRcspWireCodec.queryStatus(nextSequence()))
                NoiseModeFeatureState.FEATURE_ID -> listOf(JlRcspWireCodec.currentNoiseModeQuery(nextSequence()))
                DailingGameModeFeatureState.FEATURE_ID -> listOf(JlRcspWireCodec.queryAdvSettings(nextSequence()))
                else -> emptyList()
            }
        }
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is StandardControlRequest.SetNoiseMode ->
            listOf(JlRcspWireCodec.currentNoiseModeQuery(nextSequence()))
        is DailingControlRequest.SetGameMode ->
            listOf(JlRcspWireCodec.queryAdvSettings(nextSequence()))
        else -> emptyList()
    }

    override fun drainImmediateCommands(): List<ByteArray> =
        pendingCommands.toList().also { pendingCommands.clear() }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        if (authStage == AuthStage.FAILED) return@buildList
        var offset = 0
        while (offset < bytes.size && authStage != AuthStage.COMPLETE) {
            authPacket[authSize++] = bytes[offset++]
            if (authPacket[0].toInt() != authStage.tag) {
                rejectAuthentication()
                return@buildList
            }
            if (authSize != authStage.size) continue
            when (authStage) {
                AuthStage.CHALLENGE -> {
                    pendingCommands.addLast(JlAuthCrypto.authResponse(authPacket.copyOfRange(1, 17)))
                    authStage = AuthStage.PROOF
                }
                AuthStage.PROOF -> {
                    // The published JieLi SDK accepts the 17-byte proof before sending "pass".
                    pendingCommands.addLast(JlAuthCrypto.authOk())
                    authStage = AuthStage.ACK
                }
                AuthStage.ACK -> {
                    if (!authPacket.copyOf(5).contentEquals(JlAuthCrypto.authOk())) {
                        rejectAuthentication()
                        return@buildList
                    }
                    authStage = AuthStage.COMPLETE
                    val initialization = JlRcspWireCodec.appInitialization()
                    sequence = initialization.size
                    pendingCommands.addAll(initialization)
                }
                else -> error("Unexpected authentication stage")
            }
            authSize = 0
        }
        if (authStage != AuthStage.COMPLETE || offset == bytes.size) return@buildList
        // An ACK and the first RCSP frame can share one RFCOMM read.
        decoder.offer(bytes.copyOfRange(offset, bytes.size)).forEach { frame ->
            if (JlRcspWireCodec.isDeviceInfo(frame)) publishHandshake()
            JlRcspWireCodec.parseBattery(frame)?.let { report ->
                if (report.usable) {
                    batteryConfirmed = true
                    publishHandshake()
                    add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                }
                if (batteryConfirmed) {
                    add(ProtocolEvent.FeatureStateChanged(BatteryFeatureState(EarbudBattery(
                        left = BatteryReading(report.leftPercent, charging = false),
                        right = BatteryReading(report.rightPercent, charging = false),
                    ))))
                }
            }
            JlRcspWireCodec.parseNoiseState(frame)?.let { mode ->
                publishHandshake()
                add(ProtocolEvent.CapabilitiesIdentified(battery = false, noiseModes = NOISE_MODES))
                add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(when (mode) {
                    JlRcspWireCodec.NoiseState.OFF -> NoiseMode.OFF
                    JlRcspWireCodec.NoiseState.ANC -> NoiseMode.ANC
                    JlRcspWireCodec.NoiseState.TRANSPARENCY -> NoiseMode.TRANSPARENCY
                })))
            }
            JlRcspWireCodec.parseWorkMode(frame)?.let { enabled ->
                publishHandshake()
                add(ProtocolEvent.FeatureStateChanged(DailingGameModeFeatureState(enabled)))
            }
        }
    }

    override fun reset() {
        decoder.reset()
        pendingCommands.clear()
        authPacket.fill(0)
        authSize = 0
        authStage = AuthStage.CHALLENGE
        handshakePublished = false
        batteryConfirmed = false
        sequence = 0
    }

    private fun MutableList<ProtocolEvent>.rejectAuthentication() {
        authStage = AuthStage.FAILED
        authSize = 0
        pendingCommands.clear()
        add(ProtocolEvent.HandshakeRejected)
    }

    private fun MutableList<ProtocolEvent>.publishHandshake() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }

    private fun refreshCommands(): List<ByteArray> = listOf(
        JlRcspWireCodec.queryStatus(nextSequence()),
        JlRcspWireCodec.currentNoiseModeQuery(nextSequence()),
        // The vendor app reads work mode before the first game-mode write.
        JlRcspWireCodec.queryAdvSettings(nextSequence()),
    )

    private fun nextSequence(): Int = sequence.also { sequence = (sequence + 1) and 0xFF }

    private fun NoiseMode.toWireState(): JlRcspWireCodec.NoiseState? = when (this) {
        NoiseMode.OFF -> JlRcspWireCodec.NoiseState.OFF
        NoiseMode.ANC -> JlRcspWireCodec.NoiseState.ANC
        NoiseMode.TRANSPARENCY -> JlRcspWireCodec.NoiseState.TRANSPARENCY
        NoiseMode.WIND -> null
    }

    private companion object {
        val NOISE_MODES = setOf(NoiseMode.OFF, NoiseMode.ANC, NoiseMode.TRANSPARENCY)
    }
}
