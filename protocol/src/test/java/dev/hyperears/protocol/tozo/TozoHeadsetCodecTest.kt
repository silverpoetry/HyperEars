package dev.hyperears.protocol.tozo
import org.junit.Assert.*
import org.junit.Test
class TozoHeadsetCodecTest {
    private fun hex(value:String)=value.chunked(2).map {it.toInt(16).toByte()}.toByteArray()
    @Test fun capturedReadbacksHaveDifferentMeaningFromWriteAcknowledgements() {
        assertEquals(TozoHeadsetCodec.Report.Battery(100,100),TozoHeadsetCodec.parse(hex("0002026464c8")))
        assertEquals(TozoHeadsetCodec.Report.Firmware("0.0.71","0.0.71"),TozoHeadsetCodec.parse(hex("0001060000470000478e")))
        assertEquals(TozoHeadsetCodec.Report.Noise(TozoHeadsetCodec.Noise.TRANSPARENCY),TozoHeadsetCodec.parse(hex("0030010202")))
        assertEquals(TozoHeadsetCodec.Report.Acknowledgement(0x1004,0),TozoHeadsetCodec.parse(hex("1004010000")))
    }
    @Test fun modeWritesUseTheCorrectSeparateCommands() {
        assertArrayEquals(hex("1004010000"),TozoHeadsetCodec.setNoise(TozoHeadsetCodec.Noise.OFF))
        assertArrayEquals(hex("1004010101"),TozoHeadsetCodec.setNoise(TozoHeadsetCodec.Noise.ANC))
        assertArrayEquals(hex("1005010101"),TozoHeadsetCodec.setNoise(TozoHeadsetCodec.Noise.TRANSPARENCY))
        assertArrayEquals(hex("1007010101"),TozoHeadsetCodec.setNoise(TozoHeadsetCodec.Noise.WIND))
        assertArrayEquals(hex("1011010101"),TozoHeadsetCodec.setNoise(TozoHeadsetCodec.Noise.ADAPTIVE))
    }
    @Test fun invalidPayloadsDoNotOpenControlsOrInventBattery() {
        assertNull(TozoHeadsetCodec.parse(TozoWireCodec.encode(0x30,byteArrayOf())))
        assertNull(TozoHeadsetCodec.parse(TozoWireCodec.encode(0x30,byteArrayOf(7))))
        assertNull(TozoHeadsetCodec.parse(TozoWireCodec.encode(2,byteArrayOf(101,50))))
        assertNull(TozoHeadsetCodec.parse(TozoWireCodec.encode(2,byteArrayOf(255.toByte(),50))))
        assertNull(TozoHeadsetCodec.parse(TozoWireCodec.encode(1,byteArrayOf(1,2))))
        assertNull(TozoHeadsetCodec.parse(TozoWireCodec.encode(0x1004,byteArrayOf(0,0))))
    }
}
