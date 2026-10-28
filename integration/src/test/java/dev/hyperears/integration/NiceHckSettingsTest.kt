package dev.hyperears.integration

import dev.hyperears.protocol.nicehck.NiceHckSettingsCodec
import dev.hyperears.protocol.nicehck.NiceHckWireCodec
import org.junit.Assert.*
import org.junit.Test

class NiceHckSettingsTest {
    @Test fun controlsAreIndependentConfirmedAndFirmwareQualified() {
        val adapter = NiceHckYuanDaoOrigAdapter()
        fun report(op: Int, vararg bytes: Int) = adapter.receive(NiceHckWireCodec.command(op, bytes.map(Int::toByte).toByteArray()))
        val game = NiceHckControlRequest.SetValue(NiceHckSetting.GAME, 1)
        assertFalse(adapter.supportsControl(game))
        report(0x0108, 0)
        assertTrue(adapter.supportsControl(game))
        assertFalse(adapter.supportsControl(NiceHckControlRequest.SetValue(NiceHckSetting.LOW_LATENCY, 1)))
        val decoded = ControlRequestTransport.decode(ControlRequestTransport.encode(game))
        assertEquals(game, decoded)
        val sent = adapter.executeControl(game)
        assertTrue(sent.accepted)
        assertArrayEquals(NiceHckWireCodec.command(0x0208, byteArrayOf(1)), sent.commands.single())
        assertArrayEquals(NiceHckWireCodec.command(0x0108), sent.readback.single())
        assertEquals(0, adapter.runtimeState().features.get<NiceHckSettingsFeatureState>()!!.values[NiceHckSetting.GAME])
        report(0x0208, 1) // Write echo is not device state.
        report(0x0108, 2)
        report(0x0108, 1, 0)
        assertEquals(0, adapter.runtimeState().features.get<NiceHckSettingsFeatureState>()!!.values[NiceHckSetting.GAME])
        report(0x0107, 1)
        val vocal = NiceHckControlRequest.SetValue(NiceHckSetting.EQ, 6)
        assertFalse(adapter.supportsControl(vocal))
        report(3, 7, 4)
        assertFalse(adapter.supportsControl(vocal))
        report(3, 8, 4)
        assertTrue(adapter.supportsControl(vocal))
        assertArrayEquals(NiceHckWireCodec.command(0x0207, byteArrayOf(6)), adapter.executeControl(vocal).commands.single())
        adapter.resetProtocolSession()
        assertFalse(adapter.supportsControl(game))
        assertNull(adapter.runtimeState().features.get<NiceHckSettingsFeatureState>())
    }

    @Test fun sixNoiseModesAndIndependentWindToggleDoNotAlias() {
        val adapter = NiceHckYuanDaoOrigAdapter()
        adapter.receive(NiceHckWireCodec.command(0x0101, byteArrayOf(2, 127)))
        assertFalse(adapter.snapshot().capabilities.noiseControl)
        assertNull(adapter.runtimeState().features.get<NiceHckSettingsFeatureState>())
        adapter.receive(NiceHckWireCodec.command(0x0101, byteArrayOf(3)))
        val state = adapter.runtimeState().features.get<NiceHckSettingsFeatureState>()!!
        assertEquals(3, state.values[NiceHckSetting.NOISE])
        assertEquals(NoiseMode.ANC, adapter.runtimeState().noiseMode)
        assertFalse(adapter.supportsControl(NiceHckControlRequest.SetValue(NiceHckSetting.WIND_SUPPRESSION, 1)))
        val request = NiceHckControlRequest.SetValue(NiceHckSetting.NOISE, 16)
        assertArrayEquals(NiceHckWireCodec.command(0x0201, byteArrayOf(16, 0)), adapter.executeControl(request).commands.single())
        assertEquals(NiceHckYuanDaoOrigAdapter.ADVANCED_PRESENTATION_ID, adapter.snapshot().presentationId)
    }
    @Test fun initialProbeIncludesIndependentSettingsAndFirmwareReads() {
        val commands = NiceHckYuanDaoOrigAdapter().beginHandshake().commands
        val expected = listOf(NiceHckSettingsCodec.queryFirmware, NiceHckSettingsCodec.queryEqualizer) +
            NiceHckSettingsCodec.Toggle.entries.map(NiceHckSettingsCodec::query)
        expected.forEach { query -> assertTrue("Missing bounded settings read", commands.any { it.contentEquals(query) }) }
    }
}
