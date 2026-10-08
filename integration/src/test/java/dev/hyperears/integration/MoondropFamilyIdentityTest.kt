package dev.hyperears.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoondropFamilyIdentityTest {
    @Test
    fun reportedSpaceTravelNameUsesFamilyWithSystemBatteryAndNoPrivateProtocol() {
        for (name in listOf("Space Travel 2 Ultra", "SPACE TRAVEL 2 ULTRA", "SpaceTravel2Ultra")) {
            val adapter = requireNotNull(EarbudAdapterRegistry.resolve(
                EarbudIdentity(deviceName = name, standardHeadset = true),
            ))
            assertEquals(MoondropEarbudAdapter.ID, adapter.id)
            assertEquals(AdapterResolution.FAMILY_MATCH, adapter.resolution)
            assertFalse(adapter.privateProtocolRequired)
            assertTrue(adapter.transports.isEmpty())
            assertTrue(adapter.snapshot().capabilities.battery)
            assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
            assertFalse(adapter.snapshot().capabilities.noiseControl)
            assertTrue(adapter.snapshot().supportedNoiseModes.isEmpty())
        }
    }

    @Test
    fun aliasCannotOverrideNativeOwnershipOrNonHeadsetIdentity() {
        val adapter = MoondropEarbudAdapter()
        assertFalse(adapter.matches(EarbudIdentity(
            deviceName = "Space Travel 2 Ultra", standardHeadset = true, nativeSystemEarbud = true,
        )))
        assertFalse(adapter.matches(EarbudIdentity(deviceName = "Space Travel 2 Ultra")))
    }

    @Test
    fun similarUnverifiedNamesAndGenericSppDoNotSelectTheFamily() {
        for (name in listOf("Space Travel", "Space Travel 2", "Space Travel 2 Ultra Pro", "Other Space Travel 2 Ultra")) {
            assertFalse(MoondropEarbudAdapter().matches(EarbudIdentity(
                deviceName = name,
                standardHeadset = true,
                serviceUuids = setOf(MoondropRobinAdapter.STANDARD_SPP_UUID),
            )))
        }
    }
}
