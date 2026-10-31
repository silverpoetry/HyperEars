package dev.hyperears.integration

import dev.hyperears.protocol.apple.AppleAapWireCodec.Component
import dev.hyperears.protocol.apple.AppleProximityReport
import org.junit.Assert.*
import org.junit.Test

class AppleProximityIntegrationTest {
    private fun report(precise: Boolean = true) = AppleProximityReport(
        0x2013, true, Component(81, false), Component(72, true), Component(63, false), precise,
    )
    @Test fun onlyConfirmedLidEdgeChangesPopupSequenceAndResetClearsEvidence() {
        val adapter = AppleAirPodsAdapter()
        assertTrue(adapter.acceptProximityReport(report()))
        assertEquals(0L, adapter.runtimeState().features.get<AppleCaseFeatureState>()!!.sequence)
        assertTrue(adapter.acceptProximityCaseOpen())
        assertEquals(1L, adapter.runtimeState().features.get<AppleCaseFeatureState>()!!.sequence)
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get<AppleCaseFeatureState>())
    }
    @Test fun approximateDoesNotBecomePrecisionAndAacpKeepsPrecedence() {
        val adapter = AppleAirPodsAdapter()
        adapter.acceptProximityReport(report(false))
        assertEquals(BatterySource.SYSTEM_AGGREGATE, adapter.effectiveBatterySource())
        adapter.receive(byteArrayOf(4, 0, 4, 0, 4, 0, 1, 4, 1, 91, 2, 1))
        adapter.acceptProximityReport(report())
        val battery = adapter.runtimeState().features.get<BatteryFeatureState>()!!.battery
        assertEquals(91, battery.left.percent)
        assertEquals(72, battery.right.percent)
        assertEquals(63, battery.case.percent)
        assertEquals(BatterySource.PRIVATE_PROTOCOL, adapter.effectiveBatterySource())
    }
}
