package dev.hyperears.protocol.sony
import org.junit.Assert.*
import org.junit.Test
class SonyInformationCodecTest {
    private fun b(vararg v: Int) = v.map(Int::toByte).toByteArray()
    @Test fun strictTextLengthUtf8AndControlCharacters() {
        val valid = b(5, 2, 5) + "1.2.3".encodeToByteArray()
        assertEquals(SonyInformationCodec.Report.Text(2, "1.2.3"), SonyInformationCodec.parse(false, valid))
        for (i in valid.indices) assertNull(SonyInformationCodec.parse(false, valid.copyOf(i)))
        assertNull(SonyInformationCodec.parse(false, valid + b(0)))
        assertNull(SonyInformationCodec.parse(false, b(5, 2, 1, 255)))
        assertNull(SonyInformationCodec.parse(false, b(5, 2, 1, 10)))
        assertEquals(SonyInformationCodec.Report.Text(9, "2.0"), SonyInformationCodec.parse(true, b(0x13, 9, 4, 50, 46, 48, 0)))
    }
    @Test fun codecDialectsDoNotShareCommandOrAcceptMalformedFrames() {
        assertEquals(SonyInformationCodec.Report.Codec(0x10), SonyInformationCodec.parse(false, b(0x19, 0, 0x10)))
        assertEquals(SonyInformationCodec.Report.Codec(0x30), SonyInformationCodec.parse(true, b(0x15, 2, 0x30)))
        assertEquals(SonyInformationCodec.Report.Codec(0), SonyInformationCodec.parse(false, b(0x19, 0, 0x30)))
        assertNull(SonyInformationCodec.parse(true, b(0x19, 0, 0x10)))
        assertNull(SonyInformationCodec.parse(true, b(0x13, 2, 3)))
        assertNull(SonyInformationCodec.parse(true, b(0x13, 2, 2, 0)))
    }
    @Test fun upscalingActiveStatusIsSeparateFromEnabledPreference() {
        assertEquals(SonyInformationCodec.Report.Upscaling(2, 2), SonyInformationCodec.parse(true, b(0x13, 3, 2, 2)))
        assertEquals(SonyInformationCodec.Report.Upscaling(1, 1), SonyInformationCodec.parse(false, b(0x17, 0, 1, 1)))
        assertNull(SonyInformationCodec.parse(true, b(0x13, 3, 4, 1)))
        assertNull(SonyInformationCodec.parse(true, b(0x13, 3, 1, 3)))
    }
}
