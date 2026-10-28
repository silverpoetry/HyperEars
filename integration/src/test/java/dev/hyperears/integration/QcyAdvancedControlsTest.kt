package dev.hyperears.integration

import dev.hyperears.protocol.qcy.QcyWireCodec
import org.junit.Assert.*
import org.junit.Test

class QcyAdvancedControlsTest {
    @Test fun optionalReportsIndependentlyIdentifySettings() {
        for (command in listOf(9, 16, 35, 36, 39, 45, 64)) {
            val session = QcyProtocolSession()
            val events = session.offer(report(command, 1))
            assertTrue("Missing optional report $command", events.any { it is ProtocolEvent.FeatureStateChanged })
            assertTrue(events.contains(ProtocolEvent.HandshakeAccepted))
        }
    }
    @Test fun eachToggleGatesItselfAndWritesWithoutOptimismThenReadsBack() {
        QcyToggle.entries.forEach { setting ->
            val adapter = QcyStandardGattAdapter()
            val request = QcyControlRequest.SetToggle(setting, true)
            assertFalse(adapter.executeControl(request).accepted)
            adapter.receive(report(setting.command, setting.wire().off))
            val result = adapter.executeControl(request)
            assertTrue(result.accepted)
            assertFalse(result.stateChanged)
            assertEquals(false, adapter.runtimeState().features.get<QcySettingsFeatureState>()!!.toggles[setting])
            assertEquals(listOf(report(setting.command, 1).toList()), result.commands.map(ByteArray::toList))
            assertEquals(listOf(QcyWireCodec.query(setting.command).toList()), result.readback.map(ByteArray::toList))
            QcyToggle.entries.filter { it != setting }.forEach { other ->
                assertFalse(adapter.executeControl(QcyControlRequest.SetToggle(other, true)).accepted)
            }
            adapter.receive(report(setting.command, 1))
            assertEquals(true, adapter.runtimeState().features.get<QcySettingsFeatureState>()!!.toggles[setting])
            adapter.resetProtocolSession()
            assertNull(adapter.runtimeState().features.get<QcySettingsFeatureState>())
            assertFalse(adapter.executeControl(request).accepted)
        }
    }
    @Test fun malformedReportsAndResetCannotCreateCapabilities() {
        val adapter = QcyStandardGattAdapter()
        listOf(report(9, 0), report(39, 2), report(45, 3), report(32, 7), byteArrayOf(-1, 4, 9, 2, 1, 2)).forEach(adapter::receive)
        assertNull(adapter.runtimeState().features.get<QcySettingsFeatureState>())
        assertNull(adapter.miLinkCardPresentationId)
        val session = QcyProtocolSession()
        session.offer(report(9, 2))
        session.reset()
        assertTrue(session.encode(QcyControlRequest.SetToggle(QcyToggle.LOW_LATENCY, true)).isEmpty())
    }
    @Test fun bothEqReportsConfirmPresetButCustomRequiresFullParameters() {
        val adapter = QcyStandardGattAdapter()
        adapter.receive(report(34, 1))
        assertTrue(adapter.executeControl(QcyControlRequest.SetEqPreset(2)).accepted)
        assertFalse(adapter.executeControl(QcyControlRequest.SetCustomEq(List(10) { 0 })).accepted)
        adapter.receive(QcyWireCodec.setCustomEq(List(10) { -1 }))
        assertEquals(0, adapter.runtimeState().features.get<QcySettingsFeatureState>()!!.eqPreset)
        val gains = List(10) { 2 }
        val request = QcyControlRequest.SetCustomEq(gains)
        val result = adapter.executeControl(request)
        assertTrue(result.accepted)
        assertEquals(QcyWireCodec.setCustomEq(gains).toList(), result.commands.single().toList())
        assertEquals(List(10) { -1 }, adapter.runtimeState().features.get<QcySettingsFeatureState>()!!.equalizer!!.customGains())
        assertFalse(adapter.executeControl(QcyControlRequest.SetCustomEq(List(9) { 0 })).accepted)
        assertFalse(adapter.executeControl(QcyControlRequest.SetCustomEq(List(10) { 9 })).accepted)
        adapter.receive(report(32, 1))
        assertTrue(adapter.executeControl(QcyControlRequest.SetEqPreset(10)).accepted)
        assertFalse(adapter.executeControl(QcyControlRequest.SetEqPreset(7)).accepted)
    }
    @Test fun spatialRemainsVendorStateAndAllTypedRequestsRoundTrip() {
        val adapter = QcyStandardGattAdapter()
        adapter.receive(report(45, 2))
        assertEquals(2, adapter.runtimeState().features.get<QcySettingsFeatureState>()!!.spatialMode)
        assertTrue(adapter.executeControl(QcyControlRequest.SetSpatialMode(0)).accepted)
        assertFalse(adapter.executeControl(QcyControlRequest.SetSpatialMode(3)).accepted)
        listOf(QcyControlRequest.SetToggle(QcyToggle.SLEEP, true), QcyControlRequest.SetSpatialMode(1),
            QcyControlRequest.SetEqPreset(10), QcyControlRequest.SetCustomEq(List(10) { -8 })).forEach {
            assertEquals(it, ControlRequestTransport.decode(ControlRequestTransport.encode(it)))
        }
    }
    private fun report(command: Int, value: Int) = byteArrayOf(-1, 3, command.toByte(), 1, value.toByte())
}
