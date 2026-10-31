package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class AppleNativeRegistryTest {
    private val apple = EarbudIdentity("AirPods Pro", true, serviceUuids = setOf(AppleAirPodsAdapter.AAP_SERVICE_UUID))
    @Test fun registryRequiresExplicitConnectionPermissionAndStrongCandidate() {
        assertNull(EarbudAdapterRegistry.resolve(apple))
        assertTrue(EarbudAdapterRegistry.resolve(apple, appleNativeDeclined = true) is AppleAirPodsProAdapter)
        assertNull(EarbudAdapterRegistry.resolve(apple.copy(serviceUuids = emptySet()), appleNativeDeclined = true))
        assertNull(EarbudAdapterRegistry.resolve(apple.copy(standardHeadset = false), appleNativeDeclined = true))
        assertNull(EarbudAdapterRegistry.resolve(apple.copy(nativeSystemEarbud = true), appleNativeDeclined = true))
    }
    @Test fun renamedAppleAndNonAppleKeepTheirIndependentMatching() {
        assertTrue(EarbudAdapterRegistry.resolve(apple.copy(deviceName = "Renamed"), appleNativeDeclined = true) is AppleAirPodsAdapter)
        assertTrue(EarbudAdapterRegistry.resolve(EarbudIdentity("Unknown headset", true)) is StandardEarbudAdapter)
    }
}
