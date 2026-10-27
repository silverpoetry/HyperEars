package dev.hyperears.integration

import dev.hyperears.protocol.flos2.FloS2WireCodec

/**
 * Shared brand policy for the Dreame Flo S2 / TheAstro OpenPro S2 sport earbuds; never registered
 * as a runtime fallback. The two brands ship identical hardware and protocol: the wire reports the
 * device name "Flo S2" while the BLE advertisement carries the brand-specific model name.
 */
abstract class FloS2EarbudAdapter(
    transferredSession: ProtocolSession? = null,
    initialRuntimeState: AdapterRuntimeState = AdapterRuntimeState(),
) : StandardEarbudAdapter(transferredSession, initialRuntimeState) {
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return BRAND_NAME_MARKERS.any(name::contains) ||
            identity.serviceUuids.any { it.equals(FloS2SportGattAdapter.SERVICE_UUID, ignoreCase = true) }
    }

    override val controlRequestContract: ControlRequestContract =
        StandardControlRequestContract.extending { adapter, request ->
            request is FloS2ControlRequest.SetSoundEffect &&
                adapter.runtimeState().features.get<FloS2SoundEffectFeatureState>() != null
        }

    override val featureStateContract: DeviceFeatureStateContract =
        StandardDeviceFeatureStateContract.extending { _, state ->
            state is FloS2SoundEffectFeatureState
        }

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy = when (request) {
        is FloS2ControlRequest.SetSoundEffect ->
            // The earbud acknowledges without echoing the selected value and pushes no event for
            // app-initiated changes; authoritative state arrives via the status readback.
            ControlExecutionPolicy(confirmation = ControlConfirmationPolicy.DEVICE_REPORT)

        else -> super.controlPolicy(request)
    }

    override fun onProtocolReset() {
        super.onProtocolReset()
        removeFeatureState(FloS2SoundEffectFeatureState.FEATURE_ID)
    }

    companion object {
        /** Brand identifiers used by the vendor app's own scan filter for both brand twins. */
        val BRAND_NAME_MARKERS = listOf("flos2", "openpros2", "haptifit")
    }
}

/**
 * Candidate for the vendor sport-earbud protocol on the `AC00` GATT service.
 *
 * A name match only authorizes the bounded read-only probe. Private battery and the sound-effect
 * control remain unavailable until the earbud returns a valid protocol frame.
 */
open class FloS2SportGattAdapter(
    transferredSession: ProtocolSession? = null,
    initialRuntimeState: AdapterRuntimeState = AdapterRuntimeState(),
) : FloS2EarbudAdapter(transferredSession, initialRuntimeState) {
    override val id: String = ID
    override val displayName: String = "FloS2 sport earbud family"
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE

    /**
     * Carries the sound-effect selector on MiLink's native mode-slot card. The presentation is
     * deliberately absent until a status frame confirms the sound-effect feature, mirroring the
     * capability gating of every private feature.
     */
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = FloS2MiLinkPresentationIds.SOUND_EFFECT.takeIf {
            runtimeState().features.get<FloS2SoundEffectFeatureState>() != null
        }

    override val transports: List<EarbudTransportSpec> = listOf(
        GattTransportSpec(
            serviceUuid = SERVICE_UUID,
            writeCharacteristicUuid = WRITE_CHARACTERISTIC_UUID,
            notifyCharacteristicUuid = NOTIFY_CHARACTERISTIC_UUID,
            // The vendor app writes without response on AC01.
            writeMode = GattWriteMode.WITHOUT_RESPONSE,
            id = "flos2-sport-gatt",
        ),
    )

    override fun createProtocolSession(): ProtocolSession = FloS2ProtocolSession()

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.FallbackTo(
            StandardEarbudAdapter(initialRuntimeState = runtimeState()),
        )

    companion object {
        const val ID = "flos2-sport-family"
        const val SERVICE_UUID = "0000ac00-0000-1000-8000-00805f9b34fb"
        const val WRITE_CHARACTERISTIC_UUID = "0000ac01-0000-1000-8000-00805f9b34fb"
        const val NOTIFY_CHARACTERISTIC_UUID = "0000ac02-0000-1000-8000-00805f9b34fb"
    }
}

/** Exact candidate for TheAstro OpenPro S2, captured on a real device. */
class TheAstroOpenProS2Adapter : FloS2SportGattAdapter() {
    override val id: String = ID
    override val displayName: String = "TheAstro OpenPro S2"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        return normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES
    }

    companion object {
        const val ID = "theastro-openpro-s2"
        private val MODEL_NAMES = setOf("openpros2")
    }
}
