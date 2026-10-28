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
        assertFalse(adapter.matches(EarbudIdentity(deviceName = "Space Travel 2 Ultra", standardHeadset = false)))
    }

    @Test
    fun similarUnverifiedNamesAndGenericSppDoNotSelectTheFamily() {
        for (name in listOf("Space Travel", "Space Travel 2 Ultra Pro", "Other Space Travel 2 Ultra")) {
            assertFalse(MoondropEarbudAdapter().matches(EarbudIdentity(
                deviceName = name,
                standardHeadset = true,
                serviceUuids = setOf(MoondropRobinAdapter.STANDARD_SPP_UUID),
            )))
        }
    }

    @Test
    fun officialCatalogAliasesRemainStandardOnlyAndExactProtocolsKeepPriority() {
        for (name in listOf("Space Travel 2", "Pill Yamada Ryo", "H.I.D.E.404_Klukai", "BIANCA:STIGMATA", "SUSANOO TWS")) {
            val identity = EarbudIdentity(name, standardHeadset = true)
            val adapter = requireNotNull(EarbudAdapterRegistry.resolve(identity))
            assertEquals(MoondropEarbudAdapter.ID, adapter.id)
            assertFalse(adapter.privateProtocolRequired)
            assertTrue(adapter.transports.isEmpty())
            assertFalse(adapter.snapshot().capabilities.noiseControl)
            assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.snapshot().batterySource)
            assertFalse(adapter.matches(identity.copy(nativeSystemEarbud = true)))
        }
        assertEquals(MoondropRobinAdapter.ID, EarbudAdapterRegistry.resolve(
            EarbudIdentity("Robin's Earphones", standardHeadset = true),
        )?.id)
        assertFalse(MoondropEarbudAdapter().matches(EarbudIdentity("Echo-BP", standardHeadset = true)))
        assertFalse(MoondropEarbudAdapter().matches(EarbudIdentity("Pill", standardHeadset = true)))
    }
}
