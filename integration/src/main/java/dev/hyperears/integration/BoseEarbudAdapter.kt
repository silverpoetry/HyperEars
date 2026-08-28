package dev.hyperears.integration

import dev.hyperears.protocol.bose.BoseBmapWireCodec
import dev.hyperears.protocol.bose.BoseProductCatalog
import java.util.Locale

/**
 * Shared Bose BMAP headset behavior.
 *
 * Family detection only reads properties already cached by Android. The private channel then
 * confirms Bose's product ID through BMAP `[0.3]`; names and OUIs never unlock model controls.
 */
open class BoseEarbudAdapter(
    transferredSession: ProtocolSession? = null,
    initialRuntimeState: AdapterRuntimeState = AdapterRuntimeState(),
) : StandardEarbudAdapter(transferredSession, initialRuntimeState) {
    override val id: String = ID
    override val displayName: String = "Bose BMAP headset"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val controlApps: List<ControlAppSpec> = listOf(
        ControlAppCatalog.bose,
        ControlAppCatalog.boseConnect,
    )
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness =
        TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = listOf(
        RfcommEndpointSpec.Channel(number = 8),
        RfcommEndpointSpec.ServiceUuid(
            uuid = STANDARD_SPP_UUID,
            id = "spp-uuid",
        ),
        RfcommEndpointSpec.ServiceUuid(
            // This is the Apple iAP2 accessory-side service UUID. Bose uses it as one
            // BMAP/RFCOMM endpoint, but other vendors advertise the same service too.
            uuid = IAP2_ACCESSORY_UUID,
            id = "iap2-accessory-rfcomm",
        ),
        RfcommEndpointSpec.Channel(number = 2),
    )
    open val wireConfig: BoseWireConfig? = null

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        val oui = identity.deviceAddress
            ?.uppercase(Locale.ROOT)
            ?.take(8)
        val hasBoseVendorService = identity.serviceUuids.any { uuid ->
            uuid.equals(BOSE_BMAP_BLE_SERVICE_UUID, ignoreCase = true)
        }
        return hasBoseVendorService || BOSE_NAME_MARKERS.any(name::contains) || oui in BOSE_OUIS
    }

    override fun createProtocolSession(): ProtocolSession =
        BoseBmapProtocolSession(
            expectedProductId = wireConfig?.productId,
            fallbackFormFactor = formFactor,
        ).also { session -> wireConfig?.let(session::configure) }

    override fun onProductIdentified(productId: Int): HandshakeResult? {
        if (wireConfig?.productId == productId) return null
        val identifiedConfig = BoseBmapModelRegistry.find(productId) ?: return null
        if (!isAdapterEnabled(identifiedConfig.modelId)) return null
        val session = protocolSession as? BoseBmapProtocolSession ?: return null
        val next = BoseRuntimeAdapterFactory.create(
            productId = productId,
            protocolSession = session,
            runtimeState = runtimeState(),
        ) ?: return null
        return selectReplacement(next, AdapterActivation.KEEP_CHANNEL_READY)
    }

    override fun onCapabilitiesIdentified(
        battery: Boolean,
        noiseModes: Set<NoiseMode>,
        gameMode: Boolean,
    ): HandshakeResult? {
        val session = protocolSession as? BoseBmapProtocolSession ?: return null
        val discovered = session.discoveredConfig
            ?: return super.onCapabilitiesIdentified(battery, noiseModes, gameMode)
        val nextConfig = wireConfig?.copy(noiseControl = discovered.noiseControl) ?: discovered
        if (wireConfig == nextConfig) {
            return super.onCapabilitiesIdentified(battery, noiseModes, gameMode)
        }
        val next = BoseRuntimeAdapterFactory.create(
            configuration = nextConfig,
            formFactor = formFactor,
            displayName = if (wireConfig == null) {
                BoseCapabilityConfigRegistry.displayName(formFactor, nextConfig)
            } else {
                displayName
            },
            presentationId = miLinkCardPresentationId
                ?: BoseCapabilityConfigRegistry.presentationId(nextConfig),
            protocolSession = session,
            runtimeState = runtimeState(),
        )
        return selectReplacement(next, AdapterActivation.KEEP_CHANNEL_READY)
    }

    companion object {
        const val ID = "bose-bmap-family"
        const val STANDARD_SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"
        /**
         * Common Apple iAP2 accessory-side UUID used by Bose for a legacy BMAP RFCOMM endpoint.
         * It is transport-only and must never be used as a Bose identity predicate.
         */
        const val IAP2_ACCESSORY_UUID = "00000000-deca-fade-deca-deafdecacaff"

        /** Bluetooth SIG service UUID assigned to Bose Corporation for its BMAP BLE service. */
        const val BOSE_BMAP_BLE_SERVICE_UUID = "0000febe-0000-1000-8000-00805f9b34fb"

        /** @deprecated Use [IAP2_ACCESSORY_UUID]; this UUID is not a Bose identity. */
        @Deprecated("Transport endpoint only; do not use for device identity")
        const val BMAP_UUID = IAP2_ACCESSORY_UUID

        private val BOSE_NAME_MARKERS = setOf(
            "bose",
            "quietcomfort",
            "qc30",
            "qc35",
            "qc45",
            "soundsport",
        )

        /** Bose-owned OUI observed on the locally captured QuietComfort Headphones. */
        private val BOSE_OUIS = setOf("BC:87:FA")
    }
}

/** Bose's over-ear family, selected from Android's stable Bluetooth device class. */
open class BoseHeadphonesAdapter(
    transferredSession: ProtocolSession? = null,
    initialRuntimeState: AdapterRuntimeState = AdapterRuntimeState(),
) : BoseEarbudAdapter(transferredSession, initialRuntimeState) {
    override val id: String = ID
    override val displayName: String = "Bose headphones"
    override val formFactor: HeadsetFormFactor = HeadsetFormFactor.HEADPHONES

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            (
                identity.bluetoothDeviceClass == BLUETOOTH_DEVICE_CLASS_HEADPHONES ||
                    normalizeDeviceName(identity.deviceName.orEmpty()).contains("headphones")
                )

    companion object {
        const val ID = "bose-headphones-family"

        // android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES
        const val BLUETOOTH_DEVICE_CLASS_HEADPHONES = 0x0418
    }
}

/** Wire-level noise-control dialect owned by a concrete Bose adapter. */
sealed interface BoseNoiseControlConfig {
    val supportedModes: Set<NoiseMode>

    data class AudioModes(
        val quietModeIndex: Int = 0,
        val awareModeIndex: Int = 1,
        val additionalAncModeIndices: Set<Int> = emptySet(),
        val fullAwareCnc: Int = 10,
        val modeConfigLayout: BoseBmapWireCodec.ModeConfigLayout? = null,
        val windModeFromConfig: Boolean = false,
        override val supportedModes: Set<NoiseMode>,
    ) : BoseNoiseControlConfig

    data class Anr(
        val offValue: Int = 0,
        val highValue: Int = 1,
        val windValue: Int = 2,
        override val supportedModes: Set<NoiseMode> = setOf(
            NoiseMode.ANC,
            NoiseMode.OFF,
            NoiseMode.WIND,
        ),
    ) : BoseNoiseControlConfig

    data class Cnc(
        val maximumRawLevel: Int = 10,
        override val supportedModes: Set<NoiseMode> = setOf(
            NoiseMode.ANC,
            NoiseMode.OFF,
            NoiseMode.TRANSPARENCY,
        ),
    ) : BoseNoiseControlConfig
}

/**
 * Immutable wire configuration embedded in a Bose adapter.
 *
 * [productId] is present for a concrete model confirmed by `[0.3]`. It is absent only for a
 * family fallback whose wire dialect was established by a successful read-only capability probe.
 */
data class BoseWireConfig(
    val productId: Int?,
    val modelId: String,
    val noiseControl: BoseNoiseControlConfig? = null,
)

/** Common concrete-model behavior for Bose products represented as TWS/in-ear devices. */
abstract class BoseBmapModelAdapter(
    final override val id: String,
    product: BoseProductCatalog.Product,
    noiseControl: BoseNoiseControlConfig? = null,
    final override val miLinkCardPresentationId: MiLinkCardPresentationId? = null,
) : BoseEarbudAdapter() {
    final override val displayName: String = product.displayName
    final override val wireConfig: BoseWireConfig = BoseWireConfig(
        productId = product.productId,
        modelId = id,
        noiseControl = noiseControl,
    )
    final override val supportedNoiseModes: Set<NoiseMode> =
        noiseControl?.supportedModes.orEmpty()
    final override val capabilities: EarbudCapabilities = super.capabilities.copy(
        battery = true,
        noiseControl = supportedNoiseModes.isNotEmpty(),
        windNoiseControl = NoiseMode.WIND in supportedNoiseModes,
    )
    final override val resolution: AdapterResolution = AdapterResolution.PROTOCOL_CONFIRMED

    /** Concrete Bose models are selected by BMAP product ID, never by a mutable display name. */
    final override fun matches(identity: EarbudIdentity): Boolean = false
}

/** Common concrete-model behavior for Bose over-ear products. */
abstract class BoseBmapHeadphonesModelAdapter(
    final override val id: String,
    product: BoseProductCatalog.Product,
    noiseControl: BoseNoiseControlConfig? = null,
    final override val miLinkCardPresentationId: MiLinkCardPresentationId? = null,
) : BoseHeadphonesAdapter() {
    final override val displayName: String = product.displayName
    final override val wireConfig: BoseWireConfig = BoseWireConfig(
        productId = product.productId,
        modelId = id,
        noiseControl = noiseControl,
    )
    final override val supportedNoiseModes: Set<NoiseMode> =
        noiseControl?.supportedModes.orEmpty()
    final override val capabilities: EarbudCapabilities = super.capabilities.copy(
        battery = true,
        noiseControl = supportedNoiseModes.isNotEmpty(),
        windNoiseControl = NoiseMode.WIND in supportedNoiseModes,
    )
    final override val resolution: AdapterResolution = AdapterResolution.PROTOCOL_CONFIRMED

    /** Concrete Bose models are selected by BMAP product ID, never by a mutable display name. */
    final override fun matches(identity: EarbudIdentity): Boolean = false
}

private class BoseConfirmedEarbudAdapter(
    private val configuration: BoseWireConfig,
    override val displayName: String,
    override val miLinkCardPresentationId: MiLinkCardPresentationId?,
    session: BoseBmapProtocolSession,
    runtimeState: AdapterRuntimeState,
) : BoseEarbudAdapter(session, runtimeState) {
    override val id: String = configuration.modelId
    override val wireConfig: BoseWireConfig = configuration
    override val resolution: AdapterResolution = AdapterResolution.PROTOCOL_CONFIRMED
    override val supportedNoiseModes: Set<NoiseMode> =
        configuration.noiseControl?.supportedModes.orEmpty()
    override val capabilities: EarbudCapabilities = super.capabilities.copy(
        battery = true,
        noiseControl = supportedNoiseModes.isNotEmpty(),
        windNoiseControl = NoiseMode.WIND in supportedNoiseModes,
    )
    override fun matches(identity: EarbudIdentity): Boolean = false

    init {
        session.configure(configuration)
    }
}

private class BoseConfirmedHeadphonesAdapter(
    private val configuration: BoseWireConfig,
    override val displayName: String,
    override val miLinkCardPresentationId: MiLinkCardPresentationId?,
    session: BoseBmapProtocolSession,
    runtimeState: AdapterRuntimeState,
) : BoseHeadphonesAdapter(session, runtimeState) {
    override val id: String = configuration.modelId
    override val wireConfig: BoseWireConfig = configuration
    override val resolution: AdapterResolution = AdapterResolution.PROTOCOL_CONFIRMED
    override val supportedNoiseModes: Set<NoiseMode> =
        configuration.noiseControl?.supportedModes.orEmpty()
    override val capabilities: EarbudCapabilities = super.capabilities.copy(
        battery = true,
        noiseControl = supportedNoiseModes.isNotEmpty(),
        windNoiseControl = NoiseMode.WIND in supportedNoiseModes,
    )
    override fun matches(identity: EarbudIdentity): Boolean = false

    init {
        session.configure(configuration)
    }
}

internal object BoseRuntimeAdapterFactory {
    fun create(
        productId: Int,
        protocolSession: BoseBmapProtocolSession,
        runtimeState: AdapterRuntimeState,
    ): EarbudAdapter? {
        val definition = BoseBmapModelRegistry.adapters.firstOrNull { adapter ->
            val config = when (adapter) {
                is BoseBmapModelAdapter -> adapter.wireConfig
                is BoseBmapHeadphonesModelAdapter -> adapter.wireConfig
                else -> null
            }
            config?.productId == productId
        }
            ?: return null
        val configuration = when (definition) {
            is BoseBmapModelAdapter -> definition.wireConfig
            is BoseBmapHeadphonesModelAdapter -> definition.wireConfig
            is BoseEarbudAdapter -> definition.wireConfig
            else -> null
        } ?: return null
        return create(
            configuration = configuration,
            formFactor = definition.formFactor,
            displayName = definition.displayName,
            presentationId = definition.miLinkCardPresentationId,
            protocolSession = protocolSession,
            runtimeState = runtimeState,
        )
    }

    fun create(
        configuration: BoseWireConfig,
        formFactor: HeadsetFormFactor,
        displayName: String,
        presentationId: MiLinkCardPresentationId?,
        protocolSession: BoseBmapProtocolSession,
        runtimeState: AdapterRuntimeState,
    ): EarbudAdapter = if (formFactor == HeadsetFormFactor.HEADPHONES) {
            BoseConfirmedHeadphonesAdapter(
                configuration,
                displayName,
                presentationId,
                protocolSession,
                runtimeState,
            )
        } else {
            BoseConfirmedEarbudAdapter(
                configuration,
                displayName,
                presentationId,
                protocolSession,
                runtimeState,
            )
        }
}

/** Opaque presentation contracts shared by models with the same native-card semantics. */
object BoseMiLinkPresentationIds {
    val TWO_MODE = MiLinkCardPresentationId("bose-anc-aware-two-mode")
    val WIND_REPLACES_OFF = MiLinkCardPresentationId("bose-wind-replaces-off")
    val WIND_REPLACES_TRANSPARENCY =
        MiLinkCardPresentationId("bose-wind-replaces-transparency")
}
