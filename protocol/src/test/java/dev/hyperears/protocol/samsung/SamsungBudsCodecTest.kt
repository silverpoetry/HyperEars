package dev.hyperears.protocol.samsung

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungBudsCodecTest {
    @Test
    fun decodesCapturedBuds2ProExtendedStatus() {
        val raw = hex(
            "FD 31 00 61 0D 04 64 64 01 00 33 58 00 01 BF 22 00 01 46 01 46 01 00 00 " +
                "03 35 00 03 00 10 01 01 01 01 32 03 01 01 01 00 0C AE 01 00 03 00 01 00 " +
                "01 01 B6 37 DD",
        )
        val frame = SamsungBudsCodec.parseFrame(raw)
        assertNotNull(frame)
        val battery = requireNotNull(SamsungBudsCodec.parseBattery(requireNotNull(frame)))
        assertEquals(100, battery.left)
        assertEquals(100, battery.right)
        assertEquals(88, battery.case)
        assertEquals(SamsungBudsCodec.NoiseMode.OFF, SamsungBudsCodec.parseNoiseMode(frame))
    }

    @Test
    fun packetRoundTripsAndRejectsCorruptCrc() {
        val packet = SamsungBudsCodec.noiseModeCommand(SamsungBudsCodec.NoiseMode.AMBIENT)
        val frame = SamsungBudsCodec.parseFrame(packet)
        assertEquals(SamsungBudsCodec.NOISE_CONTROLS, frame?.id)
        assertArrayEquals(byteArrayOf(2), frame?.payload)

        packet[4] = 1
        assertEquals(null, SamsungBudsCodec.parseFrame(packet))
    }

    @Test
    fun streamingDecoderBuffersAndSplitsFrames() {
        val one = SamsungBudsCodec.noiseModeCommand(SamsungBudsCodec.NoiseMode.ANC)
        val two = SamsungBudsCodec.managerInfoCommand()
        val decoder = SamsungBudsCodec.Decoder()
        assertTrue(decoder.offer(one.copyOfRange(0, 3)).isEmpty())
        val frames = decoder.offer(one.copyOfRange(3, one.size) + two)
        assertEquals(2, frames.size)
        assertArrayEquals(one, frames[0])
        assertArrayEquals(two, frames[1])
    }

    @Test
    fun parsesUniversalAcknowledgement() {
        fun ack(vararg payload: Int) = SamsungBudsCodec.Frame(
            SamsungBudsCodec.UNIVERSAL_ACK, payload.map(Int::toByte).toByteArray(),
        )
        assertEquals(SamsungBudsCodec.NoiseMode.ANC, SamsungBudsCodec.parseNoiseMode(ack(0x78, 1)))
        for (invalid in listOf(ack(0x78), ack(0x78, 1, 1), ack(0x78, 3), ack(0x92, 1))) {
            assertEquals(null, SamsungBudsCodec.parseNoiseMode(invalid))
        }
    }

    @Test
    fun sharedServiceFallbackPublishesStableBatteryAndNoiseWithoutModelSettings() {
        val raw = hex(
            "FD 31 00 61 0D 04 64 64 01 00 33 58 00 01 BF 22 00 01 46 01 46 01 00 00 " +
                "03 35 00 03 00 10 01 01 01 01 32 03 01 01 01 00 0C AE 01 00 03 00 01 00 " +
                "01 01 B6 37 DD",
        )
        val frame = SamsungBudsCodec.parseFrame(raw)!!
        assertEquals(100, SamsungBudsCodec.parseBattery(frame, SamsungBudsCodec.Model.UNKNOWN)?.left)
        assertEquals(SamsungBudsCodec.NoiseMode.OFF,
            SamsungBudsCodec.parseNoiseMode(frame, SamsungBudsCodec.Model.UNKNOWN))
    }

    @Test
    fun corruptFrameLengthDoesNotConsumeFollowingValidFrame() {
        val good = SamsungBudsCodec.noiseModeCommand(SamsungBudsCodec.NoiseMode.ANC)
        // Invalid candidate overlaps the next valid frame, including its start marker.
        val corrupt = byteArrayOf(0xFD.toByte(), 8, 0, 0)
        val decoder = SamsungBudsCodec.Decoder()
        val frames = decoder.offer(corrupt + good)
        assertEquals(1, frames.size)
        assertArrayEquals(good, frames.single())
    }

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }.toByteArray()
}
