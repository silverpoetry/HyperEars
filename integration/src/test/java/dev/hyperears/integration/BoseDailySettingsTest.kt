package dev.hyperears.integration

import dev.hyperears.protocol.bose.BoseBmapWireCodec as Bmap
import dev.hyperears.protocol.bose.BoseDailySettingsCodec as Wire
import org.junit.Assert.*
import org.junit.Test

class BoseDailySettingsTest {
    private fun report(function: Int, vararg payload: Int) = Bmap.packet(1, function, Bmap.Operator.STATUS,
        payload.map(Int::toByte).toByteArray())
    private fun identity(id: Int) = Bmap.packet(0, 3, Bmap.Operator.STATUS,
        byteArrayOf((id ushr 8).toByte(), id.toByte(), 0))
    private fun adapter(id: Int): EarbudAdapter = (BoseEarbudAdapter().receive(identity(id)).handshake as HandshakeResult.Replace).adapter

    @Test fun sidetoneAndAnswerAreModelAndReportQualified() {
        for (id in listOf(0x4020, 0x4039, 0x402F, 0x4082)) {
            val device = adapter(id)
            val request = BoseDailyControlRequest.SetSidetone(2)
            assertFalse(device.executeControl(request).accepted)
            device.receive(report(11, 1, 3))
            assertTrue(device.executeControl(request).accepted)
            assertEquals(3, device.runtimeState().features.get<BoseDailySettingsFeatureState>()!!.sidetone)
            val command = Bmap.Decoder().offer(device.protocolSession.encode(request).single()).single()
            assertArrayEquals(byteArrayOf(1, 2), command.payload)
            assertEquals(request, ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
            device.receive(report(27, 0))
            assertEquals(id == 0x4082, device.executeControl(BoseDailyControlRequest.SetAutoAnswer(true)).accepted)
            val answer = BoseDailyControlRequest.SetAutoAnswer(true)
            assertEquals(answer, ControlRequestTransport.decode(ControlRequestTransport.encode(answer)))
            device.protocolSession.reset()
            assertTrue(device.protocolSession.encode(request).isEmpty())
        }
        for (id in listOf(0x400C, 0x4062, 0x4075)) {
            val device = adapter(id); device.receive(report(11, 1, 2) + report(27, 1))
            assertFalse(device.executeControl(BoseDailyControlRequest.SetSidetone(1)).accepted)
            assertFalse(device.executeControl(BoseDailyControlRequest.SetAutoAnswer(false)).accepted)
        }
    }
    @Test fun coalescedIdentityAndEqRetainTheSameReportAsSeparateReceives() {
        val eq = report(7, -10, 10, 3, 0)
        val separate = adapter(0x4039).also { it.receive(eq) }
        val family = BoseEarbudAdapter()
        val result = family.receive(identity(0x4039) + eq)
        val combined = (result.handshake as HandshakeResult.Replace).adapter
        assertEquals(separate.runtimeState().features.get<BoseDailySettingsFeatureState>(),
            combined.runtimeState().features.get<BoseDailySettingsFeatureState>())
        assertTrue(combined.executeControl(BoseDailyControlRequest.SetEqBand(
            BoseEqBandDefinition(0, -10, 10), 4)).accepted)
    }

    @Test fun disabledIdentityDoesNotAdmitCoalescedDailyReports() {
        val family = BoseEarbudAdapter()
        family.configureDisabledAdapterIds(setOf(BoseBmapModelRegistry.find(0x4039)!!.modelId))
        family.receive(identity(0x4039) + report(7, -10, 10, 3, 0))
        assertNull(family.runtimeState().features.get<BoseDailySettingsFeatureState>())
        assertTrue(family.protocolSession.encode(BoseDailyControlRequest.SetEqBand(
            BoseEqBandDefinition(0, -10, 10), 4)).isEmpty())
    }

    @Test fun onlyIdentityQualifiedModelsQueryAndReadConfirmTheirOptionalControls() {
        val family = BoseEarbudAdapter()
        family.receive(report(7, -10, 10, 0, 0))
        assertNull(family.runtimeState().features.get<BoseDailySettingsFeatureState>())
        val response = family.receive(identity(0x4075))
        val prince = (response.handshake as HandshakeResult.Replace).adapter
        assertTrue(response.commands.any { it.contentEquals(Wire.query(7)) })
        assertFalse(prince.executeControl(BoseDailyControlRequest.SetVoicePrompts(true)).accepted)
        prince.receive(report(3, 0xC8))
        val result = prince.executeControl(BoseDailyControlRequest.SetVoicePrompts(true))
        assertTrue(result.accepted); assertFalse(result.stateChanged)
        assertArrayEquals(byteArrayOf(1, 3, 2, 1, 0xE8.toByte()), result.commands.single())
        assertEquals(0xC8, prince.runtimeState().features.get<BoseDailySettingsFeatureState>()!!.promptsRaw)
        assertArrayEquals(Wire.query(3), result.readback.single())
        prince.resetProtocolSession()
        assertFalse(prince.executeControl(BoseDailyControlRequest.SetVoicePrompts(true)).accepted)
        assertNull(prince.runtimeState().features.get<BoseDailySettingsFeatureState>())
    }
    @Test fun eqBoundsAndBandDefinitionMustRemainCurrentAndEmptySnapshotRevokesWrites() {
        val device = adapter(0x4039)
        device.receive(report(7, -10, 10, 0, 0, -10, 10, 1, 1))
        val request = BoseDailyControlRequest.SetEqBand(BoseEqBandDefinition(0, -10, 10), 4)
        assertTrue(device.executeControl(request).accepted)
        assertFalse(device.executeControl(request.copy(value = 11)).accepted)
        device.receive(report(7, -5, 5, 0, 0))
        assertFalse(device.executeControl(request).accepted)
        assertTrue(device.protocolSession.encode(request).isEmpty())
        assertFalse(device.executeControl(BoseDailyControlRequest.SetEqBand(BoseEqBandDefinition(1, -10, 10), 0)).accepted)
        device.receive(report(7))
        assertFalse(device.runtimeState().features.get<BoseDailySettingsFeatureState>()!!.hasSettings)
        assertFalse(device.executeControl(request.copy(expected = BoseEqBandDefinition(0, -5, 5))).accepted)
    }
    @Test fun profilesDoNotTurnSharedSettingsGrammarIntoUniversalCapabilities() {
        val qc35 = adapter(0x4020)
        qc35.receive(report(7, -10, 10, 0, 0)); qc35.receive(report(10, 7)); qc35.receive(report(24, 1))
        assertNull(qc35.runtimeState().features.get<BoseDailySettingsFeatureState>())
        val qc45 = adapter(0x4039)
        qc45.receive(report(24, 1)); qc45.receive(report(5, 11, 5, 1))
        assertNull(qc45.runtimeState().features.get<BoseDailySettingsFeatureState>())
        val open = adapter(0x4068)
        open.receive(report(10, 7))
        assertTrue(open.executeControl(BoseDailyControlRequest.SetMultipoint(false)).accepted)
        assertFalse(open.snapshot().capabilities.noiseControl)
        assertEquals(BoseDailyPresentationIds.SETTINGS_ONLY, open.miLinkCardPresentationId)
    }
    @Test fun cncEnableRepeatsOnlyForNc700AndAnrLowStaysDistinct() {
        val nc700 = adapter(0x4024)
        nc700.receive(report(5, 11, 0, 0))
        val request = BoseDailyControlRequest.SetCncLevel(7)
        val result = nc700.executeControl(request)
        assertEquals(2, result.commands.size)
        assertArrayEquals(Bmap.setCnc(7, true), result.commands.first())
        assertFalse(result.stateChanged)
        assertEquals(false, nc700.runtimeState().features.get<BoseDailySettingsFeatureState>()!!.cnc!!.enabled)
        val buds = adapter(0x402F); buds.receive(report(5, 11, 0, 0))
        assertEquals(1, buds.executeControl(request).commands.size)
        val qc35 = adapter(0x4020); qc35.receive(report(6, 3, 11))
        assertEquals(3, qc35.runtimeState().features.get<BoseDailySettingsFeatureState>()!!.anr)
        assertEquals(NoiseMode.ANC, qc35.runtimeState().noiseMode)
        assertArrayEquals(Bmap.setAnr(1), qc35.executeControl(BoseDailyControlRequest.SetAnr(1)).commands.single())
    }
    @Test fun errorsAndWrongIdentityDoNotGrantOrRetainSettingsWrites() {
        val device = adapter(0x4082)
        device.receive(Bmap.packet(1, 24, Bmap.Operator.ERROR, byteArrayOf(1)))
        assertFalse(device.executeControl(BoseDailyControlRequest.SetAutoPause(false)).accepted)
        device.receive(report(24, 1)); assertTrue(device.executeControl(BoseDailyControlRequest.SetAutoPause(false)).accepted)
        device.receive(identity(0x4020))
        assertFalse(device.executeControl(BoseDailyControlRequest.SetAutoPause(false)).accepted)
        assertTrue(device.protocolSession.encode(BoseDailyControlRequest.SetAutoPause(false)).isEmpty())
    }
    @Test fun typedRequestsRoundTrip() {
        listOf(BoseDailyControlRequest.SetEqBand(BoseEqBandDefinition(2, -10, 10), -5),
            BoseDailyControlRequest.SetVoicePrompts(false), BoseDailyControlRequest.SetMultipoint(true),
            BoseDailyControlRequest.SetAutoPause(true), BoseDailyControlRequest.SetCncLevel(4),
            BoseDailyControlRequest.SetAnr(3)).forEach {
            assertEquals(it, ControlRequestTransport.decode(ControlRequestTransport.encode(it)))
        }
    }
}
