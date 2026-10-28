package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class RoseRfcommSettingsTest {
    private fun report(vararg payload: Int): ByteArray {
        val body = byteArrayOf(-35, 0, payload.size.toByte()) + payload.map(Int::toByte)
        return body + byteArrayOf((body.sumOf { it.toInt() and 255 } and 255).toByte(), -86)
    }
    @Test fun i7AndMk2PermitOnlyTheirReportedGameSetting() {
        listOf(RoseEarfeelI7Adapter(), RoseBudsFeelMk2Adapter()).forEach { adapter ->
            adapter.receive(report(2, 14, 0, 2, 42, 2, 2, 44, 3, 2, 46, 5))
            assertEquals(mapOf(RoseRfcommSetting.GAME to 0), adapter.runtimeState().features.get<RoseRfcommSettingsFeatureState>()!!.values)
            assertTrue(adapter.executeControl(RoseRfcommControlRequest.SetValue(RoseRfcommSetting.GAME, 1)).accepted)
            assertFalse(adapter.executeControl(RoseRfcommControlRequest.SetValue(RoseRfcommSetting.EQ, 1)).accepted)
            assertFalse(adapter.executeControl(RoseRfcommControlRequest.SetValue(RoseRfcommSetting.NOISE, 5)).accepted)
        }
    }
    @Test fun ceramicsUExposesSixDistinctModesAndEachOptionalFieldNeedsItsOwnReport() {
        val adapter = RoseCeramicsUAdapter()
        val noise = RoseRfcommControlRequest.SetValue(RoseRfcommSetting.NOISE, 6)
        assertFalse(adapter.executeControl(noise).accepted)
        adapter.receive(report(9, 5))
        assertEquals(5, adapter.runtimeState().features.get<RoseRfcommSettingsFeatureState>()!!.values[RoseRfcommSetting.NOISE])
        val result = adapter.executeControl(noise)
        assertTrue(result.accepted)
        assertFalse(result.stateChanged)
        assertEquals(5, adapter.runtimeState().features.get<RoseRfcommSettingsFeatureState>()!!.values[RoseRfcommSetting.NOISE])
        assertEquals(1, result.readback.size)
        for (setting in RoseRfcommSetting.entries.filter { it != RoseRfcommSetting.NOISE }) {
            val value = setting.values.first()
            assertFalse(adapter.executeControl(RoseRfcommControlRequest.SetValue(setting, value)).accepted)
            adapter.receive(report(2, setting.type, value))
            assertTrue(adapter.executeControl(RoseRfcommControlRequest.SetValue(setting, value)).accepted)
        }
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get<RoseRfcommSettingsFeatureState>())
        assertFalse(adapter.executeControl(noise).accepted)
    }
    @Test fun familyUltraAndFurinaDoNotInheritAdvancedSettingsFromSharedGrammar() {
        listOf(RoseBudsFeelProtocolFamilyAdapter(), RoseLuliUltraAdapter(), FurinaEndlessAdapter()).forEach {
            it.receive(report(2, 14, 1, 2, 42, 3))
            assertNull(it.runtimeState().features.get<RoseRfcommSettingsFeatureState>())
            assertFalse(it.executeControl(RoseRfcommControlRequest.SetValue(RoseRfcommSetting.GAME, 0)).accepted)
        }
    }
    @Test fun controlTransportRetainsNamespacedProfileValues() {
        val request = RoseRfcommControlRequest.SetValue(RoseRfcommSetting.TRANSPARENCY_DEPTH, 5)
        assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
    }
}
