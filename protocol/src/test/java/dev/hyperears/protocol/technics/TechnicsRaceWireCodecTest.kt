package dev.hyperears.protocol.technics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicsRaceWireCodecTest {
    @Test
    fun batteryAndNoiseQueriesMatchTechnicsPackets() {
        assertHex("05 5A 03 00 D6 0C 00", TechnicsRaceWireCodec.queryAgentBattery)
        assertHex("05 5A 03 00 D6 0C 01", TechnicsRaceWireCodec.queryClientBattery)
        assertHex("05 5A 02 00 40 00", TechnicsRaceWireCodec.queryCaseBattery)
        assertHex("05 5A 02 00 0A 00", TechnicsRaceWireCodec.queryOutsideControl)
    }

    @Test
    fun noiseModeSequencesMatchStableTechnicsCommandOrder() {
        assertSequence(
            TechnicsRaceWireCodec.NoiseMode.OFF,
            "05 5A 03 00 68 00 00",
            "05 5A 05 00 0B 00 00 64 32",
        )
        assertSequence(
            TechnicsRaceWireCodec.NoiseMode.ANC,
            "05 5A 03 00 68 00 00",
            "05 5A 05 00 0B 00 01 64 32",
            "05 5A 04 00 39 00 20 02",
        )
        assertSequence(
            TechnicsRaceWireCodec.NoiseMode.TRANSPARENCY,
            "05 5A 03 00 68 00 00",
            "05 5A 04 00 22 00 00 00",
            "05 5A 05 00 0B 00 02 64 32",
        )
    }

    @Test
    fun parsesAgentClientAndCaseBatteryResponses() {
        val frames = decode(
            "05 5D 05 00 D6 0C 00 00 57 " +
                "05 5D 05 00 D6 0C 00 01 4C " +
                "05 5B 04 00 40 00 00 40",
        )

        assertEquals(
            TechnicsRaceWireCodec.BatteryReading(
                TechnicsRaceWireCodec.BatteryComponent.RIGHT,
                87,
            ),
            TechnicsRaceWireCodec.parseBattery(frames[0]),
        )
        assertEquals(
            TechnicsRaceWireCodec.BatteryReading(
                TechnicsRaceWireCodec.BatteryComponent.LEFT,
                76,
            ),
            TechnicsRaceWireCodec.parseBattery(frames[1]),
        )
        assertEquals(
            TechnicsRaceWireCodec.BatteryReading(
                TechnicsRaceWireCodec.BatteryComponent.CASE,
                64,
            ),
            TechnicsRaceWireCodec.parseBattery(frames[2]),
        )
    }

    @Test
    fun rejectsFailedUnknownAndOutOfRangeBatteryResponses() {
        val frames = listOf(
            "05 5B 05 00 D6 0C 01 00 64",
            "05 5B 05 00 D6 0C 00 02 64",
            "05 5B 05 00 D6 0C 00 00 65",
            "05 5B 04 00 40 00 00 FF",
            "05 5B 03 00 D6 0C 00",
            "05 5A 05 00 D6 0C 00 00 64",
        ).map { decode(it).single() }

        frames.forEach { assertNull(TechnicsRaceWireCodec.parseBattery(it)) }
    }

    @Test
    fun parsesOnlySuccessfulReadOnlyNoiseStateResponses() {
        val outside = decode("05 5B 06 00 0A 00 00 01 64 32").single()
        assertEquals(
            TechnicsRaceWireCodec.OutsideControlState(
                mode = TechnicsRaceWireCodec.OutsideControlMode.ANC,
                noiseCancelLevel = 100,
                ambientLevel = 50,
            ),
            TechnicsRaceWireCodec.parseOutsideControl(outside),
        )

        assertNull(
            TechnicsRaceWireCodec.parseOutsideControl(
                decode("05 5B 06 00 0A 00 01 01 64 32").single(),
            ),
        )
        assertNull(
            TechnicsRaceWireCodec.parseOutsideControl(
                decode("05 5B 06 00 0A 00 00 02 65 32").single(),
            ),
        )
    }

    @Test
    fun decoderHandlesGarbageFragmentationAndCoalescedFrames() {
        val right = hex("05 5B 05 00 D6 0C 00 00 57")
        val case = hex("05 5B 04 00 40 00 00 40")
        val decoder = TechnicsRaceWireCodec.Decoder()

        assertTrue(
            decoder.offer(byteArrayOf(0x7F, 0x55) + right.copyOfRange(0, 4)).isEmpty(),
        )
        val frames = decoder.offer(right.copyOfRange(4, right.size) + case)

        assertEquals(2, frames.size)
        assertEquals(0x0CD6, frames[0].raceId)
        assertEquals(TechnicsRaceWireCodec.MessageType.RESPONSE, frames[0].type)
        assertArrayEquals(right, frames[0].bytes)
        assertEquals(0x0040, frames[1].raceId)
    }

    @Test
    fun decoderRejectsBadTypeAndLengthsThenResynchronizes() {
        val badType = hex("05 59 02 00 34 12")
        val tooShort = hex("05 5B 01 00 34 05")
        val tooLarge = hex("05 5B FD 01 34 12")
        val valid = hex("05 5B 04 00 40 00 00 40")

        val frames = TechnicsRaceWireCodec.Decoder().offer(
            badType + tooShort + tooLarge + valid,
        )

        assertEquals(1, frames.size)
        assertEquals(0x0040, frames.single().raceId)
    }

    @Test
    fun decoderResetDropsFragmentedFrame() {
        val frame = hex("05 5B 04 00 40 00 00 40")
        val decoder = TechnicsRaceWireCodec.Decoder()

        assertTrue(decoder.offer(frame.copyOfRange(0, 5)).isEmpty())
        decoder.reset()
        assertTrue(decoder.offer(frame.copyOfRange(5, frame.size)).isEmpty())
    }

    private fun assertSequence(
        mode: TechnicsRaceWireCodec.NoiseMode,
        vararg expected: String,
    ) {
        val actual = TechnicsRaceWireCodec.setNoiseMode(mode)
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index -> assertHex(expected[index], actual[index]) }
    }

    private fun assertHex(expected: String, actual: ByteArray) {
        assertArrayEquals(hex(expected), actual)
    }

    private fun decode(value: String): List<TechnicsRaceWireCodec.Frame> =
        TechnicsRaceWireCodec.Decoder().offer(hex(value))

    private fun hex(value: String): ByteArray = TechnicsRaceWireCodec.hex(value)
}
