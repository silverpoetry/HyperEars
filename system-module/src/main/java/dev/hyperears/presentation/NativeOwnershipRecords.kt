package dev.hyperears.presentation

/** The native host's registrations, never HyperEars carrier IDs or Bluetooth display names. */
internal data class NativeOwnershipRecords(
    val deviceId: String,
    val fallbackDeviceId: String,
    val appleType: String,
    val thirdPartyType: String,
) {
    val admission: NativeAdmission
        get() = if (listOf(deviceId, fallbackDeviceId, appleType, thirdPartyType).any(String::isNotEmpty)) {
            NativeAdmission.SYSTEM
        } else NativeAdmission.HYPEREARS
}
