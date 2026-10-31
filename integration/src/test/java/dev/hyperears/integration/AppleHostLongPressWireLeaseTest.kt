package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class AppleHostLongPressWireLeaseTest {
    @Test fun protocolReadbackAdvancesAcknowledgementOnlyOnRealRoutingReports() {
        val adapter = AppleAirPodsAdapter()
        fun report(mask: Int, a: Int, b: Int, c: Int) =
            byteArrayOf(4, 0, 4, 0, 9, 0, 0x39, mask.toByte(), a.toByte(), b.toByte(), c.toByte())
        adapter.receive(report(0x12, 0xa5, 0x55, 0x33))
        val before = adapter.runtimeState().features.get<AppleGestureSettingsFeatureState>()!!
        assertEquals(1L, before.interceptionSequence)
        val begin = adapter.executeControl(AppleHostLongPressLeaseRequest("host", true))
        assertTrue(begin.accepted)
        assertArrayEquals(report(0x1a, 0xa5, 0x55, 0x33), begin.commands.single())
        assertFalse(begin.readback.isEmpty())
        assertEquals(before, adapter.runtimeState().features.get<AppleGestureSettingsFeatureState>())
        adapter.receive(report(0x3a, 1, 2, 3))
        assertEquals(2L, adapter.runtimeState().features.get<AppleGestureSettingsFeatureState>()!!.interceptionSequence)
        val end = adapter.executeControl(AppleHostLongPressLeaseRequest("host", false))
        assertArrayEquals(report(0x32, 1, 2, 3), end.commands.single())
    }
    @Test fun acquireAndRestorePreserveUnownedBitsAndLatestTrailingBytes() {
        val lease = AppleHostLongPressWireLease()
        assertEquals(listOf(0x1b, 1, 2, 3), lease.change(AppleHostLongPressLeaseRequest("owner", true), listOf(0x13, 1, 2, 3)))
        assertNull(lease.change(AppleHostLongPressLeaseRequest("stale", false), listOf(0x1b, 4, 5, 6)))
        assertEquals(listOf(0x13, 4, 5, 6), lease.change(AppleHostLongPressLeaseRequest("owner", false), listOf(0x1b, 4, 5, 6)))
    }
    @Test fun neverStealsExistingRouteOrRestoresAcrossReset() {
        val lease = AppleHostLongPressWireLease()
        assertNull(lease.change(AppleHostLongPressLeaseRequest("owner", true), listOf(8, 0, 0, 0)))
        assertNull(lease.change(AppleHostLongPressLeaseRequest("owner", true), null))
        assertNotNull(lease.change(AppleHostLongPressLeaseRequest("owner", true), listOf(0, 0, 0, 0)))
        lease.clear()
        assertNull(lease.change(AppleHostLongPressLeaseRequest("owner", false), listOf(8, 0, 0, 0)))
    }
    @Test fun typedLeaseRequestRoundTripsWithoutChangingItsOwner() {
        val request = AppleHostLongPressLeaseRequest("session-owner", true)
        assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
    }
}
