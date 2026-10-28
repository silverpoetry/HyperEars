package dev.hyperears.protocol.bose
import org.junit.Assert.*
import org.junit.Test
class BoseButtonSettingsCodecTest {
    private fun frame(vararg bytes:Int)=BoseBmapWireCodec.Decoder().offer(bytes.map(Int::toByte).toByteArray()).single()
    @Test fun originalQc35CaptureMaskAndWireWriteAreIndependentGoldens() {
        // bosectl test_device_parsers.py QC35 capture, not built from production constants.
        val report=BoseDailySettingsCodec.parseButton(frame(1,9,3,7,16,4,1,6,0,0,0))!!
        assertEquals(16,report.id);assertEquals(4,report.event);assertEquals(1,report.current)
        assertEquals(listOf(6,0,0,0),report.mask)
        assertArrayEquals(byteArrayOf(1,9,2,3,16,4,2),BoseDailySettingsCodec.setButton(16,4,2))
        assertArrayEquals(byteArrayOf(1,9,1,0),BoseDailySettingsCodec.query(9))
    }
    @Test fun masklessStateIsValidButEchoWrongDomainAndMalformedAreNotEvidence() {
        assertTrue(BoseDailySettingsCodec.parseButton(frame(1,9,3,3,16,4,2))!!.mask.isEmpty())
        assertNull(BoseDailySettingsCodec.parseButton(frame(1,9,2,3,16,4,2)))
        assertNull(BoseDailySettingsCodec.parseButton(frame(2,9,3,3,16,4,2)))
        assertNull(BoseDailySettingsCodec.parseButton(frame(1,9,3,2,16,4)))
        assertNull(BoseDailySettingsCodec.parseButton(frame(1,9,3,3,1,4,2)))
        assertNull(BoseDailySettingsCodec.parseButton(frame(1,9,3,3,16,0,2)))
        assertNull(BoseDailySettingsCodec.parseButton(frame(1,9,3,8,16,4,2,6,0,0,0,99)))
    }
}
