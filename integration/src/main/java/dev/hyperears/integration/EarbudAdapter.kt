package dev.hyperears.integration

data class EarbudIdentity(
    val deviceName: String?,
    val standardHeadset: Boolean,
    val nativeSystemEarbud: Boolean = false,
    val deviceAddress: String? = null,
    val bluetoothDeviceClass: Int? = null,
    val serviceUuids: Set<String> = emptySet(),
)

/**
 * A complete earbud-model adapter.
 *
 * Adapters form a strict inheritance hierarchy. Each vendor or model inherits the behavior and
 * capabilities of its parent, then overrides only verified differences. Transport ownership stays
 * outside this hierarchy so selecting an adapter never creates a Bluetooth connection by itself.
 */
abstract class EarbudAdapter(
    private val transferredProtocolSession: ProtocolSession? = null,
    initialRuntimeState: AdapterRuntimeState = AdapterRuntimeState(),
) {
    abstract val id: String
    abstract val displayName: String

    /** Whether HyperEars may expose this adapter to MiLink. */
    open val integrationEnabled: Boolean = true

    /** Whether the adapter requires an additional vendor channel before it becomes ready. */
    open val privateProtocolRequired: Boolean = false

    /** Noise states that this model can truthfully expose through MiLink's native controls. */
    open val supportedNoiseModes: Set<NoiseMode> = emptySet()

    /** The authoritative source for this adapter's battery telemetry. */
    open val batterySource: BatterySource = BatterySource.NONE

    /** Physical form used by platform presentation bridges. */
    open val formFactor: HeadsetFormFactor = HeadsetFormFactor.TWS

    open val capabilities: EarbudCapabilities = EarbudCapabilities()
    open val miLinkCardPresentationId: MiLinkCardPresentationId? = null

    /** Ordered transport candidates owned by this model adapter. */
    open val transports: List<EarbudTransportSpec> = emptyList()

    /** Evidence required before a transport candidate becomes the session's active channel. */
    open val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE

    /** Vendor applications that must own the private channel while their process is alive. */
    open val controlApps: List<ControlAppSpec> = emptyList()

    /** How this runtime adapter was selected. */
    open val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH

    /** Semantic request contract inherited from the standard control family by default. */
    open val controlRequestContract: ControlRequestContract = StandardControlRequestContract

    /** Typed state contract inherited from the standard feature family by default. */
    open val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract

    abstract fun matches(identity: EarbudIdentity): Boolean

    /**
     * Decides how a provisional protocol-family candidate degrades when its bounded initial
     * transport and handshake attempts finish without ever confirming the private protocol.
     *
     * Concrete and already-confirmed adapters retain their identity by default. A family probe
     * may replace itself with a conservative, non-private adapter while preserving runtime state.
     */
    open fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.KeepDormant

    /**
     * Applies the user policy to an Adapter-declared initial-protocol fallback.
     *
     * A disabled vendor fallback must never be activated indirectly. In that case the only
     * permitted substitute is Android's standard-headset integration; if the user disabled that
     * Adapter as well, the current Adapter remains dormant and no replacement is installed.
     */
    fun resolveInitialProtocolFailure(): InitialProtocolFailureResolution {
        val declared = onInitialProtocolUnavailable()
        if (declared !is InitialProtocolFailureResolution.FallbackTo) return declared

        val fallback = when {
            isAdapterEnabled(declared.adapter.id) -> declared.adapter
            isAdapterEnabled(StandardEarbudAdapter.ID) -> StandardEarbudAdapter(
                initialRuntimeState = runtimeState(),
            )
            else -> return InitialProtocolFailureResolution.KeepDormant
        }
        fallback.configureDisabledAdapterIds(disabledReplacementAdapterIds)
        return InitialProtocolFailureResolution.FallbackTo(fallback)
    }

    /**
     * Mutable wire-conversation state owned by this adapter instance.
     *
     * Registry entries are factories; a runtime adapter is never shared by two physical devices.
     */
    val protocolSession: ProtocolSession by lazy(LazyThreadSafetyMode.NONE) {
        transferredProtocolSession ?: createProtocolSession()
    }

    private var runtimeState: AdapterRuntimeState = initialRuntimeState
    private var confirmedCapabilities: EarbudCapabilities? = null
    private var confirmedNoiseModes: Set<NoiseMode>? = null
    private var confirmedBatterySource: BatterySource? = null
    @Volatile
    private var disabledReplacementAdapterIds: Set<String> = emptySet()

    /** Applies the user policy to any concrete Adapter selected during protocol negotiation. */
    fun configureDisabledAdapterIds(adapterIds: Set<String>) {
        disabledReplacementAdapterIds = adapterIds.toSet()
    }

    /** Allows a family Adapter to reject a disabled protocol-identified replacement. */
    protected fun isAdapterEnabled(adapterId: String): Boolean =
        adapterId !in disabledReplacementAdapterIds

    /** Creates a protocol-driven replacement only when its stable Adapter ID is enabled. */
    protected fun selectReplacement(
        adapter: EarbudAdapter,
        activation: AdapterActivation,
    ): HandshakeResult? = if (isAdapterEnabled(adapter.id)) {
        HandshakeResult.Replace(adapter, activation)
    } else {
        null
    }

    protected open fun createProtocolSession(): ProtocolSession = StandardBluetoothProtocolSession()

    /** Begins the one adapter-owned protocol confirmation phase. */
    fun beginHandshake(): AdapterIoResult {
        if (!privateProtocolRequired) {
            return AdapterIoResult(handshake = HandshakeResult.Ready)
        }
        val commands = protocolSession.initialReadCommands()
        val handshake = if (transportReadiness == TransportReadiness.PROTOCOL_HANDSHAKE) {
            HandshakeResult.AwaitingEvidence
        } else {
            HandshakeResult.Ready
        }
        return AdapterIoResult(commands = commands, handshake = handshake)
    }

    /**
     * Consumes one transport read. Protocol events remain private to the adapter aggregate.
     */
    fun receive(bytes: ByteArray): AdapterIoResult {
        val previousSnapshot = snapshot()
        val events = protocolSession.offer(bytes)
        var changed = false
        var handshake: HandshakeResult? = null
        val unknown = mutableListOf<ProtocolEvent.UnknownFrame>()
        val eventScope = RecordingAdapterEventScope()

        // Apply telemetry first so a replacement Adapter receives the complete runtime snapshot
        // decoded from this transport read, independent of event ordering inside a codec.
        events.forEach { event ->
            when (event) {
                is ProtocolEvent.FeatureStateChanged -> {
                    if (!featureStateContract.accepts(this, event.state)) return@forEach
                    when (onFeatureReported(event.state, eventScope)) {
                        FeatureReportDecision.ACCEPT -> {
                            if (event.state is BatteryFeatureState) {
                                batterySourceAfterProtocolEvidence()?.let {
                                    confirmedBatterySource = it
                                }
                            }
                            val nextFeatures = runtimeState.features.update(event.state)
                            if (nextFeatures != runtimeState.features) {
                                runtimeState = runtimeState.copy(features = nextFeatures)
                                changed = true
                            }
                        }

                        FeatureReportDecision.HOLD -> Unit
                    }
                }

                is ProtocolEvent.UnknownFrame -> unknown += event
                else -> Unit
            }
        }
        events.forEach { event ->
            when (event) {
                ProtocolEvent.HandshakeAccepted -> {
                    if (handshake !is HandshakeResult.Replace) {
                        handshake = HandshakeResult.Ready
                    }
                }

                ProtocolEvent.HandshakeRejected -> {
                    if (handshake !is HandshakeResult.Replace) {
                        handshake = HandshakeResult.Rejected
                    }
                }

                is ProtocolEvent.ProductIdentified -> {
                    onProductIdentified(event.productId)?.let { handshake = it }
                }

                is ProtocolEvent.CapabilitiesIdentified -> {
                    onCapabilitiesIdentified(event.battery, event.noiseModes)
                        ?.let { handshake = it }
                }

                else -> Unit
            }
        }
        val commands = buildList {
            addAll(protocolSession.drainImmediateCommands())
            events.forEach { event -> addAll(protocolSession.followUpCommands(event)) }
        }
        (handshake as? HandshakeResult.Replace)
            ?.adapter
            ?.let { replacement ->
                replacement.disabledReplacementAdapterIds = disabledReplacementAdapterIds
                replacement.adoptProtocolState(this)
            }
        changed = changed || snapshot() != previousSnapshot
        return AdapterIoResult(
            commands = commands,
            handshake = handshake,
            stateChanged = changed,
            unknownFrames = unknown,
            effects = eventScope.effects(),
        )
    }

    /**
     * Handles one decoded report after structural validation but before public-state admission.
     *
     * A concrete model may hold a verified transient and record a one-shot state request through
     * [scope]. The default preserves the direct report-to-state behavior of every existing Adapter.
     */
    protected open fun onFeatureReported(
        state: DeviceFeatureState,
        scope: AdapterEventScope,
    ): FeatureReportDecision = FeatureReportDecision.ACCEPT

    /** Maps authoritative vendor identity evidence to a new concrete adapter when needed. */
    protected open fun onProductIdentified(productId: Int): HandshakeResult? = null

    protected open fun onCapabilitiesIdentified(
        battery: Boolean,
        noiseModes: Set<NoiseMode>,
    ): HandshakeResult? {
        val nextModes = effectiveSupportedNoiseModes() + noiseModes
        val base = effectiveCapabilities()
        confirmedNoiseModes = nextModes
        confirmedCapabilities = base.copy(
            battery = base.battery || battery,
            noiseControl = nextModes.isNotEmpty(),
            windNoiseControl = NoiseMode.WIND in nextModes,
        )
        return null
    }

    /** Promotes the standard fallback only after this private session proves battery telemetry. */
    protected open fun batterySourceAfterProtocolEvidence(): BatterySource? =
        BatterySource.PRIVATE_PROTOCOL.takeIf {
            privateProtocolRequired && batterySource == BatterySource.SYSTEM_AGGREGATE
        }

    fun effectiveCapabilities(): EarbudCapabilities = confirmedCapabilities ?: capabilities

    fun effectiveSupportedNoiseModes(): Set<NoiseMode> =
        confirmedNoiseModes ?: supportedNoiseModes

    fun effectiveBatterySource(): BatterySource = confirmedBatterySource ?: batterySource

    /** Validates a control against the effective adapter capability and request contract. */
    fun supportsControl(request: ControlRequest): Boolean =
        controlRequestContract.supports(this, request)

    /**
     * Returns the execution policy for one typed request. Concrete adapters override this for
     * verified device-specific confirmation or pacing requirements.
     */
    open fun controlPolicy(request: ControlRequest): ControlExecutionPolicy = when (request) {
        is StandardControlRequest.SetNoiseMode -> ControlExecutionPolicy(
            stateAfterWrite = NoiseModeFeatureState(request.mode),
        )

        else -> ControlExecutionPolicy()
    }

    fun executeControl(request: ControlRequest): AdapterControlResult {
        if (!supportsControl(request)) {
            return AdapterControlResult(accepted = false)
        }
        if (!privateProtocolRequired) {
            return AdapterControlResult(accepted = request === StandardControlRequest.Refresh)
        }
        val commands = protocolSession.encode(request)
        if (commands.isEmpty() && request !== StandardControlRequest.Refresh) {
            return AdapterControlResult(accepted = false)
        }
        val policy = controlPolicy(request)
        var changed = false
        val stateAfterWrite = policy.stateAfterWrite
        if (policy.confirmation != ControlConfirmationPolicy.DEVICE_REPORT &&
            stateAfterWrite != null
        ) {
            val nextFeatures = runtimeState.features.update(stateAfterWrite)
            if (nextFeatures != runtimeState.features) {
                runtimeState = runtimeState.copy(features = nextFeatures)
                changed = true
            }
        }
        val readback = when (policy.confirmation) {
            ControlConfirmationPolicy.PUBLISH_AFTER_WRITE -> emptyList()
            ControlConfirmationPolicy.DEVICE_REPORT,
            ControlConfirmationPolicy.PUBLISH_AFTER_WRITE_THEN_REFRESH,
            -> protocolSession.readback(request)
        }
        return AdapterControlResult(
            accepted = true,
            commands = commands,
            readback = readback,
            stateChanged = changed,
        )
    }

    /**
     * Records model-owned follow-up effects after the complete control write succeeds.
     *
     * The Android runtime invokes this exactly once after [executeControl] commands are written. A
     * failed write never arms model confirmation state.
     */
    fun controlWritten(request: ControlRequest): List<AdapterEffect> {
        val scope = RecordingAdapterEventScope()
        onControlWritten(request, scope)
        return scope.effects()
    }

    protected open fun onControlWritten(
        request: ControlRequest,
        scope: AdapterEventScope,
    ) = Unit

    /** Encodes one Adapter-requested state read on the current protocol session. */
    fun queryState(featureId: String): List<ByteArray> =
        protocolSession.query(TelemetryQuery.RefreshFeature(featureId))

    fun onSystemBatteryChanged(percent: Int?): Boolean {
        if (effectiveBatterySource() != BatterySource.SYSTEM_AGGREGATE) return false
        val battery = EarbudBattery.fromSystemAggregate(percent)
        val nextFeatures = runtimeState.features.update(BatteryFeatureState(battery))
        if (nextFeatures == runtimeState.features) return false
        runtimeState = runtimeState.copy(features = nextFeatures)
        return true
    }

    /**
     * Transfers session-scoped telemetry during an Adapter replacement.
     *
     * Product and capability discovery can replace a family Adapter without reconnecting its
     * channel. The replacement receives only feature types it declares, so a former family or
     * model cannot leak a vendor-specific state into a conservative fallback.
     */
    fun adoptRuntimeState(source: AdapterRuntimeState): Boolean {
        val retained = source.features.retain { state ->
            featureStateContract.accepts(this, state)
        }
        val next = source.copy(features = retained)
        if (next == runtimeState) return false
        runtimeState = next
        return true
    }

    /** Transfers protocol evidence during an in-place Adapter refinement. */
    private fun adoptProtocolState(source: EarbudAdapter): Boolean {
        var changed = adoptRuntimeState(source.runtimeState())
        val promotedBatterySource = batterySourceAfterProtocolEvidence()
        if (source.effectiveBatterySource() == BatterySource.PRIVATE_PROTOCOL &&
            promotedBatterySource != null &&
            effectiveBatterySource() != promotedBatterySource
        ) {
            confirmedBatterySource = promotedBatterySource
            changed = true
        }
        return changed
    }

    fun runtimeState(): AdapterRuntimeState = runtimeState

    fun snapshot(): AdapterSnapshot = AdapterSnapshot(
        id = id,
        displayName = displayName,
        resolution = resolution,
        privateProtocolRequired = privateProtocolRequired,
        batterySource = effectiveBatterySource(),
        formFactor = formFactor,
        capabilities = effectiveCapabilities(),
        supportedNoiseModes = effectiveSupportedNoiseModes(),
        presentationId = miLinkCardPresentationId,
        transportKinds = transports.mapTo(linkedSetOf()) { transport ->
            when (transport) {
                is RfcommEndpointSpec -> TransportKind.RFCOMM
                is GattTransportSpec -> TransportKind.GATT
                is L2capEndpointSpec -> TransportKind.L2CAP
            }
        },
        controlApps = controlApps,
    )

    fun resetProtocolSession() {
        protocolSession.reset()
        onProtocolReset()
    }

    /** Clears model-owned transient protocol state whenever the physical conversation is reset. */
    protected open fun onProtocolReset() = Unit

    protected fun normalizeDeviceName(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)
}

private class RecordingAdapterEventScope : AdapterEventScope {
    private val recordedEffects = mutableListOf<AdapterEffect>()

    override fun requestState(featureId: String, delayMs: Long) {
        recordedEffects += AdapterEffect.RequestState(featureId, delayMs)
    }

    override fun cancelStateRequest(featureId: String) {
        recordedEffects += AdapterEffect.CancelStateRequest(featureId)
    }

    fun effects(): List<AdapterEffect> = recordedEffects.toList()
}

private class StandardBluetoothProtocolSession : ProtocolSession {
    override fun initialReadCommands(): List<ByteArray> = emptyList()

    override fun encode(request: ControlRequest): List<ByteArray> = emptyList()

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = emptyList()

    override fun reset() = Unit
}

enum class TransportReadiness {
    /** A successful link-layer connection is sufficient. */
    CONNECTED,

    /** The candidate must also return an accepted protocol handshake. */
    PROTOCOL_HANDSHAKE,
}

sealed interface InitialProtocolFailureResolution {
    data object KeepDormant : InitialProtocolFailureResolution

    data class FallbackTo(
        val adapter: EarbudAdapter,
    ) : InitialProtocolFailureResolution
}

/**
 * Android's standard Bluetooth-headset behavior.
 *
 * This is the terminal fallback. A2DP/HFP, routing and volume remain owned by Android and the ROM;
 * HyperEars contributes only its form factor and Android's already-cached aggregate battery.
 */
open class StandardEarbudAdapter(
    transferredProtocolSession: ProtocolSession? = null,
    initialRuntimeState: AdapterRuntimeState = AdapterRuntimeState(),
) : EarbudAdapter(transferredProtocolSession, initialRuntimeState) {
    override val id: String = ID
    override val displayName: String = "Standard Bluetooth headset"
    override val batterySource: BatterySource = BatterySource.SYSTEM_AGGREGATE
    override val capabilities: EarbudCapabilities = EarbudCapabilities(
        battery = true,
        audioHandoff = true,
    )
    override val resolution: AdapterResolution = AdapterResolution.STANDARD
    override fun matches(identity: EarbudIdentity): Boolean =
        identity.standardHeadset && !identity.nativeSystemEarbud

    companion object {
        const val ID = "standard-bluetooth-headset"
    }
}

/**
 * Resolves the most specific eligible adapter first.
 */
enum class EarbudAdapterKind {
    MODEL,
    FAMILY,
    STANDARD,
}

data class EarbudAdapterDescriptor(
    val id: String,
    val displayName: String,
    val kind: EarbudAdapterKind,
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
    }
}

data class EarbudAdapterGroup(
    val id: String,
    val displayName: String,
    val adapters: List<EarbudAdapterDescriptor>,
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(adapters.isNotEmpty())
    }
}

object EarbudAdapterRegistry {
    private data class GroupMetadata(
        val id: String,
        val displayName: String,
    )

    private class Registration(
        val group: GroupMetadata,
        val factory: () -> EarbudAdapter,
    ) {
        val descriptor: EarbudAdapterDescriptor
        val privateCapabilitiesInitiallyLocked: Boolean

        init {
            val adapter = factory()
            descriptor = EarbudAdapterDescriptor(
                id = adapter.id,
                displayName = adapter.displayName,
                kind = when (adapter.resolution) {
                    AdapterResolution.EXACT_MATCH,
                    AdapterResolution.PROTOCOL_CONFIRMED,
                    -> EarbudAdapterKind.MODEL

                    AdapterResolution.FAMILY_MATCH -> EarbudAdapterKind.FAMILY
                    AdapterResolution.STANDARD -> EarbudAdapterKind.STANDARD
                },
            )
            val snapshot = adapter.snapshot()
            val capabilities = snapshot.capabilities
            privateCapabilitiesInitiallyLocked = !adapter.privateProtocolRequired ||
                (
                    snapshot.batterySource == BatterySource.SYSTEM_AGGREGATE &&
                        capabilities.battery &&
                        capabilities.audioHandoff &&
                        !capabilities.noiseControl &&
                        !capabilities.windNoiseControl &&
                        !capabilities.spatialAudio &&
                        !capabilities.wearDetection &&
                        !capabilities.findDevice &&
                        snapshot.supportedNoiseModes.isEmpty() &&
                        snapshot.presentationId == null &&
                        adapter.transportReadiness == TransportReadiness.PROTOCOL_HANDSHAKE
                )
        }
    }

    private val vivoGroup = GroupMetadata("vivo", "vivo / iQOO")
    private val oppoGroup = GroupMetadata("oppo", "OPPO Enco")
    private val starRingGroup =
        GroupMetadata("starring", "StarRing / 籁特易耳")
    private val boseGroup = GroupMetadata("bose", "Bose")
    private val edifierGroup =
        GroupMetadata("edifier", "Edifier / 漫步者")
    private val roseGroup =
        GroupMetadata("rose", "ROSESELSA / 弱水时砂")
    private val niceHckGroup =
        GroupMetadata("nicehck", "NiceHCK / 原道")
    private val moondropGroup =
        GroupMetadata("moondrop", "MOONDROP / 水月雨")
    private val honorGroup = GroupMetadata("honor", "荣耀")
    private val sonyGroup = GroupMetadata("sony", "Sony")
    private val qcyGroup = GroupMetadata("qcy", "QCY")
    private val technicsGroup = GroupMetadata("technics", "Technics")
    private val standardGroup = GroupMetadata("standard", "标准蓝牙耳机")

    private val initialRegistrations: List<Registration> = buildList {
        add(Registration(vivoGroup, ::VivoTwsAir3ProAdapter))
        add(Registration(vivoGroup, ::VivoTws3eAdapter))
        add(Registration(vivoGroup, ::VivoEarbudAdapter))
        add(Registration(starRingGroup, ::StarRingUltraAdapter))
        add(Registration(starRingGroup, ::StarRingEarbudAdapter))
        add(Registration(oppoGroup, ::OppoEncoAir2ProAdapter))
        add(Registration(oppoGroup, ::OppoEncoFree4Adapter))
        add(Registration(oppoGroup, ::OppoEncoX3Adapter))
        add(Registration(oppoGroup, ::OppoEncoAir5Adapter))
        add(Registration(oppoGroup, ::OppoEarbudAdapter))
        add(Registration(boseGroup, ::BoseHeadphonesAdapter))
        add(Registration(boseGroup, ::BoseEarbudAdapter))
        add(Registration(edifierGroup, ::EdifierW860NBProAdapter))
        add(Registration(edifierGroup, ::EdifierEvoProAdapter))
        add(Registration(edifierGroup, ::EdifierHeadphonesAdapter))
        add(Registration(edifierGroup, ::EdifierEarbudAdapter))
        add(Registration(roseGroup, ::FurinaEndlessAdapter))
        add(Registration(roseGroup, ::RoseLuliUltraAdapter))
        add(Registration(roseGroup, ::RoseEarfreeI5Adapter))
        add(Registration(roseGroup, ::RoseEarfreeProtocolFamilyAdapter))
        add(Registration(roseGroup, ::RoseBudsFeelMk2Adapter))
        add(Registration(roseGroup, ::RoseBudsFeelProtocolFamilyAdapter))
        add(Registration(roseGroup, ::RoseEarbudAdapter))
        add(Registration(niceHckGroup, ::NiceHckYuanDaoOrigAdapter))
        add(Registration(niceHckGroup, ::NiceHckEarbudAdapter))
        add(Registration(moondropGroup, ::MoondropRobinAdapter))
        add(Registration(moondropGroup, ::MoondropEarbudAdapter))
        add(Registration(honorGroup, ::HonorX5sProAdapter))
        add(Registration(qcyGroup, ::QcyCrosskyC50sAdapter))
        add(Registration(qcyGroup, ::QcyStandardGattAdapter))
        add(Registration(technicsGroup, ::TechnicsEarbudAdapter))
        // Apple devices are handled by the platform; keep AAP code available for explicit use,
        // but do not add Apple adapters to HyperEars' default matching chain.
        addAll(SonyAdapterRegistry.factories.map { Registration(sonyGroup, it) })
        add(Registration(standardGroup, ::StandardEarbudAdapter))
    }

    /** Protocol-identified models are catalogued lazily and never enter initial name matching. */
    private val catalogOnlyRegistrations: List<Registration> by lazy {
        BoseBmapModelRegistry.factories.map { factory ->
            Registration(
                group = boseGroup,
                factory = factory,
            )
        }
    }

    private val catalogRegistrations: List<Registration> by lazy {
        (initialRegistrations + catalogOnlyRegistrations).also(::requireUniqueIds)
    }

    val groups: List<EarbudAdapterGroup> by lazy {
        catalogRegistrations
            .groupBy(Registration::group)
            .map { (group, members) ->
                EarbudAdapterGroup(
                    id = group.id,
                    displayName = group.displayName,
                    adapters = members.map(Registration::descriptor),
                )
            }
    }

    /** Builds the settings-only catalog without touching the connection matching hot path. */
    fun preloadCatalog() {
        groups.size
    }

    val adapters: List<EarbudAdapter> get() = catalogRegistrations.map { it.factory() }

    val adapterIds: Set<String> by lazy {
        catalogRegistrations.mapTo(linkedSetOf()) { it.descriptor.id }
    }

    init {
        requireUniqueIds(initialRegistrations)
        requireInitialPrivateCapabilitiesLocked(initialRegistrations)
    }

    fun resolve(
        identity: EarbudIdentity,
        disabledAdapterIds: Set<String> = emptySet(),
    ): EarbudAdapter? {
        if (PlatformReservedHeadsetPolicy.reserves(identity)) return null
        return initialRegistrations.asSequence()
            .filterNot { it.descriptor.id in disabledAdapterIds }
            .map { it.factory() }
            .firstOrNull { it.matches(identity) }
    }

    fun forIntegration(
        identity: EarbudIdentity,
        disabledAdapterIds: Set<String> = emptySet(),
    ): EarbudAdapter? = resolve(identity, disabledAdapterIds)
        ?.takeIf(EarbudAdapter::integrationEnabled)

    private fun requireUniqueIds(registrations: List<Registration>) {
        val duplicates = registrations
            .map { it.descriptor.id }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicates.isEmpty()) {
            "Earbud adapter IDs must be unique: $duplicates"
        }
    }

    /**
     * Identity matching may select a protocol candidate, but it must never authorize a private
     * feature. Every directly registered private Adapter starts from Android's standard headset
     * projection and unlocks telemetry, controls, and custom cards only from protocol evidence.
     * Protocol-confirmed replacement Adapters (for example Bose product-ID models) are catalogued
     * separately and therefore are intentionally outside this invariant.
     */
    private fun requireInitialPrivateCapabilitiesLocked(registrations: List<Registration>) {
        val violations = registrations
            .filterNot(Registration::privateCapabilitiesInitiallyLocked)
            .map { it.descriptor.id }
        require(violations.isEmpty()) {
            "Initial private adapters must keep vendor capabilities locked until protocol evidence: " +
                violations.joinToString()
        }
    }

}
