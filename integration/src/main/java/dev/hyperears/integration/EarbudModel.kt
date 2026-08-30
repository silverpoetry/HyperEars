package dev.hyperears.integration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class NoiseMode {
    @SerialName("anc")
    ANC,

    @SerialName("off")
    OFF,

    @SerialName("transparency")
    TRANSPARENCY,

    @SerialName("wind")
    WIND,
}

@Serializable
data class BatteryReading(
    val percent: Int?,
    val charging: Boolean,
) {
    init {
        require(percent == null || percent in 0..100)
    }

    val available: Boolean get() = percent != null
}

@Serializable
data class EarbudBattery(
    val left: BatteryReading = BatteryReading(null, false),
    val right: BatteryReading = BatteryReading(null, false),
    val case: BatteryReading = BatteryReading(null, false),
    val overall: BatteryReading = BatteryReading(null, false),
) {
    companion object {
        /** Projects one authoritative aggregate value without inventing component telemetry. */
        fun fromAggregate(percent: Int?): EarbudBattery {
            val reading = BatteryReading(percent?.takeIf { it in 0..100 }, charging = false)
            return EarbudBattery(
                left = reading,
                right = reading,
                overall = reading,
            )
        }

        /**
         * Projects Android's single headset battery value onto MiLink's left/right schema.
         *
         * Standard Bluetooth exposes no trustworthy case level or per-bud split, so both buds
         * deliberately receive the same aggregate value and the case remains unavailable.
         */
        fun fromSystemAggregate(percent: Int?): EarbudBattery {
            return fromAggregate(percent)
        }
    }
}

/**
 * A typed, device-facing state value emitted by a protocol and retained by its Adapter.
 *
 * Standard telemetry and vendor/model-specific state intentionally share this boundary. The
 * platform bridge may project selected standard values onto MiLink callbacks, while CardAdapters
 * can consume any state type declared by their concrete Adapter. The stable feature id is kept out
 * of the wire payload; the serializer discriminator is the transport identity.
 */
@Serializable
sealed interface DeviceFeatureState {
    @Transient
    val featureId: String
}

@Serializable
@SerialName("standard.battery")
data class BatteryFeatureState(
    val battery: EarbudBattery,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "standard.battery"
    }
}

@Serializable
@SerialName("standard.noise_mode")
data class NoiseModeFeatureState(
    val mode: NoiseMode,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "standard.noise_mode"
    }
}

/** Immutable state collection with replacement semantics per feature identity. */
@Serializable
data class DeviceFeatureSnapshot(
    val values: List<DeviceFeatureState> = emptyList(),
) {
    fun get(featureId: String): DeviceFeatureState? =
        values.firstOrNull { it.featureId == featureId }

    inline fun <reified T : DeviceFeatureState> get(): T? =
        values.filterIsInstance<T>().firstOrNull()

    fun update(next: DeviceFeatureState): DeviceFeatureSnapshot =
        copy(values = values.filterNot { it.featureId == next.featureId } + next)

    fun remove(featureId: String): DeviceFeatureSnapshot =
        copy(values = values.filterNot { it.featureId == featureId })

    /** Retains only values that are understood by the receiving Adapter. */
    fun retain(accept: (DeviceFeatureState) -> Boolean): DeviceFeatureSnapshot =
        copy(values = values.filter(accept))
}

/**
 * Declares which typed state values one Adapter understands and may retain.
 *
 * Capability evidence separately decides whether a retained standard value is exposed through
 * MiLink. This lets a family Adapter safely keep a valid report received in the same frame that
 * confirms its capability, while still rejecting state types owned by another model family.
 */
fun interface DeviceFeatureStateContract {
    fun accepts(adapter: EarbudAdapter, state: DeviceFeatureState): Boolean
}

/** Baseline feature contract inherited by every Adapter. */
object StandardDeviceFeatureStateContract : DeviceFeatureStateContract {
    override fun accepts(adapter: EarbudAdapter, state: DeviceFeatureState): Boolean =
        state.featureId == BatteryFeatureState.FEATURE_ID ||
            state.featureId == NoiseModeFeatureState.FEATURE_ID
}

/** Adds a family/model feature predicate without weakening standard state handling. */
fun DeviceFeatureStateContract.extending(
    additionalSupport: (EarbudAdapter, DeviceFeatureState) -> Boolean,
): DeviceFeatureStateContract = DeviceFeatureStateContract { adapter, state ->
    accepts(adapter, state) || additionalSupport(adapter, state)
}

enum class BatterySource {
    NONE,
    SYSTEM_AGGREGATE,
    PRIVATE_PROTOCOL,
}

/**
 * Defines where control-state truth comes from after a successful vendor write.
 *
 * The policy belongs to the model adapter; byte-level readback commands belong to the protocol.
 */
enum class ControlConfirmationPolicy {
    /** Publish only an authoritative device report. */
    DEVICE_REPORT,

    /** Publish the requested state after the complete write transaction succeeds. */
    PUBLISH_AFTER_WRITE,

    /** Publish after the write, then request an authoritative state refresh. */
    PUBLISH_AFTER_WRITE_THEN_REFRESH,
}

enum class SystemProfileState {
    DISCONNECTED,
    CONNECTED,
}

enum class PrivateTransportState {
    NOT_REQUIRED,
    IDLE,
    CONNECTING,
    CONNECTED,
    RECOVERING,
    DORMANT,
}

enum class ProtocolHandshakeState {
    NOT_REQUIRED,
    PENDING,
    CONFIRMED,
    REJECTED,
}

/** Selects which process currently owns the vendor-private headset control channel. */
enum class ControlOwnership {
    MODULE,
    EXTERNAL_APP,
}

/** One authoritative lifecycle projection for a physical headset session. */
data class DeviceLifecycle(
    val systemProfile: SystemProfileState = SystemProfileState.DISCONNECTED,
    val privateTransport: PrivateTransportState = PrivateTransportState.NOT_REQUIRED,
    val protocolHandshake: ProtocolHandshakeState = ProtocolHandshakeState.NOT_REQUIRED,
    val controlOwnership: ControlOwnership = ControlOwnership.MODULE,
    val externalControlApp: ControlAppSpec? = null,
) {
    init {
        require(
            (controlOwnership == ControlOwnership.EXTERNAL_APP) ==
                (externalControlApp != null),
        ) { "External control ownership requires exactly one control app identity" }
    }

    val active: Boolean get() = systemProfile == SystemProfileState.CONNECTED
    val privateTransportRequired: Boolean
        get() = privateTransport != PrivateTransportState.NOT_REQUIRED
    val privateTransportConnected: Boolean
        get() = privateTransport == PrivateTransportState.CONNECTED
    val protocolConfirmed: Boolean
        get() = protocolHandshake == ProtocolHandshakeState.CONFIRMED
    val protocolReady: Boolean
        get() = protocolHandshake in setOf(
            ProtocolHandshakeState.NOT_REQUIRED,
            ProtocolHandshakeState.CONFIRMED,
        )
    val operational: Boolean
        get() = active && (
            !privateTransportRequired ||
                privateTransportConnected && protocolReady
            )
}

data class EarbudState(
    val adapter: AdapterSnapshot? = null,
    val deviceName: String? = null,
    val address: String? = null,
    val lifecycle: DeviceLifecycle = DeviceLifecycle(),
    val features: DeviceFeatureSnapshot = DeviceFeatureSnapshot(),
    val revision: Long = 0,
) {
    /** Read-only standard feature projections used by platform bridges and generic UI. */
    val battery: EarbudBattery
        get() = features.get<BatteryFeatureState>()?.battery ?: EarbudBattery()

    val noiseMode: NoiseMode?
        get() = features.get<NoiseModeFeatureState>()?.mode

    /** Compatibility views. Lifecycle truth is stored only in [lifecycle]. */
    val modelId: String? get() = adapter?.id
    val sessionActive: Boolean get() = lifecycle.active
    val privateProtocolRequired: Boolean get() = lifecycle.privateTransportRequired
    val connected: Boolean get() = lifecycle.operational
    val privateChannelConnected: Boolean get() = lifecycle.privateTransportConnected
    val handshakeAccepted: Boolean get() = lifecycle.protocolConfirmed
}

/** Returns a copy with one feature replaced by its stable feature identity. */
fun EarbudState.withFeature(state: DeviceFeatureState): EarbudState =
    copy(features = features.update(state))

/** Replaces the standard noise-mode feature without reintroducing a stored legacy field. */
fun EarbudState.withNoiseMode(mode: NoiseMode?): EarbudState =
    copy(
        features = if (mode == null) {
            features.remove(NoiseModeFeatureState.FEATURE_ID)
        } else {
            features.update(NoiseModeFeatureState(mode))
        },
    )

/** Evidence decoded from a vendor byte stream. It never represents system lifecycle state. */
sealed interface ProtocolEvent {
    data object HandshakeAccepted : ProtocolEvent
    data object HandshakeRejected : ProtocolEvent

    /** Authoritative vendor product identifier; mapping to an Adapter remains adapter-owned. */
    data class ProductIdentified(val productId: Int) : ProtocolEvent

    /**
     * Private-protocol abilities established by successful read-only responses.
     * `battery` is true only when this read also proves private battery telemetry.
     */
    data class CapabilitiesIdentified(
        val battery: Boolean,
        val noiseModes: Set<NoiseMode> = emptySet(),
    ) : ProtocolEvent

    data class FeatureStateChanged(val state: DeviceFeatureState) : ProtocolEvent

    data class UnknownFrame(
        val version: Int,
        val vendor: Int,
        val command: Int,
        val payloadSize: Int,
    ) : ProtocolEvent
}

/**
 * One internal telemetry read requested by an Adapter.
 *
 * Telemetry reads are deliberately separate from user-facing [ControlRequest] values. An Adapter
 * decides what must be observed, its [ProtocolSession] translates the query into vendor bytes, and
 * the Android runtime only owns scheduling and transport I/O.
 */
sealed interface TelemetryQuery {
    data object RefreshAll : TelemetryQuery

    data class RefreshFeature(
        val featureId: String,
    ) : TelemetryQuery {
        init {
            require(featureId.isNotBlank()) { "Telemetry feature id cannot be blank" }
        }
    }
}

/** Whether one structurally valid protocol report may enter the public Adapter state. */
enum class FeatureReportDecision {
    ACCEPT,
    HOLD,
}

/** Ordered, declarative effects recorded while an Adapter handles one framework event. */
sealed interface AdapterEffect {
    data class RequestState(
        val featureId: String,
        val delayMs: Long,
    ) : AdapterEffect {
        init {
            require(featureId.isNotBlank()) { "Requested feature ID cannot be blank" }
            require(delayMs >= 0L) { "State-request delay cannot be negative" }
        }
    }

    data class CancelStateRequest(
        val featureId: String,
    ) : AdapterEffect {
        init {
            require(featureId.isNotBlank()) { "Cancelled feature ID cannot be blank" }
        }
    }
}

/**
 * Event-local control surface exposed to an Adapter.
 *
 * Calls only record ordered [AdapterEffect] values. They never start a timer, touch a transport or
 * call back into the Android runtime while Adapter code is executing.
 */
interface AdapterEventScope {
    fun requestState(featureId: String, delayMs: Long)

    fun cancelStateRequest(featureId: String)
}

/** A model-declared private-protocol transport candidate. */
sealed interface EarbudTransportSpec {
    val id: String
}

sealed interface RfcommEndpointSpec : EarbudTransportSpec {

    data class ServiceUuid(
        val uuid: String,
        override val id: String,
    ) : RfcommEndpointSpec

    data class Channel(
        val number: Int,
        val secure: Boolean = true,
        override val id: String = "rfcomm-$number${if (secure) "" else "-insecure"}",
    ) : RfcommEndpointSpec
}

/**
 * Bluetooth Classic L2CAP endpoint identified by a fixed protocol/service multiplexer.
 *
 * Apple Accessory Protocol uses the BR/EDR socket type with PSM `0x1001`; keeping that detail in
 * the transport declaration prevents Apple-specific reflection from leaking into the protocol or
 * device adapter layers.
 */
data class L2capEndpointSpec(
    val psm: Int,
    val serviceUuid: String,
    val authenticated: Boolean = true,
    val encrypted: Boolean = true,
    override val id: String,
) : EarbudTransportSpec {
    init {
        require(psm in 1..0xFFFF)
        require(serviceUuid.isNotBlank())
    }
}

/** Platform-independent identity of a possible BLE GATT peer. */
data class GattPeerIdentity(
    val deviceName: String?,
    val deviceAddress: String?,
    val serviceUuids: Set<String> = emptySet(),
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
)

/** Adapter-owned association rule between one audio device and a companion BLE endpoint. */
fun interface GattPeerMatcher {
    fun matches(sessionDevice: GattPeerIdentity, candidate: GattPeerIdentity): Boolean
}

/** Declarative BLE scan filter used only while resolving a companion control endpoint. */
data class GattScanFilterSpec(
    val manufacturerId: Int? = null,
    val serviceUuid: String? = null,
    val deviceName: String? = null,
) {
    init {
        require(manufacturerId == null || manufacturerId in 0..0xFFFF)
        require(serviceUuid == null || serviceUuid.isNotBlank())
        require(deviceName == null || deviceName.isNotBlank())
        require(manufacturerId != null || serviceUuid != null || deviceName != null) {
            "A companion GATT scan requires at least one stable filter"
        }
    }
}

/** Selects the physical Bluetooth device on which a GATT service is opened. */
sealed interface GattPeerSelection {
    /** Opens GATT directly on the A2DP/HFP device that owns the HyperEars session. */
    data object SessionDevice : GattPeerSelection

    /**
     * Resolves a separately advertised vendor-control endpoint.
     *
     * The Android runtime owns the bounded scan. The Adapter-owned matcher receives only stable,
     * platform-independent observations and therefore keeps vendor address layouts out of the
     * transport implementation.
     */
    data class CompanionDevice(
        val filter: GattScanFilterSpec,
        val matcher: GattPeerMatcher,
        val scanTimeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS,
    ) : GattPeerSelection {
        init {
            require(scanTimeoutMs in 1_000L..30_000L)
        }
    }

    companion object {
        const val DEFAULT_SCAN_TIMEOUT_MS = 8_000L
    }
}

enum class GattWriteMode {
    WITH_RESPONSE,
    WITHOUT_RESPONSE,
}

/**
 * BLE GATT transport whose characteristics carry the protocol's unmodified business frames.
 *
 * UUIDs are authoritative. Optional instance IDs pin a captured attribute table when a device
 * exposes duplicate characteristic UUIDs; runtimes still validate characteristic properties.
 */
data class GattTransportSpec(
    /** Optional service boundary used to disambiguate otherwise common characteristic UUIDs. */
    val serviceUuid: String? = null,
    val writeCharacteristicUuid: String,
    val notifyCharacteristicUuid: String,
    val writeInstanceId: Int? = null,
    val notifyInstanceId: Int? = null,
    val writeMode: GattWriteMode = GattWriteMode.WITH_RESPONSE,
    val notificationsRequired: Boolean = false,
    val peerSelection: GattPeerSelection = GattPeerSelection.SessionDevice,
    override val id: String,
) : EarbudTransportSpec {
    init {
        require(serviceUuid == null || serviceUuid.isNotBlank())
        require(writeCharacteristicUuid.isNotBlank())
        require(notifyCharacteristicUuid.isNotBlank())
    }
}

data class EarbudCapabilities(
    val battery: Boolean = false,
    val noiseControl: Boolean = false,
    val windNoiseControl: Boolean = false,
    val audioHandoff: Boolean = false,
    val spatialAudio: Boolean = false,
    val wearDetection: Boolean = false,
    val findDevice: Boolean = false,
)

/** One vendor control application that may take ownership of the private headset protocol. */
data class ControlAppSpec(
    val packageName: String,
    val displayName: String,
) {
    init {
        require(packageName.isNotBlank())
        require(displayName.isNotBlank())
    }
}

enum class AdapterResolution {
    STANDARD,
    EXACT_MATCH,
    FAMILY_MATCH,
    PROTOCOL_CONFIRMED,
}

enum class TransportKind {
    RFCOMM,
    GATT,
    L2CAP,
}

/** Immutable device-facing projection of the one active adapter instance. */
data class AdapterSnapshot(
    val id: String,
    val displayName: String,
    val resolution: AdapterResolution,
    val privateProtocolRequired: Boolean,
    val batterySource: BatterySource,
    val formFactor: HeadsetFormFactor,
    val capabilities: EarbudCapabilities,
    val supportedNoiseModes: Set<NoiseMode>,
    val presentationId: MiLinkCardPresentationId?,
    val transportKinds: Set<TransportKind>,
    val controlApps: List<ControlAppSpec> = emptyList(),
)

/**
 * Removes vendor-private telemetry and controls while preserving standard MiLink integration.
 *
 * The concrete identity and form factor remain visible, but the platform sees no private channel
 * requirement, model-specific card extension, or writable vendor capability until ownership
 * returns to HyperEars.
 */
fun AdapterSnapshot.standardIntegrationProjection(): AdapterSnapshot = copy(
    privateProtocolRequired = false,
    batterySource = BatterySource.SYSTEM_AGGREGATE,
    capabilities = EarbudCapabilities(
        battery = true,
        audioHandoff = true,
    ),
    supportedNoiseModes = emptySet(),
    presentationId = null,
    transportKinds = emptySet(),
)

data class AdapterRuntimeState(
    val features: DeviceFeatureSnapshot = DeviceFeatureSnapshot(),
) {
    /** Read-only standard feature projections for protocol-independent consumers. */
    val battery: EarbudBattery
        get() = features.get<BatteryFeatureState>()?.battery ?: EarbudBattery()

    val noiseMode: NoiseMode?
        get() = features.get<NoiseModeFeatureState>()?.mode
}

enum class AdapterActivation {
    KEEP_CHANNEL_READY,
    RESTART_ON_CURRENT_CHANNEL,
    RECONNECT,
}

sealed interface HandshakeResult {
    data object AwaitingEvidence : HandshakeResult
    data object Ready : HandshakeResult
    data object Rejected : HandshakeResult
    data class Replace(
        val adapter: EarbudAdapter,
        val activation: AdapterActivation,
    ) : HandshakeResult
}

data class AdapterIoResult(
    val commands: List<ByteArray> = emptyList(),
    val handshake: HandshakeResult? = null,
    val stateChanged: Boolean = false,
    val unknownFrames: List<ProtocolEvent.UnknownFrame> = emptyList(),
    val effects: List<AdapterEffect> = emptyList(),
)

data class AdapterControlResult(
    val accepted: Boolean,
    val commands: List<ByteArray> = emptyList(),
    val readback: List<ByteArray> = emptyList(),
    val stateChanged: Boolean = false,
)

/**
 * Physical presentation declared by an adapter.
 *
 * This is deliberately platform-neutral. The MiLink bridge maps it onto one known stock carrier
 * ID per form factor; concrete model identity never leaks into Xiaomi's device-ID registry.
 */
enum class HeadsetFormFactor {
    TWS,
    HEADPHONES,
}

/** Opaque link from a concrete device adapter to its platform-specific MiLink presentation. */
@JvmInline
value class MiLinkCardPresentationId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

/**
 * One private-protocol codec instance owned by one physical device session.
 *
 * Model selection and capabilities belong to [EarbudAdapter]; this interface only translates
 * between the common domain model and a vendor byte stream.
 */
interface ProtocolSession {
    fun initialReadCommands(): List<ByteArray>
    fun encode(request: ControlRequest): List<ByteArray>

    /**
     * Encodes an internal state read without passing it through the user-control contract.
     *
     * The default supports a complete refresh for existing protocols. Feature-specific reads are
     * opt-in so a runtime follow-up can never send an unrelated vendor command by accident.
     */
    fun query(request: TelemetryQuery): List<ByteArray> = when (request) {
        TelemetryQuery.RefreshAll -> encode(StandardControlRequest.Refresh)
        is TelemetryQuery.RefreshFeature -> emptyList()
    }

    /**
     * Protocol-generated writes produced while decoding incoming bytes.
     *
     * ACK-driven protocols use this to advance their request queue without owning the transport.
     * The runtime drains this exactly once after each [offer] call. Most protocols are passive and
     * retain the empty default.
     */
    fun drainImmediateCommands(): List<ByteArray> = emptyList()

    /**
     * Optional commands unlocked by an authoritative protocol event.
     *
     * This keeps family-safe discovery separate from model-specific reads. The session serializes
     * returned commands on the existing transport; protocols never own sockets or coroutines.
     */
    fun followUpCommands(event: ProtocolEvent): List<ByteArray> = emptyList()

    /** Optional authoritative readback sent after [encode] completes successfully. */
    fun readback(request: ControlRequest): List<ByteArray> = emptyList()

    fun offer(bytes: ByteArray): List<ProtocolEvent>
    fun reset()
}
