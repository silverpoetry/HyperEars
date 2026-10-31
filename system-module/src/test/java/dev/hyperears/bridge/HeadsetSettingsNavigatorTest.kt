package dev.hyperears.bridge

import dev.hyperears.integration.ControlAppSpec
import dev.hyperears.integration.ControlOwnership
import dev.hyperears.integration.DeviceLifecycle
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.StandardEarbudAdapter
import dev.hyperears.settings.MoreSettingsTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadsetSettingsNavigatorTest {
    private val address = "02:00:00:00:00:01"
    private val primary = ControlAppSpec("test.primary", "Primary")
    private val secondary = ControlAppSpec("test.secondary", "Secondary")
    private val state = EarbudState(
        address = address,
        adapter = StandardEarbudAdapter().snapshot().copy(controlApps = listOf(primary, secondary)),
    )
    private val calls = mutableListOf<String>()
    private var vendorResult = true
    private var moduleResult = true
    private var systemResult = true
    private var candidates = emptyList<ControlAppSpec>()
    private val navigator = HeadsetSettingsNavigator(
        openSystemSettings = { calls += "system:$it"; systemResult },
        openVendorApp = { candidates = it; calls += "vendor"; vendorResult },
        openHyperEars = { calls += "module"; moduleResult },
    )

    @Test
    fun systemSelectionUsesTheActualDeviceAddress() {
        assertTrue(navigator.open(address, state, MoreSettingsTarget.SYSTEM_SETTINGS))
        assertEquals(listOf("system:$address"), calls)
    }

    @Test
    fun activeControllerPrecedesDeclaredCandidatesWithoutDuplicates() {
        val yielded = state.copy(lifecycle = DeviceLifecycle(
            controlOwnership = ControlOwnership.EXTERNAL_APP,
            externalControlApp = secondary,
        ))
        assertTrue(navigator.open(address, yielded, MoreSettingsTarget.VENDOR_APP))
        assertEquals(listOf(secondary, primary), candidates)
        assertEquals(listOf("vendor"), calls)
    }

    @Test
    fun unavailableVendorFallsBackToTheSameDevice() {
        vendorResult = false
        assertTrue(navigator.open(address, state, MoreSettingsTarget.VENDOR_APP))
        assertEquals(listOf(primary, secondary), candidates)
        assertEquals(listOf("vendor", "system:$address"), calls)
    }

    @Test
    fun missingAdapterUsesSystemDetailsWithoutInventingAController() {
        assertTrue(navigator.open(address, state.copy(adapter = null), MoreSettingsTarget.VENDOR_APP))
        assertEquals(listOf("system:$address"), calls)
    }

    @Test
    fun anotherDevicesControllerIsNeverUsed() {
        assertTrue(navigator.open(address, state.copy(address = "02:00:00:00:00:02"), MoreSettingsTarget.VENDOR_APP))
        assertEquals(listOf("system:$address"), calls)
    }

    @Test
    fun unavailableModuleFallsBackAndReportsFailureWhenNeitherDestinationOpens() {
        moduleResult = false
        systemResult = false
        assertFalse(navigator.open(address, state, MoreSettingsTarget.HYPEREARS))
        assertEquals(listOf("module", "system:$address"), calls)
    }

    @Test
    fun eachClickUsesItsCurrentSelectionAndState() {
        assertTrue(navigator.open(address, state, MoreSettingsTarget.HYPEREARS))
        assertTrue(navigator.open(address, state.copy(adapter = null), MoreSettingsTarget.VENDOR_APP))
        assertTrue(navigator.open(address, state, MoreSettingsTarget.VENDOR_APP))
        assertEquals(listOf("module", "system:$address", "vendor"), calls)
    }

    @Test fun nativeRouteIsLimitedToTheAcceptedTozoAddress() {
        val nav=HeadsetSettingsNavigator({calls+="legacy";true},{false},{false},{calls+="native";true})
        val tozo=state.copy(adapter=dev.hyperears.integration.TozoNc20ProAdapter().snapshot(),
            lifecycle=DeviceLifecycle(systemProfile=dev.hyperears.integration.SystemProfileState.CONNECTED))
        nav.open(address,state,MoreSettingsTarget.SYSTEM_SETTINGS)
        nav.open(address,tozo,MoreSettingsTarget.SYSTEM_SETTINGS)
        nav.open("02:00:00:00:00:02",tozo,MoreSettingsTarget.SYSTEM_SETTINGS)
        assertEquals(listOf("legacy","native","legacy"),calls)
    }
}
