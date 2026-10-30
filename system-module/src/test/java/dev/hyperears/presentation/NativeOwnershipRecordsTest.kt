package dev.hyperears.presentation

import org.junit.Assert.*
import org.junit.Test

class NativeOwnershipRecordsTest {
    private val empty = NativeOwnershipRecords("", "", "", "")

    @Test
    fun nativeHostCanAuthorizeAnUnregisteredDeviceWithoutMiLink() {
        assertEquals(NativeAdmission.HYPEREARS, empty.admission)
        val gate = ConnectionPresentationGate()
        val metadata = NativePresentationMetadata(HeadsetConnection("physical", 100))
        assertFalse(gate.tryPresent(metadata, 110) { fail("Unconfirmed"); true })
        assertTrue(gate.tryPresent(metadata.copy(admission = empty.admission), 120) { true })
        assertFalse(gate.tryPresent(metadata.copy(admission = empty.admission), 130) { fail("Duplicate"); true })
    }

    @Test
    fun everyNativeRegistrationReservesTheDevice() {
        listOf(
            empty.copy(deviceId = "native-id"),
            empty.copy(fallbackDeviceId = "native-id"),
            empty.copy(appleType = "0"),
            empty.copy(thirdPartyType = "registered-third-party"),
        ).forEach { assertEquals(NativeAdmission.SYSTEM, it.admission) }
    }

    @Test
    fun nativeRegistrationArrivingLaterOverridesTheEarlierEmptyLookup() {
        assertEquals(NativeAdmission.HYPEREARS, empty.admission)
        assertEquals(NativeAdmission.SYSTEM, empty.copy(deviceId = "native-id").admission)
    }

    @Test
    fun onlyOwnershipKeysInvalidateTheCurrentDevice() {
        val address = "02:00:00:00:00:01"
        listOf(null, "saved$address", "$address-type", "third_party_type$address").forEach {
            assertTrue(NativeOwnershipStore.affects(address, it))
        }
        listOf("battery$address", "saved02:00:00:00:00:02", "$address-model").forEach {
            assertFalse(NativeOwnershipStore.affects(address, it))
        }
    }
}
