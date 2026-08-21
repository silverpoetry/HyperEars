package dev.hyperears.protocol.moondrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoondropPuddingWireCodecTest {
    @Test
    fun commandsMatchCapturedBytes() {
        assertEquals("FF 01 00 00 00 0A 03 00", MoondropPuddingWireCodec.handshake.hex())
        assertEquals(
            "FF 04 00 02 00 1D 1A 01 01 02",
            MoondropPuddingWireCodec.queryBattery.hex(),
        )
        assertEquals("FF 04 00 00 00 1D 40 03", MoondropPuddingWireCodec.queryNoiseMode.hex())
        assertEquals(
            "FF 04 00 01 00 1D 40 04 00",
            MoondropPuddingWireCodec.setNoiseMode(MoondropPuddingWireCodec.NoiseMode.OFF).hex(),
        )
        assertEquals(
            "FF 04 00 01 00 1D 40 04 01",
            MoondropPuddingWireCodec.setNoiseMode(MoondropPuddingWireCodec.NoiseMode.ANC).hex(),
        )
        assertEquals(
            "FF 04 00 01 00 1D 40 04 02",
            MoondropPuddingWireCodec
                .setNoiseMode(MoondropPuddingWireCodec.NoiseMode.TRANSPARENCY)
                .hex(),
        )
    }

    @Test
    fun handshakeMatchesRobinShapeAndRejectsWrongPayload() {
        val accepted = MoondropPuddingWireCodec.Frame(0x0A, 0x83, 0x00, byteArrayOf(0, 4, 3, 1))
        val rejected = accepted.copy(parameters = byteArrayOf(0, 4, 3, 2))
        assertTrue(MoondropPuddingWireCodec.parseHandshake(accepted))
        assertFalse(MoondropPuddingWireCodec.parseHandshake(rejected))
    }

    @Test
    fun trailingBytesOutsideTheLengthFieldAreIgnored() {
        val queryResponse = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x41,
            opcode = 0x03,
            parameters = byteArrayOf(0x02),
        ) + byteArrayOf(0x40)
        val confirmResponse = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x41,
            opcode = 0x04,
            parameters = byteArrayOf(0x01),
        ) + byteArrayOf(0x40)
        val hello = MoondropPuddingWireCodec.frame(
            command = 0x0A,
            subcommand = 0x83,
            opcode = 0x00,
            parameters = byteArrayOf(0, 4, 3, 1),
        ) + byteArrayOf(0x40)

        assertEquals(
            MoondropPuddingWireCodec.NoiseMode.TRANSPARENCY,
            MoondropPuddingWireCodec.parseNoiseModeQuery(
                MoondropPuddingWireCodec.Decoder().offer(queryResponse).single(),
            ),
        )
        assertEquals(
            MoondropPuddingWireCodec.NoiseMode.ANC,
            MoondropPuddingWireCodec.parseNoiseModeConfirm(
                MoondropPuddingWireCodec.Decoder().offer(confirmResponse).single(),
            ),
        )
        assertTrue(
            MoondropPuddingWireCodec.parseHandshake(
                MoondropPuddingWireCodec.Decoder().offer(hello).single(),
            ),
        )
    }

    @Test
    fun batteryParsesFromQueryResponseAndUnsolicitedPush() {
        val queryResponse = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x1B,
            opcode = 0x01,
            parameters = byteArrayOf(1, 91, 2, 76, 3, 0xFF.toByte()),
        )
        val push = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x81,
            opcode = 0x01,
            parameters = byteArrayOf(1, 97, 2, 100, 3, 0xFF.toByte()),
        )
        val withCase = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x1B,
            opcode = 0x01,
            parameters = byteArrayOf(1, 60, 2, 55, 3, 88),
        )
        val decoder = MoondropPuddingWireCodec.Decoder()
        assertEquals(
            MoondropPuddingWireCodec.BatteryState(91, 76, casePercent = null),
            MoondropPuddingWireCodec.parseBattery(decoder.offer(queryResponse).single()),
        )
        assertEquals(
            MoondropPuddingWireCodec.BatteryState(97, 100, casePercent = null),
            MoondropPuddingWireCodec.parseBattery(decoder.offer(push).single()),
        )
        assertEquals(
            MoondropPuddingWireCodec.BatteryState(60, 55, casePercent = 88),
            MoondropPuddingWireCodec.parseBattery(decoder.offer(withCase).single()),
        )
    }

    @Test
    fun zeroOrFfBudValueMeansNotConnectedAndStaysUnknown() {
        val zeroLeft = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x1B,
            opcode = 0x01,
            parameters = byteArrayOf(1, 0, 2, 92, 3, 88),
        )
        val ffRight = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x81,
            opcode = 0x01,
            parameters = byteArrayOf(1, 61, 2, 0xFF.toByte(), 3, 88),
        )
        val decoder = MoondropPuddingWireCodec.Decoder()
        assertEquals(
            MoondropPuddingWireCodec.BatteryState(null, 92, casePercent = 88),
            MoondropPuddingWireCodec.parseBattery(decoder.offer(zeroLeft).single()),
        )
        assertEquals(
            MoondropPuddingWireCodec.BatteryState(61, null, casePercent = 88),
            MoondropPuddingWireCodec.parseBattery(decoder.offer(ffRight).single()),
        )

        val bothUnreadable = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x1B,
            opcode = 0x01,
            parameters = byteArrayOf(1, 0, 2, 0xFF.toByte(), 3, 88),
        )
        assertEquals(
            MoondropPuddingWireCodec.BatteryState(null, null, casePercent = 88),
            MoondropPuddingWireCodec.parseBattery(decoder.offer(bothUnreadable).single()),
        )
    }

    @Test
    fun malformedBatteryFramesAreRejected() {
        val wrongKeys = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x1B,
            opcode = 0x01,
            parameters = byteArrayOf(1, 91, 9, 76, 3, 0xFF.toByte()),
        )
        val outOfRange = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x1B,
            opcode = 0x01,
            parameters = byteArrayOf(1, 101, 2, 102, 3, 0xFF.toByte()),
        )
        val wrongLength = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x1B,
            opcode = 0x01,
            parameters = byteArrayOf(1, 91, 2, 76),
        )
        val wrongOpcode = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x1B,
            opcode = 0x02,
            parameters = byteArrayOf(1, 91, 2, 76, 3, 0xFF.toByte()),
        )
        val decoder = MoondropPuddingWireCodec.Decoder()
        assertNull(MoondropPuddingWireCodec.parseBattery(decoder.offer(wrongKeys).single()))
        assertNull(MoondropPuddingWireCodec.parseBattery(decoder.offer(outOfRange).single()))
        assertNull(MoondropPuddingWireCodec.parseBattery(decoder.offer(wrongLength).single()))
        assertNull(MoondropPuddingWireCodec.parseBattery(decoder.offer(wrongOpcode).single()))
    }

    @Test
    fun decoderHandlesFragmentationAndCoalescedFrames() {
        val decoder = MoondropPuddingWireCodec.Decoder()
        val query = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x41,
            opcode = 0x03,
            parameters = byteArrayOf(0x01),
        )
        val confirm = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x41,
            opcode = 0x04,
            parameters = byteArrayOf(0x00),
        )
        assertTrue(decoder.offer(query.copyOfRange(0, 5)).isEmpty())
        val frames = decoder.offer(query.copyOfRange(5, query.size) + confirm)
        assertEquals(2, frames.size)
        assertEquals(
            MoondropPuddingWireCodec.NoiseMode.ANC,
            MoondropPuddingWireCodec.parseNoiseModeQuery(frames[0]),
        )
        assertEquals(
            MoondropPuddingWireCodec.NoiseMode.OFF,
            MoondropPuddingWireCodec.parseNoiseModeConfirm(frames[1]),
        )
    }

    @Test
    fun malformedResponsesAreRejected() {
        val decoder = MoondropPuddingWireCodec.Decoder()
        val wrongSize = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x41,
            opcode = 0x03,
            parameters = byteArrayOf(0x01, 0x40),
        )
        val unknownMode = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x41,
            opcode = 0x04,
            parameters = byteArrayOf(0x03),
        )
        val wrongOpcode = MoondropPuddingWireCodec.frame(
            command = 0x1D,
            subcommand = 0x41,
            opcode = 0x02,
            parameters = byteArrayOf(0x01),
        )
        assertNull(MoondropPuddingWireCodec.parseNoiseModeQuery(decoder.offer(wrongSize).single()))
        assertNull(MoondropPuddingWireCodec.parseNoiseModeConfirm(decoder.offer(unknownMode).single()))
        assertNull(MoondropPuddingWireCodec.parseNoiseModeConfirm(decoder.offer(wrongOpcode).single()))
    }

    private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
