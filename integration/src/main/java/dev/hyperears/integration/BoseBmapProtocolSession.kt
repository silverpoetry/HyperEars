package dev.hyperears.integration

import dev.hyperears.protocol.bose.BoseBmapWireCodec

/** One Bose BMAP codec session; transport and lifecycle remain owned by the system module. */
internal class BoseBmapProtocolSession(
    expectedProductId: Int? = null,
    private val fallbackFormFactor: HeadsetFormFactor = HeadsetFormFactor.TWS,
    private val isModelEnabled: (String) -> Boolean = { true },
) : ProtocolSession {
    private val daily = BoseDailySettingsSession()
    private val decoder = BoseBmapWireCodec.Decoder()
    private val modeConfigs = mutableMapOf<Int, BoseBmapWireCodec.ModeConfig>()
    private var identityAccepted: Boolean? = null
    private var activeConfig: BoseWireConfig? = null
    var discoveredConfig: BoseWireConfig? = null
        private set
    private var pendingBattery: ProtocolEvent.FeatureStateChanged? = null
    private var currentModeIndex: Int? = null
    private var currentCncEnabled: Boolean? = null
    private var requiredProductId: Int? = expectedProductId

    /** The owning Adapter is the only object allowed to select writable wire behavior. */
    fun configure(configuration: BoseWireConfig) {
        activeConfig = configuration
        daily.configure(configuration.productId)
        configuration.productId?.let { requiredProductId = it }
    }

    override fun initialReadCommands(): List<ByteArray> = listOf(
        // QC35/35 II require this harmless BMAP initialization read before other requests.
        BoseBmapWireCodec.queryFunctionBlockInfo,
        BoseBmapWireCodec.queryProductIdentity,
        BoseBmapWireCodec.queryBattery,
    )

    override fun encode(request: ControlRequest): List<ByteArray> = when {
        request is BoseDailyControlRequest -> if (identityAccepted == true) daily.encode(request) else emptyList()
        request === StandardControlRequest.Refresh -> initialReadCommands() + activeConfig.noiseReadCommands() +
            daily.queries().takeIf { identityAccepted == true }.orEmpty()
        request is StandardControlRequest.SetNoiseMode -> activeConfig
            ?.noiseControl
            ?.encode(request.mode)
            .orEmpty()

        else -> emptyList()
    }

    override fun followUpCommands(event: ProtocolEvent): List<ByteArray> = when {
        event is ProtocolEvent.ProductIdentified &&
            event.productId == activeConfig?.productId -> {
            activeConfig.noiseReadCommands().ifEmpty(::capabilityProbeCommands) + daily.queries()
        }

        event === ProtocolEvent.HandshakeAccepted &&
            activeConfig == null -> capabilityProbeCommands()

        else -> emptyList()
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when {
        request is BoseDailyControlRequest -> daily.readback(request)
        request === StandardControlRequest.Refresh -> emptyList()
        request is StandardControlRequest.SetNoiseMode -> activeConfig.noiseStateReadCommands()
        else -> emptyList()
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frame ->
            if (BoseBmapWireCodec.isFunctionBlockInfo(frame)) return@forEach

            BoseBmapWireCodec.parseProductIdentity(frame)?.let { identity ->
                identityAccepted = requiredProductId == null ||
                    identity.productId == requiredProductId
                if (identityAccepted == true) {
                    // Decode coalesced status frames with the admitted product profile before
                    // the Adapter consumes ProductIdentified and transfers this same session.
                    BoseBmapModelRegistry.find(identity.productId)
                        ?.takeIf { isModelEnabled(it.modelId) }
                        ?.let { daily.configure(identity.productId) }
                    add(ProtocolEvent.ProductIdentified(identity.productId))
                    add(ProtocolEvent.HandshakeAccepted)
                } else {
                    daily.reset()
                    add(ProtocolEvent.FeatureStateChanged(BoseDailySettingsFeatureState()))
                    add(ProtocolEvent.HandshakeRejected)
                }
                if (identityAccepted == true) pendingBattery?.let { batteryEvent ->
                    add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                    add(batteryEvent)
                }
                pendingBattery = null
                return@forEach
            }

            BoseBmapWireCodec.parseBatteryState(frame)?.let { battery ->
                val event = ProtocolEvent.FeatureStateChanged(
                    BatteryFeatureState(EarbudBattery(
                        left = BatteryReading(battery.leftPercent, charging = false),
                        right = BatteryReading(battery.rightPercent, charging = false),
                        case = BatteryReading(battery.casePercent, charging = false),
                        overall = BatteryReading(battery.overallPercent, charging = false),
                    )),
                )
                when {
                    requiredProductId == null || identityAccepted == true -> {
                        add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                        add(event)
                    }
                    identityAccepted == null -> pendingBattery = event
                }
                return@forEach
            }

            var dailyAccepted = false
            if (identityAccepted == true) daily.offer(frame)?.let {
                add(ProtocolEvent.FeatureStateChanged(it))
                dailyAccepted = true
            }

            if (identityAccepted == true && activeConfig?.noiseControl == null) {
                discoverNoiseConfig(frame)?.let { configuration ->
                    discoveredConfig = configuration
                    add(
                        ProtocolEvent.CapabilitiesIdentified(
                            battery = false,
                            noiseModes = configuration.noiseControl?.supportedModes.orEmpty(),
                        ),
                    )
                }
            }

            val noiseControl = activeConfig?.noiseControl ?: discoveredConfig?.noiseControl
            when (noiseControl) {
                is BoseNoiseControlConfig.AudioModes -> {
                    noiseControl.modeConfigLayout?.let { layout ->
                        BoseBmapWireCodec.parseModeConfig(frame, layout)
                    }?.let { config ->
                        modeConfigs[config.index] = config
                        currentModeIndex
                            ?.takeIf { it == config.index }
                            ?.toNoiseMode(noiseControl)
                            ?.let { add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(it))) }
                        return@forEach
                    }

                    BoseBmapWireCodec.parseCurrentMode(frame)?.let { modeIndex ->
                        currentModeIndex = modeIndex
                        modeIndex.toNoiseMode(noiseControl)?.let { mode ->
                            add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
                        }
                        return@forEach
                    }
                }

                is BoseNoiseControlConfig.Anr -> {
                    BoseBmapWireCodec.parseAnrState(frame)?.let { state ->
                        state.level.toNoiseMode(noiseControl)?.let { mode ->
                            add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
                        }
                        return@forEach
                    }
                }

                is BoseNoiseControlConfig.Cnc -> {
                    BoseBmapWireCodec.parseCncState(frame)?.let { state ->
                        currentCncEnabled = state.enabled
                        add(
                            ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(
                                mode = when {
                                    !state.enabled -> NoiseMode.OFF
                                    state.rawLevel >= state.maximumRawLevel ->
                                        NoiseMode.TRANSPARENCY
                                    else -> NoiseMode.ANC
                                },
                            )),
                        )
                        return@forEach
                    }
                }

                null -> Unit
            }

            if (dailyAccepted) return@forEach
            add(
                ProtocolEvent.UnknownFrame(
                    version = 0,
                    vendor = frame.functionBlock,
                    command = frame.function,
                    payloadSize = frame.payload.size,
                ),
            )
        }
    }

    override fun reset() {
        decoder.reset()
        daily.reset()
        modeConfigs.clear()
        identityAccepted = null
        discoveredConfig = null
        pendingBattery = null
        currentModeIndex = null
        currentCncEnabled = null
    }

    private fun BoseNoiseControlConfig.encode(mode: NoiseMode): List<ByteArray> = when (this) {
        is BoseNoiseControlConfig.AudioModes -> when (mode) {
            NoiseMode.ANC -> listOf(BoseBmapWireCodec.switchMode(quietModeIndex))
            NoiseMode.TRANSPARENCY -> listOf(BoseBmapWireCodec.switchMode(awareModeIndex))
            NoiseMode.WIND -> windModeIndex()
                ?.let { listOf(BoseBmapWireCodec.switchMode(it)) }
                .orEmpty()

            NoiseMode.OFF -> emptyList()
        }

        is BoseNoiseControlConfig.Anr -> when (mode) {
            NoiseMode.ANC -> listOf(BoseBmapWireCodec.setAnr(highValue))
            NoiseMode.OFF -> listOf(BoseBmapWireCodec.setAnr(offValue))
            NoiseMode.WIND -> listOf(BoseBmapWireCodec.setAnr(windValue))
            NoiseMode.TRANSPARENCY -> emptyList()
        }

        is BoseNoiseControlConfig.Cnc -> when (mode) {
            NoiseMode.ANC -> listOf(BoseBmapWireCodec.setCnc(rawLevel = 0, enabled = true))
            NoiseMode.TRANSPARENCY -> {
                val command = BoseBmapWireCodec.setCnc(
                    rawLevel = maximumRawLevel,
                    enabled = true,
                )
                // NC700 powers ANC back on at its maximum level; a second SETGET is required
                // only when enabling directly into the fully-aware endpoint.
                if (currentCncEnabled == false) listOf(command, command) else listOf(command)
            }

            NoiseMode.OFF -> listOf(BoseBmapWireCodec.setCnc(rawLevel = 0, enabled = false))
            NoiseMode.WIND -> emptyList()
        }
    }

    private fun BoseWireConfig?.noiseReadCommands(): List<ByteArray> =
        this?.noiseControl?.let { control ->
            when (control) {
                is BoseNoiseControlConfig.AudioModes -> buildList {
                    if (control.modeConfigLayout != null) {
                        add(BoseBmapWireCodec.queryModeConfigs)
                    }
                    add(BoseBmapWireCodec.queryCurrentMode)
                }

                is BoseNoiseControlConfig.Anr -> listOf(BoseBmapWireCodec.queryAnr)
                is BoseNoiseControlConfig.Cnc -> listOf(BoseBmapWireCodec.queryCnc)
            }
        }.orEmpty()

    private fun BoseWireConfig?.noiseStateReadCommands(): List<ByteArray> =
        this?.noiseControl?.let { control ->
            when (control) {
                is BoseNoiseControlConfig.AudioModes ->
                    listOf(BoseBmapWireCodec.queryCurrentMode)

                is BoseNoiseControlConfig.Anr -> listOf(BoseBmapWireCodec.queryAnr)
                is BoseNoiseControlConfig.Cnc -> listOf(BoseBmapWireCodec.queryCnc)
            }
        }.orEmpty()

    /**
     * GET-only probes for the three public BMAP noise-control generations.
     *
     * ERROR or absent responses are ignored. A write path is enabled only after one exact STATUS
     * frame passes the corresponding codec parser.
     */
    private fun capabilityProbeCommands(): List<ByteArray> = listOf(
        BoseBmapWireCodec.queryCurrentMode,
        BoseBmapWireCodec.queryCnc,
        BoseBmapWireCodec.queryAnr,
    )

    private fun discoverNoiseConfig(
        frame: BoseBmapWireCodec.Frame,
    ): BoseWireConfig? {
        val cncState = BoseBmapWireCodec.parseCncState(frame)
        val (dialect, cncMaximumRawLevel) = when {
            BoseBmapWireCodec.parseCurrentMode(frame) != null ->
                BoseDiscoveredDialect.AUDIO_MODES to null

            cncState != null -> BoseDiscoveredDialect.CNC to cncState.maximumRawLevel

            BoseBmapWireCodec.parseAnrState(frame) != null ->
                BoseDiscoveredDialect.ANR to null

            else -> return null
        }
        return BoseCapabilityConfigRegistry.config(
            formFactor = fallbackFormFactor,
            dialect = dialect,
            cncMaximumRawLevel = cncMaximumRawLevel,
        )
    }

    private fun Int.toNoiseMode(configuration: BoseNoiseControlConfig.AudioModes): NoiseMode? =
        when (this) {
            configuration.quietModeIndex -> NoiseMode.ANC
            configuration.awareModeIndex -> NoiseMode.TRANSPARENCY
            in configuration.additionalAncModeIndices -> NoiseMode.ANC
            else -> modeConfigs[this]?.let { config ->
                when {
                    configuration.windModeFromConfig && config.wind -> NoiseMode.WIND
                    config.rawCnc >= configuration.fullAwareCnc -> NoiseMode.TRANSPARENCY
                    else -> NoiseMode.ANC
                }
            }
        }

    private fun Int.toNoiseMode(configuration: BoseNoiseControlConfig.Anr): NoiseMode? = when (this) {
        configuration.offValue -> NoiseMode.OFF
        configuration.highValue, 3 -> NoiseMode.ANC
        configuration.windValue -> NoiseMode.WIND
        else -> null
    }

    private fun BoseNoiseControlConfig.AudioModes.windModeIndex(): Int? {
        if (!windModeFromConfig) return null
        return modeConfigs.values
            .asSequence()
            .filterNot { it.index == quietModeIndex || it.index == awareModeIndex }
            .firstOrNull { it.wind }
            ?.index
    }
}
