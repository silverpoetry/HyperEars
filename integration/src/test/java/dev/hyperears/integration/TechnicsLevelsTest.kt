package dev.hyperears.integration

import dev.hyperears.protocol.technics.TechnicsRaceWireCodec as Wire
import org.junit.Assert.*
import org.junit.Test

class TechnicsLevelsTest {
    private fun report(mode: Int, anc: Int, ambient: Int) = Wire.encode(0x000A,
        byteArrayOf(0, mode.toByte(), anc.toByte(), ambient.toByte()), Wire.MessageType.RESPONSE)

    @Test fun levelsRequireFreshModeAndPreserveOtherReportedLevel() {
        val adapter = TechnicsEarbudAdapter()
        val anc = TechnicsControlRequest.SetLevel(false, 37, NoiseMode.ANC)
        assertFalse(adapter.supportsControl(anc))
        adapter.receive(report(1, 80, 23))
        assertEquals(anc, ControlRequestTransport.decode(ControlRequestTransport.encode(anc)))
        val write = adapter.executeControl(anc)
        assertTrue(write.accepted)
        assertArrayEquals(byteArrayOf(5, 0x5A, 5, 0, 11, 0, 1, 37, 23), write.commands.single())
        assertArrayEquals(Wire.queryOutsideControl, write.readback.single())
        assertEquals(80, adapter.runtimeState().features.get<TechnicsLevelsFeatureState>()!!.anc)
        adapter.receive(report(2, 61, 42))
        assertFalse(adapter.executeControl(anc).accepted)
        val ambient = TechnicsControlRequest.SetLevel(true, 99, NoiseMode.TRANSPARENCY)
        assertArrayEquals(byteArrayOf(5, 0x5A, 5, 0, 11, 0, 2, 61, 99), adapter.executeControl(ambient).commands.single())
        adapter.receive(report(0, 61, 42))
        assertFalse(adapter.supportsControl(ambient))
        adapter.resetProtocolSession()
        assertNull(adapter.runtimeState().features.get<TechnicsLevelsFeatureState>())
        assertNull(adapter.miLinkCardPresentationId)
    }

    @Test fun malformedOrOutOfRangeReportsDoNotGrantOrOverwriteSettings() {
        val adapter = TechnicsEarbudAdapter()
        adapter.receive(report(1, 101, 20))
        assertNull(adapter.runtimeState().features.get<TechnicsLevelsFeatureState>())
        adapter.receive(report(1, 10, 20))
        adapter.receive(report(1, 9, 255))
        assertEquals(TechnicsLevelsFeatureState(NoiseMode.ANC, 10, 20), adapter.runtimeState().features.get<TechnicsLevelsFeatureState>())
        assertFalse(adapter.supportsControl(TechnicsControlRequest.SetLevel(false, 101, NoiseMode.ANC)))
    }
}
