package dev.hyperears.protocol.jieli

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JlRcspWireCodecTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun decode(hex: String) = JlRcspWireCodec.Decoder().offer(bytes(hex)).single()
    private val battery = "fedcba00c10007000804005a5a00ef"
    private val transparency = "fedcba8009000d0eff0a0d024000400040004000ef"

    @Test fun capturedBatteryAndNoiseReports() {
        assertEquals(JlRcspWireCodec.BatteryReport(90, 90), JlRcspWireCodec.parseBattery(decode(battery)))
        assertEquals(JlRcspWireCodec.NoiseState.TRANSPARENCY, JlRcspWireCodec.parseNoiseState(decode(transparency)))
        assertEquals(JlRcspWireCodec.BatteryReport(90, 90),
            JlRcspWireCodec.parseBattery(decode("fedcba80c200130f05d60000000022010203040506005a5a0017ef")))
    }

    @Test fun preservesCapturedModeWrites() {
        assertArrayEquals(bytes("fedcbac008000d20ff0a0d016000600060006000ef"),
            JlRcspWireCodec.setNoiseMode(JlRcspWireCodec.NoiseState.ANC, 0x20))
        assertArrayEquals(bytes("fedcbac008000d1eff0a0d024000400040004000ef"),
            JlRcspWireCodec.setNoiseMode(JlRcspWireCodec.NoiseState.TRANSPARENCY, 0x1E))
        assertArrayEquals(bytes("fedcbac008000d21ff0a0d000000000000000000ef"),
            JlRcspWireCodec.setNoiseMode(JlRcspWireCodec.NoiseState.OFF, 0x21))
        assertArrayEquals(bytes("fedcbac0c300020700ef"), JlRcspWireCodec.queryStatus(7))
        assertArrayEquals(bytes("fedcbac0c0000408020502ef"), JlRcspWireCodec.setGameMode(8, true))
    }

    @Test fun allReadBoundariesAndConcatenatedFrames() {
        val data = bytes(battery)
        for (split in 0 until data.size) {
            val decoder = JlRcspWireCodec.Decoder()
            assertTrue(decoder.offer(data.copyOfRange(0, split)).isEmpty())
            assertEquals(1, decoder.offer(data.copyOfRange(split, data.size)).size)
        }
        assertEquals(2, JlRcspWireCodec.Decoder().offer(data + bytes(transparency)).size)
    }

    @Test fun missingOrInvalidTrailerDoesNotPublishAndNextFrameRecovers() {
        val data = bytes(battery)
        val decoder = JlRcspWireCodec.Decoder()
        assertTrue(decoder.offer(data.copyOf(data.size - 1)).isEmpty())
        assertEquals(1, decoder.offer(byteArrayOf(0) + data).size)
    }

    @Test fun boundedDecoderRecoversFromNoiseOversizeAndUnknownFlags() {
        val decoder = JlRcspWireCodec.Decoder()
        val malformed = bytes("fedcba00c1ffff") + bytes("fedcbaffc10000ef")
        assertEquals(1, decoder.offer(ByteArray(100_000) { 0x55 } + malformed + bytes(battery)).size)
        decoder.offer(bytes(battery).copyOf(6))
        decoder.reset()
        assertEquals(1, decoder.offer(bytes(battery)).size)
    }

    @Test fun rejectsFailedResponseAndMarkersInsideUnrelatedAttributes() {
        assertNull(JlRcspWireCodec.parseBattery(decode(battery.replace("000804", "010804"))))
        assertNull(JlRcspWireCodec.parseBattery(decode("fedcba00c100080008050104005a5aef")))
        assertNull(JlRcspWireCodec.parseNoiseState(decode(transparency.replace("8009", "8003"))))
        assertNull(JlRcspWireCodec.parseNoiseState(decode("fedcba800900050eff020d01ef")))
    }

    @Test fun malformedAndDuplicateLtvAreRejected() {
        assertNull(JlRcspWireCodec.parseWorkMode(decode("fedcba00c1000400080205ef")))
        assertNull(JlRcspWireCodec.parseWorkMode(decode("fedcba00c100080008020501020502ef")))
        assertNull(JlRcspWireCodec.parseWorkMode(decode("fedcba00c10006000802050200ef")))
    }

    @Test fun gameRequiresSuccessfulResponseAndValidWorkMode() {
        assertEquals(true, JlRcspWireCodec.parseWorkMode(decode("fedcba00c100050008020502ef")))
        assertEquals(false, JlRcspWireCodec.parseWorkMode(decode("fedcba00c100050008020501ef")))
        assertNull(JlRcspWireCodec.parseWorkMode(decode("fedcbac0c100050008020502ef")))
        assertNull(JlRcspWireCodec.parseWorkMode(decode("fedcba00c100050108020502ef")))
        assertNull(JlRcspWireCodec.parseWorkMode(decode("fedcba00c100050008020503ef")))
    }

    @Test fun completeUnknownBatteryIsRetainedButInvalidPercentageIsRejected() {
        assertEquals(JlRcspWireCodec.BatteryReport(null, null),
            JlRcspWireCodec.parseBattery(decode("fedcba00c100070008040000ff00ef")))
        assertNull(JlRcspWireCodec.parseBattery(decode("fedcba00c1000700080400655a00ef")))
    }

    @Test fun fullNoiseAttributeQueryResponseAndWriteEchoRemainDistinct() {
        assertEquals(JlRcspWireCodec.NoiseState.ANC,
            JlRcspWireCodec.parseNoiseState(decode("fedcba0007000e0008ff0a0d016000600060006000ef")))
        assertNull(JlRcspWireCodec.parseNoiseState(decode("fedcbac008000d20ff0a0d016000600060006000ef")))
    }

    @Test fun handshakeNeedsSuccessfulStructuredDeviceInfo() {
        assertTrue(JlRcspWireCodec.isDeviceInfo(decode("fedcba000300050005020020ef")))
        assertFalse(JlRcspWireCodec.isDeviceInfo(decode("fedcba000300050105020020ef")))
        assertFalse(JlRcspWireCodec.isDeviceInfo(decode("fedcba0003000400050200ef")))
    }
}
