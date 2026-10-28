package dev.hyperears.protocol.bose

import org.junit.Assert.*
import org.junit.Test

class BoseCallSettingsTest {
    private fun frame(fn: Int, vararg data: Int, op: BoseBmapWireCodec.Operator = BoseBmapWireCodec.Operator.STATUS) =
        BoseBmapWireCodec.Decoder().offer(BoseBmapWireCodec.packet(1, fn, op, data.map(Int::toByte).toByteArray())).single()
    @Test fun callReportsRequireStatusAndExactKnownValues() {
        assertEquals(2, BoseDailySettingsCodec.parseSidetone(frame(11, 0, 2)))
        assertNull(BoseDailySettingsCodec.parseSidetone(frame(11, 1)))
        assertNull(BoseDailySettingsCodec.parseSidetone(frame(11, 1, 4)))
        assertNull(BoseDailySettingsCodec.parseSidetone(frame(11, 1, 2, op = BoseBmapWireCodec.Operator.SET_GET)))
        assertEquals(false, BoseDailySettingsCodec.parseAutoAnswer(frame(27, 0)))
        assertEquals(true, BoseDailySettingsCodec.parseAutoAnswer(frame(27, 1)))
        assertNull(BoseDailySettingsCodec.parseAutoAnswer(frame(27, 2)))
        assertNull(BoseDailySettingsCodec.parseAutoAnswer(frame(27)))
    }
}
