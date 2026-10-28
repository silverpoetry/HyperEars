package dev.hyperears.integration

import dev.hyperears.protocol.rose.RoseZtWireCodec as Wire
import org.junit.Assert.*
import org.junit.Test

class RoseZtAdapterTest {
    private fun report(group: Int, command: Int, vararg payload: Int): ByteArray {
        val body = listOf(9, 255, 0, 0, 1, group, command, 10 + payload.size, 0) + payload.toList()
        return (body + (body.sum() and 255)).map(Int::toByte).toByteArray()
    }
    @Test fun distinctProfilesGateReportsAndKeepIndependentAudioMultipoint() {
        for (model in Wire.Model.entries) {
            val adapter = RoseZtAdapter(model)
            assertNull(adapter.snapshot().presentationId)
            val request = RoseZtControlRequest.SetValue(model.name, "GAME", 0)
            assertFalse(adapter.supportsControl(request))
            assertTrue(adapter.protocolSession.encode(request).isEmpty())
            adapter.receive(report(6, 3, 1))
            assertTrue(adapter.supportsControl(request))
            val written = adapter.executeControl(request)
            assertArrayEquals(Wire.command(6, 0x83, listOf(0)), written.commands.single())
            assertArrayEquals(Wire.command(6, 3), written.readback.single())
            adapter.receive(written.commands.single())
            assertEquals(1, adapter.runtimeState().features.get<RoseZtSettingsFeatureState>()!!.values["GAME"])
            adapter.receive(report(8, 1, 1))
            assertFalse(adapter.supportsControl(RoseZtControlRequest.SetValue(model.name, "MULTIPOINT", 1)))
            adapter.receive(report(8, 2, 0))
            assertEquals(model == Wire.Model.HEADFEEL, adapter.supportsControl(RoseZtControlRequest.SetValue(model.name, "MULTIPOINT", 1)))
            assertFalse(adapter.supportsControl(request.copy(expectedModel = "other")))
            assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
            adapter.resetProtocolSession()
            assertFalse(adapter.supportsControl(request)); assertTrue(adapter.protocolSession.encode(request).isEmpty())
        }
    }
    @Test fun headBatteryIsAggregateAndI3DoesNotAcceptWindOrGain() {
        val head = RoseZtAdapter(Wire.Model.HEADFEEL)
        head.receive(report(1, 3, 65))
        assertEquals(65, head.runtimeState().battery.overall.percent)
        assertNull(head.runtimeState().battery.left.percent); assertNull(head.runtimeState().battery.case.percent)
        assertEquals(HeadsetFormFactor.HEADPHONES, head.snapshot().formFactor)
        val i3 = RoseZtAdapter(Wire.Model.I3)
        i3.receive(report(1, 1, 0, 0, 60, 70, 0, 1, 80))
        assertEquals(60, i3.runtimeState().battery.left.percent)
        assertEquals(80, i3.runtimeState().battery.case.percent)
        i3.receive(report(6, 2, 0, 0, 1, 0))
        assertFalse(i3.snapshot().capabilities.noiseControl)
        i3.receive(report(6, 2, 1, 0, 0, 0))
        assertEquals(setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY), i3.snapshot().supportedNoiseModes)
        assertFalse(i3.executeControl(StandardControlRequest.SetNoiseMode(NoiseMode.WIND)).accepted)
        i3.receive(report(2, 3, 2))
        assertFalse(i3.supportsControl(RoseZtControlRequest.SetValue("I3", "GAIN", 1)))
    }
    @Test fun ambiguousOrUnqualifiedAdvertisementCannotFallBackToAnExactName() {
        val conflicting = EarbudIdentity("EarFree-i3", true, manufacturerData = mapOf(
            0x02F1 to listOf(3), 0x5352 to listOf(0, 2, 0, 2)))
        assertFalse(RoseZtAdapter(Wire.Model.I3).matches(conflicting))
        assertFalse(RoseZtAdapter(Wire.Model.HEADFEEL).matches(conflicting))
        assertFalse(EarbudAdapterRegistry.resolve(conflicting) is RoseZtAdapter)
        val crossFamily = EarbudIdentity("EarFree-i3", true, manufacturerData = mapOf(
            0x02F1 to listOf(3), 0x8418 to listOf(1, 7)))
        assertFalse(RoseZtAdapter(Wire.Model.I3).matches(crossFamily))
        assertFalse(EarbudAdapterRegistry.resolve(crossFamily) is RoseZtAdapter)
        val unknown = EarbudIdentity("HeadFeel", true, manufacturerData = mapOf(0x5352 to listOf(0, 2, 0)))
        assertFalse(RoseZtAdapter(Wire.Model.HEADFEEL).matches(unknown))
        assertFalse(EarbudAdapterRegistry.resolve(unknown) is RoseZtAdapter)
        assertTrue(RoseZtAdapter(Wire.Model.I3).matches(EarbudIdentity("EarFree-i3", true)))
    }
    @Test fun registryNamesAndEndpointsDoNotBorrowI5() {
        assertEquals("rose-zt-i3", EarbudAdapterRegistry.resolve(EarbudIdentity("EarFree-i3", true))?.id)
        assertEquals("rose-zt-headfeel", EarbudAdapterRegistry.resolve(EarbudIdentity("HeadFeel", true))?.id)
        val candidates = RoseZtAdapter(Wire.Model.I3).transports.map { it as GattTransportSpec }
        assertEquals(listOf(GattWriteMode.WITHOUT_RESPONSE, GattWriteMode.WITH_RESPONSE), candidates.map { it.writeMode })
        assertEquals(2, candidates.map { it.id }.distinct().size)
        assertEquals(1, candidates.map { Triple(it.serviceUuid, it.writeCharacteristicUuid, it.notifyCharacteristicUuid) }.distinct().size)
        assertTrue(RoseZtAdapter(Wire.Model.HEADFEEL).transports.single() is RfcommEndpointSpec)
        assertFalse(RoseZtAdapter(Wire.Model.I3).matches(EarbudIdentity("EarFeel-i5", true)))
    }
}
