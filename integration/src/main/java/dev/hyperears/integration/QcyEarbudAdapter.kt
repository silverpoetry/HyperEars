package dev.hyperears.integration

import dev.hyperears.protocol.qcy.QcyAdvertisementCodec

/** Shared brand policy for QCY and Crossky adapters; never registered as a runtime fallback. */
abstract class QcyEarbudAdapter(
    transferredSession: ProtocolSession? = null,
    initialRuntimeState: AdapterRuntimeState = AdapterRuntimeState(),
) : StandardEarbudAdapter(transferredSession, initialRuntimeState) {
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH
    override val controlApps: List<ControlAppSpec> = listOf(
        ControlAppCatalog.qcy,
        ControlAppCatalog.qcyChina,
    )

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return name.startsWith("qcy") ||
            name.startsWith("crossky") ||
            name in REPORTED_QCY_ALIASES
    }

    private companion object {
        val REPORTED_QCY_ALIASES = setOf("qycc50s")
    }
}

/**
 * Candidate for QCY's public `A001/1001/1002` GATT protocol family.
 *
 * A name match only authorizes the bounded read-only probe. Private battery and native
 * three-state noise controls remain unavailable until the headset returns a valid protocol frame.
 */
open class QcyStandardGattAdapter(
    transferredSession: ProtocolSession? = null,
    initialRuntimeState: AdapterRuntimeState = AdapterRuntimeState(),
) : QcyEarbudAdapter(transferredSession, initialRuntimeState) {
    override val id: String = ID
    override val displayName: String = "QCY standard GATT family"
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness =
        TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = listOf(
        GattTransportSpec(
            serviceUuid = SERVICE_UUID,
            writeCharacteristicUuid = WRITE_CHARACTERISTIC_UUID,
            notifyCharacteristicUuid = NOTIFY_CHARACTERISTIC_UUID,
            id = "qcy-standard-gatt-session-device",
        ),
        GattTransportSpec(
            serviceUuid = SERVICE_UUID,
            writeCharacteristicUuid = WRITE_CHARACTERISTIC_UUID,
            notifyCharacteristicUuid = NOTIFY_CHARACTERISTIC_UUID,
            peerSelection = GattPeerSelection.CompanionDevice(
                filter = GattScanFilterSpec(manufacturerId = QCY_MANUFACTURER_ID),
                matcher = QcyGattPeerMatcher,
            ),
            id = "qcy-standard-gatt-companion-device",
        ),
    )

    override fun createProtocolSession(): ProtocolSession = QcyProtocolSession()

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.FallbackTo(
            StandardEarbudAdapter(initialRuntimeState = runtimeState()),
        )

    companion object {
        const val ID = "qcy-standard-gatt-family"
        const val QCY_MANUFACTURER_ID = 0x521C
        const val SERVICE_UUID = "0000a001-0000-1000-8000-00805f9b34fb"
        const val WRITE_CHARACTERISTIC_UUID = "00001001-0000-1000-8000-00805f9b34fb"
        const val NOTIFY_CHARACTERISTIC_UUID = "00001002-0000-1000-8000-00805f9b34fb"
    }
}

/** Exact public-protocol candidate reported as QYCC50S / Crossky C50S. */
class QcyCrosskyC50sAdapter : QcyStandardGattAdapter() {
    override val id: String = ID
    override val displayName: String = "QCY Crossky C50S"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!identity.standardHeadset || identity.nativeSystemEarbud) return false
        return normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES
    }

    companion object {
        const val ID = "qcy-crossky-c50s"
        private val MODEL_NAMES = setOf(
            "qycc50s",
            "crosskyc50s",
            "qcycrosskyc50s",
        )
    }
}

/** Associates a separately advertised QCY control endpoint with its active audio device. */
internal object QcyGattPeerMatcher : GattPeerMatcher {
    override fun matches(
        sessionDevice: GattPeerIdentity,
        candidate: GattPeerIdentity,
    ): Boolean {
        val sessionName = normalize(sessionDevice.deviceName)
        val candidateName = normalize(candidate.deviceName)
        val exactCompanionName = sessionName.isNotEmpty() && candidateName == "${sessionName}app"
        val manufacturerData = candidate.manufacturerData[QcyStandardGattAdapter.QCY_MANUFACTURER_ID]

        // A bonded companion can be selected without a fresh advertisement only when its name is
        // derived exactly from the current audio endpoint.
        if (manufacturerData == null) return exactCompanionName

        val advertisement = QcyAdvertisementCodec.parse(manufacturerData) ?: return false
        val sessionAddress = normalizeAddress(sessionDevice.deviceAddress)
        val addressLinked = sessionAddress != null &&
            sequenceOf(advertisement.controlAddress, advertisement.otherAddress)
                .mapNotNull(::normalizeAddress)
                .any { it == sessionAddress }
        if (addressLinked || exactCompanionName) return true

        return candidateName == "qcyapp" && sessionName.startsWith("qcy")
    }

    private fun normalize(value: String?): String =
        value.orEmpty().lowercase().filter(Char::isLetterOrDigit)

    private fun normalizeAddress(value: String?): String? = value
        ?.replace(":", "")
        ?.uppercase()
        ?.takeIf { it.length == 12 }
}
