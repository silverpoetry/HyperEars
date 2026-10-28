package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseLegacyIdentityCodec.Model
import org.junit.Assert.*
import org.junit.Test

class RoseRemainingF8Test {
    private fun report(field: Int, value: Int) = if (field == 4) byteArrayOf(0, 0x27, 2, 0, 4, 4, 2, 0, value.toByte())
        else byteArrayOf(0, 0x28, 2, 0, 3, field.toByte(), 1, value.toByte())
    @Test fun noNoiseModelsProbeOnlyTheirOwnDeclaredFields() {
        for (model in listOf(Model.AGATE, Model.AGATE_X, Model.CERAMICS)) {
            val adapter = RoseLegacyAdapter(model)
            val fields = adapter.beginHandshake().commands.map { it.last().toInt() }
            assertEquals(if (model == Model.AGATE_X) setOf(4) else setOf(4, 8), fields.toSet())
            adapter.receive(report(12, 1)); adapter.receive(report(11, 1)); adapter.receive(report(22, 1))
            assertNull(adapter.snapshot().presentationId)
            assertFalse(adapter.snapshot().capabilities.noiseControl)
            adapter.receive(report(4, 0))
            val request = RoseLegacyControlRequest.SetValue(model.name, RoseLegacySetting.EQ, 1)
            assertTrue(adapter.executeControl(request).accepted)
            assertFalse(adapter.supportsControl(request.copy(value = 2)))
            adapter.receive(report(4, 2))
            assertEquals(1, adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>()!!.values[RoseLegacySetting.EQ])
            adapter.receive(report(4, 3))
            assertEquals(1, adapter.runtimeState().features.get<RoseLegacySettingsFeatureState>()!!.values[RoseLegacySetting.EQ])
            assertFalse(adapter.supportsControl(request.copy(value = 3)))
            adapter.receive(report(8, 0))
            assertEquals(model != Model.AGATE_X, adapter.supportsControl(request.copy(setting = RoseLegacySetting.GAME)))
            assertFalse(adapter.supportsControl(request.copy(expectedModel = "EXPLORER")))
            adapter.resetProtocolSession(); assertFalse(adapter.supportsControl(request))
            val gatt = adapter.transports.first() as GattTransportSpec
            assertEquals(RoseLuliXAdapter.SERVICE_UUID, gatt.serviceUuid)
            assertEquals(RoseLuliXAdapter.WRITE_CHARACTERISTIC_UUID, gatt.writeCharacteristicUuid)
            assertEquals(GattWriteMode.WITHOUT_RESPONSE, gatt.writeMode)
        }
    }
    @Test fun sameDisplayNameDoesNotSelect7034WithoutItsActualPrefix() {
        val adapter = RoseLegacyAdapter(Model.CERAMICS_X_7034)
        assertFalse(adapter.matches(EarbudIdentity("CERAMICS-X", true)))
        assertFalse(adapter.matches(EarbudIdentity("ROSE Ceramics X", true,
            manufacturerData = mapOf(0x8418 to listOf(1, 9)))))
        assertTrue(adapter.matches(EarbudIdentity("CERAMICS-X", true,
            manufacturerData = mapOf(0x8418 to listOf(1, 0x12)))))
        assertFalse(adapter.matches(EarbudIdentity("CERAMICS-X", true,
            manufacturerData = mapOf(0x8418 to listOf(1, 0x12), 0x02f1 to listOf(3)))))
    }
    @Test fun variant7034KeepsItsOwnTransportAndFourModeDepthSemantics() {
        val adapter = RoseLegacyAdapter(Model.CERAMICS_X_7034)
        assertTrue(adapter.transports.single() is RfcommEndpointSpec.ServiceUuid)
        val request = RoseLegacyControlRequest.SetValue(Model.CERAMICS_X_7034.name, RoseLegacySetting.ANC_LEVEL, 2)
        adapter.receive(report(17, 0))
        assertFalse(adapter.supportsControl(request))
        adapter.receive(report(12, 1)); assertTrue(adapter.supportsControl(request))
        assertArrayEquals(byteArrayOf(0, 0x30, 1, 0, 1, 2), adapter.executeControl(request).commands.single())
        adapter.receive(report(12, 3))
        assertEquals(NoiseMode.OFF, adapter.runtimeState().noiseMode)
        assertFalse(adapter.supportsControl(request)); assertTrue(adapter.protocolSession.encode(request).isEmpty())
        adapter.receive(report(12, 0)); assertEquals(NoiseMode.WIND, adapter.runtimeState().noiseMode)
        assertEquals(NoiseMode.entries.toSet(), adapter.snapshot().supportedNoiseModes)
        assertFalse(adapter.supportsControl(request.copy(setting = RoseLegacySetting.PROMPT)))
        assertFalse(adapter.supportsControl(request.copy(setting = RoseLegacySetting.WEAR)))
    }
}
