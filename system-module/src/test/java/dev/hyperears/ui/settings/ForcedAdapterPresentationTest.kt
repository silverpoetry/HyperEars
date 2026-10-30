package dev.hyperears.ui.settings

import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test

class ForcedAdapterPresentationTest {
    @Test
    fun disconnectedOverridesRemainAvailableForResetAndConnectedDevicesComeFirst() {
        val states = listOf(EarbudState(deviceName = "OnePlus Buds 4", address = "02:00:00:00:00:02",
            lifecycle = DeviceLifecycle(systemProfile = SystemProfileState.CONNECTED)))
        val devices = forcedAdapterDevices(states, mapOf("02:00:00:00:00:01" to "oppo-enco-family"))
        assertEquals(listOf("02:00:00:00:00:02", "02:00:00:00:00:01"), devices.map { it.address })
        assertEquals("OnePlus Buds 4", devices.first().name)
        assertFalse(devices.last().connected)
    }

    @Test
    fun searchUsesTheRegistryMetadataAndHandlesNoMatches() {
        assertTrue(filterProbeGroups(EarbudAdapterRegistry.probeGroups, "definitely-not-an-adapter").isEmpty())
        val filtered = filterProbeGroups(EarbudAdapterRegistry.probeGroups, "oppo family")
        assertEquals(listOf(OppoEarbudAdapter.ID), filtered.flatMap { it.adapters }.map { it.id })
    }
}
