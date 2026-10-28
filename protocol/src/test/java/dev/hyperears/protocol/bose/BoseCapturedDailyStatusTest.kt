package dev.hyperears.protocol.bose

import org.junit.Assert.*
import org.junit.Test

class BoseCapturedDailyStatusTest {
    // bosectl70570b29 captures/20260404-173135_noise-control-off-and-on.json,
    // product408201: register payloads are literal captured bytes. Four-byte BMAP
    // headers below are reconstructed from the existing grammar, not a full packet capture.
    private val sidetone=byteArrayOf(1,11,3,3,1,2,15)
    private val prompts=byteArrayOf(1,3,3,7,0x41,0,0,0x81.toByte(),2,0,0)
    private fun decode(bytes:ByteArray)=BoseBmapWireCodec.Decoder().offer(bytes).single()
    @Test fun capturedThreeByteSidetoneAndSevenBytePromptsUseOnlyEstablishedFields() {
        val decoder=BoseBmapWireCodec.Decoder()
        assertTrue(decoder.offer(sidetone.copyOfRange(0,5)).isEmpty())
        val frames=decoder.offer(sidetone.copyOfRange(5,sidetone.size)+prompts)
        assertEquals(2,frames.size)
        assertEquals(2,BoseDailySettingsCodec.parseSidetone(frames[0]))
        assertEquals(0x41,BoseDailySettingsCodec.parsePrompts(frames[1]))
        assertArrayEquals(byteArrayOf(1,11,2,2,1,3),BoseDailySettingsCodec.setSidetone(3))
        // SET_GET still contains only the known first byte; language and opaque high bits survive.
        assertArrayEquals(byteArrayOf(1,3,2,1,0x61),BoseDailySettingsCodec.setPrompts(0x41,true))
        assertEquals(2,BoseDailySettingsCodec.parseSidetone(decode(sidetone).copy(payload=byteArrayOf(1,2,0xA5.toByte()))))
    }
    @Test fun wrongOperatorsLengthsAndValuesNeverBecomeEvidence() {
        for(op in BoseBmapWireCodec.Operator.entries.filter{it!=BoseBmapWireCodec.Operator.STATUS}) {
            assertNull(BoseDailySettingsCodec.parseSidetone(decode(sidetone).copy(flags=op.value)))
            assertNull(BoseDailySettingsCodec.parsePrompts(decode(prompts).copy(flags=op.value)))
        }
        for(size in listOf(0,1,4,7))assertNull(BoseDailySettingsCodec.parseSidetone(decode(sidetone).copy(payload=ByteArray(size))))
        for(size in listOf(0,2,3,4,5,6,8))assertNull(BoseDailySettingsCodec.parsePrompts(decode(prompts).copy(payload=ByteArray(size))))
        for(value in listOf(4,128,255))assertNull(BoseDailySettingsCodec.parseSidetone(decode(sidetone).copy(payload=byteArrayOf(1,value.toByte(),15))))
        assertNull(BoseDailySettingsCodec.parseSidetone(decode(sidetone).copy(functionBlock=2)))
        assertNull(BoseDailySettingsCodec.parsePrompts(decode(prompts).copy(functionBlock=2)))
        assertEquals(3,BoseDailySettingsCodec.parseSidetone(decode(sidetone).copy(payload=byteArrayOf(1,3))))
        assertEquals(0xE8,BoseDailySettingsCodec.parsePrompts(decode(prompts).copy(payload=byteArrayOf(0xE8.toByte()))))
    }
}
