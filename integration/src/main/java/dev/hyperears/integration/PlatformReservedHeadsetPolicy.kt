package dev.hyperears.integration

/**
 * Headsets deliberately reserved for platform-native integrations.
 *
 * This policy runs before the Adapter registry, including before the standard Bluetooth fallback.
 * Strong Apple AAP UUID evidence is preferred; the AirPods name marker is a conservative fallback
 * for devices whose SDP UUID cache is not populated when A2DP first connects.
 */
object PlatformReservedHeadsetPolicy {
    fun reserves(identity: EarbudIdentity, appleNativeDeclined: Boolean = false): Boolean {
        if (identity.nativeSystemEarbud) return true
        val normalizedName = identity.deviceName.orEmpty()
            .lowercase()
            .filter(Char::isLetterOrDigit)
        val appleName = APPLE_NAME_MARKERS.any(normalizedName::contains)
        val appleService = identity.serviceUuids.any { uuid ->
            AppleAirPodsAdapter.AAP_SERVICE_UUIDS.any {
                it.equals(uuid, ignoreCase = true)
            }
        }
        return (appleName || appleService) && !(appleNativeDeclined && appleService && identity.standardHeadset)
    }

    fun isAppleCandidate(identity: EarbudIdentity): Boolean =
        !identity.nativeSystemEarbud && (identity.deviceName.orEmpty().lowercase()
            .filter(Char::isLetterOrDigit).contains("airpods") || identity.serviceUuids.any { uuid ->
            AppleAirPodsAdapter.AAP_SERVICE_UUIDS.any { it.equals(uuid, ignoreCase = true) }
        })

    private val APPLE_NAME_MARKERS = setOf("airpods")
}
