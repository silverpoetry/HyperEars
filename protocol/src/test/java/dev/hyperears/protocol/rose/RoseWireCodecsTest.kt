package dev.hyperears.protocol.rose

import org.junit.Assert.assertEquals
import org.junit.Test

class RoseWireCodecsTest {
    @Test
    fun ceramicsXBuildsCapturedModeCommandsAndParsesQueryAndAsyncReports() {
        assertEquals("00 27 01 00 01 0C", RoseCeramicsXWireCodec.queryNoiseMode.hex())
        assertEquals(
            "00 2C 01 00 01 01",
            RoseCeramicsXWireCodec.setNoiseMode(RoseCeramicsXWireCodec.NoiseMode.ANC).hex(),
        )
        assertEquals(
            "00 2C 01 00 01 02",
            RoseCeramicsXWireCodec.setNoiseMode(RoseCeramicsXWireCodec.NoiseMode.TRANSPARENCY).hex(),
        )
        assertEquals(
            "00 2C 01 00 01 00",
            RoseCeramicsXWireCodec.setNoiseMode(RoseCeramicsXWireCodec.NoiseMode.WIND).hex(),
        )
        assertEquals(
            "00 2C 01 00 01 03",
            RoseCeramicsXWireCodec.setNoiseMode(RoseCeramicsXWireCodec.NoiseMode.OFF).hex(),
        )
        assertEquals(
            RoseCeramicsXWireCodec.NoiseMode.OFF,
            RoseCeramicsXWireCodec.parseNoiseMode(hex("00 27 02 00 03 0C 01 03")),
        )
        assertEquals(
            RoseCeramicsXWireCodec.NoiseMode.ANC,
            RoseCeramicsXWireCodec.parseNoiseMode(hex("00 28 02 00 03 0C 01 01")),
        )
    }

    @Test
    fun ceramicsXRejectsAckAndMalformedOrUnknownModeReports() {
        assertEquals(null, RoseCeramicsXWireCodec.parseNoiseMode(hex("00 2C 02 00 01 00")))
        assertEquals(null, RoseCeramicsXWireCodec.parseNoiseMode(hex("00 28 02 00 03 0C 01")))
        assertEquals(null, RoseCeramicsXWireCodec.parseNoiseMode(hex("00 28 02 00 03 0C 01 04")))
        assertEquals(null, RoseCeramicsXWireCodec.parseNoiseMode(hex("00 28 02 00 03 0D 01 01")))
    }

    @Test
    fun earfreeI5DecodesFragmentedBatteryAndNoiseFrames() {
        val battery = response(
            group = 0x01,
            command = 0x01,
            payload = byteArrayOf(0, 0, 91, 82, 1, 0, 67),
        )
        val noise = response(
            group = 0x06,
            command = 0x02,
            payload = byteArrayOf(0, 0, 1, 0),
        )
        val decoder = RoseEarfreeI5WireCodec.Decoder()

        assertEquals(emptyList<RoseEarfreeI5WireCodec.Frame>(), decoder.offer(battery.take(6).toByteArray()))
        val frames = decoder.offer(battery.drop(6).toByteArray() + noise)

        assertEquals(2, frames.size)
        assertEquals(
            RoseEarfreeI5WireCodec.BatteryState(91, 82, 67, true, false),
            RoseEarfreeI5WireCodec.parseBattery(frames[0]),
        )
        assertEquals(
            RoseEarfreeI5WireCodec.NoiseMode.WIND,
            RoseEarfreeI5WireCodec.parseNoiseMode(frames[1]),
        )
        assertEquals(
            "08 EE 00 00 00 06 82 0E 00 00 00 01 00 8D",
            RoseEarfreeI5WireCodec
                .setNoiseMode(RoseEarfreeI5WireCodec.NoiseMode.WIND)
                .hex(),
        )
    }

    @Test
    fun budsFeelBuildsSequencedCommandsAndDecodesTlvStatus() {
        assertEquals(
            "FF 2A 02 09 04 38 AA",
            RoseBudsFeelMk2WireCodec
                .setNoiseMode(0x2A, RoseBudsFeelMk2WireCodec.NoiseMode.WIND)
                .hex(),
        )

        val body = byteArrayOf(
            0xDD.toByte(), 0x2A, 0x15,
            0x04, 0x0C, 90, 81, 55,
            0x02, 0x09, 0x04,
        ) + ByteArray(13)
        val response = body + byteArrayOf(body.checksum(), 0xAA.toByte())
        val decoder = RoseBudsFeelMk2WireCodec.Decoder()
        val split = response.size / 2

        assertEquals(emptyList<RoseBudsFeelMk2WireCodec.State>(), decoder.offer(response.copyOfRange(0, split)))
        assertEquals(
            listOf(
                RoseBudsFeelMk2WireCodec.State.Battery(90, 81, 55),
                RoseBudsFeelMk2WireCodec.State.Noise(RoseBudsFeelMk2WireCodec.NoiseMode.WIND),
            ),
            decoder.offer(response.copyOfRange(split, response.size)),
        )
    }

    @Test
    fun budsFeelDecodesIndependentBatteryAndNoiseResponses() {
        val battery = responseFrame(
            sequence = 0xFC,
            payload = byteArrayOf(0x0C, 0x63, 0x63, 0x00),
        )
        val noise = responseFrame(
            sequence = 0xFB,
            payload = byteArrayOf(0x09, 0x02),
        )
        val decoder = RoseBudsFeelMk2WireCodec.Decoder()

        assertEquals(
            emptyList<RoseBudsFeelMk2WireCodec.State>(),
            decoder.offer(battery.copyOfRange(0, 4)),
        )
        assertEquals(
            listOf(
                RoseBudsFeelMk2WireCodec.State.Battery(99, 99, 0),
                RoseBudsFeelMk2WireCodec.State.Noise(RoseBudsFeelMk2WireCodec.NoiseMode.OFF),
            ),
            decoder.offer(battery.copyOfRange(4, battery.size) + noise),
        )
    }

    @Test
    fun budsFeelRejectsInvalidLengthAndResynchronizesAtNextResponse() {
        val invalid = byteArrayOf(
            0xDD.toByte(), 0x01, 0x04, 0x09, 0x02, 0x00, 0xAA.toByte(),
        )
        val valid = responseFrame(
            sequence = 0x02,
            payload = byteArrayOf(0x09, 0x03),
        )
        val decoder = RoseBudsFeelMk2WireCodec.Decoder()

        assertEquals(
            listOf(RoseBudsFeelMk2WireCodec.State.Noise(RoseBudsFeelMk2WireCodec.NoiseMode.TRANSPARENCY)),
            decoder.offer(invalid + valid),
        )
    }

    @Test
    fun budsFeelDecodesExtendedTlvStreamFromCapturedCeramicsUltraResponse() {
        // Captured from ROSE Ceramics U: `DD seq 15 <21-byte block> <TLV extension> checksum AA`.
        // The noise (09) and battery (0C) records live in the lengthless extension stream.
        val decoder = RoseBudsFeelMk2WireCodec.Decoder()

        assertEquals(
            CERAMICS_STATES,
            decoder.offer(CERAMICS_EXTENDED_FRAME),
        )
    }

    @Test
    fun ceramicsUltraDecodesExtendedAncVariantsAndMaskedBatteryPercentages() {
        val decoder = RoseBudsFeelMk2WireCodec.Decoder()

        assertEquals(
            listOf(
                RoseBudsFeelMk2WireCodec.State.Noise(
                    RoseBudsFeelMk2WireCodec.NoiseMode.EXTREME_ANC,
                ),
                RoseBudsFeelMk2WireCodec.State.Battery(
                    leftPercent = 100,
                    rightPercent = 100,
                    casePercent = 94,
                ),
            ),
            decoder.offer(CERAMICS_ULTRA_EXTREME_ANC_FRAME),
        )
    }

    @Test
    fun budsFeelRecognizesAdaptiveAncStatus() {
        val decoder = RoseBudsFeelMk2WireCodec.Decoder()
        val response = responseFrame(
            sequence = 0x12,
            payload = byteArrayOf(0x09, 0x05),
        )

        assertEquals(
            listOf(
                RoseBudsFeelMk2WireCodec.State.Noise(
                    RoseBudsFeelMk2WireCodec.NoiseMode.ADAPTIVE_ANC,
                ),
            ),
            decoder.offer(response),
        )
    }

    @Test
    fun budsFeelSplitsExtendedFrameAcrossOffers() {
        val decoder = RoseBudsFeelMk2WireCodec.Decoder()
        val split = CERAMICS_EXTENDED_FRAME.size / 2

        assertEquals(
            emptyList<RoseBudsFeelMk2WireCodec.State>(),
            decoder.offer(CERAMICS_EXTENDED_FRAME.copyOfRange(0, split)),
        )
        assertEquals(
            CERAMICS_STATES,
            decoder.offer(CERAMICS_EXTENDED_FRAME.copyOfRange(split, CERAMICS_EXTENDED_FRAME.size)),
        )
    }

    @Test
    fun budsFeelKeepsPartialExtendedFrameEndingInTerminatorByte() {
        val extension = byteArrayOf(
            0x03, 0x45, 0xAA.toByte(), 0x01,
            0x02, 0x09, 0x04,
        )
        val frame = extendedResponseFrame(
            sequence = 0x31,
            block = CERAMICS_EXTENDED_FRAME.copyOfRange(3, 24),
            extension = extension,
        )
        val split = frame.indexOf(0xAA.toByte()) + 1
        val decoder = RoseBudsFeelMk2WireCodec.Decoder()

        assertEquals(
            emptyList<RoseBudsFeelMk2WireCodec.State>(),
            decoder.offer(frame.copyOfRange(0, split)),
        )
        assertEquals(
            listOf(
                RoseBudsFeelMk2WireCodec.State.Noise(RoseBudsFeelMk2WireCodec.NoiseMode.WIND),
            ),
            decoder.offer(frame.copyOfRange(split, frame.size)),
        )
    }

    @Test
    fun budsFeelKeepsPartialExtendedFrameContainingResponseMarker() {
        val extension = byteArrayOf(
            0x03, 0x45, 0xDD.toByte(), 0x01,
            0x02, 0x09, 0x01,
        )
        val frame = extendedResponseFrame(
            sequence = 0x32,
            block = CERAMICS_EXTENDED_FRAME.copyOfRange(3, 24),
            extension = extension,
        )
        val marker = (1 until frame.size).first { frame[it] == 0xDD.toByte() }
        val split = marker + 1
        val decoder = RoseBudsFeelMk2WireCodec.Decoder()

        assertEquals(
            emptyList<RoseBudsFeelMk2WireCodec.State>(),
            decoder.offer(frame.copyOfRange(0, split)),
        )
        assertEquals(
            listOf(
                RoseBudsFeelMk2WireCodec.State.Noise(RoseBudsFeelMk2WireCodec.NoiseMode.ANC),
            ),
            decoder.offer(frame.copyOfRange(split, frame.size)),
        )
    }

    @Test
    fun budsFeelDecodesTwoExtendedFramesInOneOffer() {
        val second = extendedResponseFrame(
            sequence = 0x04,
            block = CERAMICS_EXTENDED_FRAME.copyOfRange(3, 24),
            extension = CERAMICS_EXTENDED_FRAME.copyOfRange(24, CERAMICS_EXTENDED_FRAME.size - 2),
        )
        val decoder = RoseBudsFeelMk2WireCodec.Decoder()

        assertEquals(
            CERAMICS_STATES + CERAMICS_STATES,
            decoder.offer(CERAMICS_EXTENDED_FRAME + second),
        )
    }

    @Test
    fun budsFeelDecodesExtendedFrameFollowedByCompactFrame() {
        val compact = responseFrame(
            sequence = 0x22,
            payload = byteArrayOf(0x09, 0x02),
        )
        val decoder = RoseBudsFeelMk2WireCodec.Decoder()

        assertEquals(
            CERAMICS_STATES +
                listOf(RoseBudsFeelMk2WireCodec.State.Noise(RoseBudsFeelMk2WireCodec.NoiseMode.OFF)),
            decoder.offer(CERAMICS_EXTENDED_FRAME + compact),
        )
    }

    @Test
    fun budsFeelResynchronizesAfterCorruptExtendedFrame() {
        val corrupt = CERAMICS_EXTENDED_FRAME.copyOf()
        corrupt[40] = (corrupt[40].toInt() xor 0x01).toByte()
        val valid = responseFrame(
            sequence = 0x23,
            payload = byteArrayOf(0x0C, 0x50, 0x51, 0x52),
        )
        val decoder = RoseBudsFeelMk2WireCodec.Decoder()

        assertEquals(
            listOf(
                RoseBudsFeelMk2WireCodec.State.Battery(80, 81, 82),
            ),
            decoder.offer(corrupt + valid),
        )
    }

    @Test
    fun budsFeelResetClearsPartialExtendedFrame() {
        val decoder = RoseBudsFeelMk2WireCodec.Decoder()
        decoder.offer(CERAMICS_EXTENDED_FRAME.copyOfRange(0, 30))
        decoder.reset()

        assertEquals(
            CERAMICS_STATES,
            decoder.offer(CERAMICS_EXTENDED_FRAME),
        )
    }

    private fun extendedResponseFrame(
        sequence: Int,
        block: ByteArray,
        extension: ByteArray,
    ): ByteArray {
        val body = byteArrayOf(
            0xDD.toByte(),
            sequence.toByte(),
            block.size.toByte(),
        ) + block + extension
        return body + byteArrayOf(body.checksum(), 0xAA.toByte())
    }

    private companion object {
        val CERAMICS_EXTENDED_FRAME: ByteArray = hex(
            "DD 03 15 01 01 04 02 01 03 02 04 07 05 00 11 05 12 01 13 03 14 08 " +
                "15 00 02 07 00 02 09 03 04 0C 61 62 5C 04 0D 00 03 04 02 0E 00 " +
                "02 12 01 02 2A 04 02 2B 01 02 2C 05 02 2D 01 02 2E 01 02 31 00 " +
                "02 32 00 02 33 00 05 36 01 01 01 01 D3 AA",
        )

        val CERAMICS_ULTRA_EXTREME_ANC_FRAME: ByteArray = hex(
            "DD 01 15 01 01 05 02 07 03 02 04 06 05 00 11 04 12 01 13 03 14 08 " +
                "15 00 02 07 00 02 09 06 04 0C E4 E4 5E 04 0D 00 03 04 02 0E 00 " +
                "02 12 01 02 2A 00 02 2B 00 02 2C 05 02 2D 05 02 2E 00 02 31 00 " +
                "02 32 01 02 33 00 05 36 01 01 01 01 DF AA",
        )

        val CERAMICS_STATES: List<RoseBudsFeelMk2WireCodec.State> = listOf(
            RoseBudsFeelMk2WireCodec.State.Noise(
                RoseBudsFeelMk2WireCodec.NoiseMode.TRANSPARENCY,
            ),
            RoseBudsFeelMk2WireCodec.State.Battery(
                leftPercent = 97,
                rightPercent = 98,
                casePercent = 92,
            ),
        )

        fun hex(value: String): ByteArray =
            value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun hex(value: String): ByteArray =
        value.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private fun responseFrame(sequence: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(
            0xDD.toByte(),
            sequence.toByte(),
            payload.size.toByte(),
        ) + payload
        return body + byteArrayOf(body.checksum(), 0xAA.toByte())
    }

    private fun response(group: Int, command: Int, payload: ByteArray): ByteArray {
        val size = 10 + payload.size
        val body = byteArrayOf(
            0x09,
            0xFF.toByte(),
            0,
            0,
            1,
            group.toByte(),
            command.toByte(),
            size.toByte(),
            0,
        ) + payload
        return body + byteArrayOf(body.checksum())
    }

    private fun ByteArray.checksum(): Byte =
        sumOf { it.toInt() and 0xFF }.and(0xFF).toByte()

    private fun ByteArray.hex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
