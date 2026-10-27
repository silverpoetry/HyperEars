package dev.hyperears.protocol.moondrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoondropZzzAngelsWireCodecTest {
    @Test
    fun requestBytesMatchTheCapturedSession() {
        assertEquals("FF 01 00 00 00 0A 03 00", MoondropZzzAngelsWireCodec.handshake.hex())
        assertEquals(
            "FF 04 00 02 00 1D 1A 01 01 02",
            MoondropZzzAngelsWireCodec.queryBattery.hex(),
        )
    }

    @Test
    fun capturedHandshakeAndBatteryFramesParse() {
        val decoder = MoondropZzzAngelsWireCodec.Decoder()

        // The captured handshake response carries the family version payload and a `40`
        // trailing byte outside the declared length.
        val handshake = decoder.offer(
            "FF 04 00 04 00 0A 83 00 00 04 03 01 40".hexBytes(),
        ).single()
        assertTrue(MoondropZzzAngelsWireCodec.parseHandshake(handshake))
        assertFalse(MoondropZzzAngelsWireCodec.isHandshakeFailure(handshake))

        // The captured battery response covers exactly the two earbuds.
        val battery = decoder.offer(
            "FF 04 00 04 00 1D 1B 01 01 3C 02 64".hexBytes(),
        ).single()
        assertEquals(
            MoondropZzzAngelsWireCodec.BatteryState(reading(60), reading(100), null),
            MoondropZzzAngelsWireCodec.parseBattery(battery),
        )
    }

    @Test
    fun handshakeAcceptsOtherLegalVersionsAndRejectsErrorStatus() {
        val decoder = MoondropZzzAngelsWireCodec.Decoder()

        val otherVersion = decoder.offer(
            MoondropZzzAngelsWireCodec.frame(
                command = 0x0A,
                subcommand = 0x83,
                opcode = 0x00,
                parameters = byteArrayOf(0, 3, 2, 5),
            ),
        ).single()
        assertTrue(MoondropZzzAngelsWireCodec.parseHandshake(otherVersion))

        val errorStatus = decoder.offer(
            MoondropZzzAngelsWireCodec.frame(
                command = 0x0A,
                subcommand = 0x83,
                opcode = 0x00,
                parameters = byteArrayOf(2, 4, 3, 1),
            ),
        ).single()
        assertFalse(MoondropZzzAngelsWireCodec.parseHandshake(errorStatus))
        assertTrue(MoondropZzzAngelsWireCodec.isHandshakeFailure(errorStatus))

        val wrongShape = decoder.offer(
            MoondropZzzAngelsWireCodec.frame(
                command = 0x0A,
                subcommand = 0x83,
                opcode = 0x00,
                parameters = byteArrayOf(0, 4),
            ),
        ).single()
        assertFalse(MoondropZzzAngelsWireCodec.parseHandshake(wrongShape))
        assertFalse(MoondropZzzAngelsWireCodec.isHandshakeFailure(wrongShape))
    }

    @Test
    fun batteryParsesTwoOrThreePairsInAnyOrder() {
        val decoder = MoondropZzzAngelsWireCodec.Decoder()

        val withCase = decoder.offer(
            MoondropZzzAngelsWireCodec.frame(
                command = 0x1D,
                subcommand = 0x1B,
                opcode = 0x01,
                parameters = byteArrayOf(1, 91, 2, 76, 3, 88),
            ),
        ).single()
        assertEquals(
            MoondropZzzAngelsWireCodec.BatteryState(reading(91), reading(76), reading(88)),
            MoondropZzzAngelsWireCodec.parseBattery(withCase),
        )

        val outOfOrder = decoder.offer(
            MoondropZzzAngelsWireCodec.frame(
                command = 0x1D,
                subcommand = 0x1B,
                opcode = 0x01,
                parameters = byteArrayOf(2, 60, 1, 55),
            ),
        ).single()
        assertEquals(
            MoondropZzzAngelsWireCodec.BatteryState(reading(55), reading(60), null),
            MoondropZzzAngelsWireCodec.parseBattery(outOfOrder),
        )
    }

    @Test
    fun batteryMarksUnreadableComponentsUnknown() {
        val decoder = MoondropZzzAngelsWireCodec.Decoder()

        val allUnreadable = decoder.offer(
            MoondropZzzAngelsWireCodec.frame(
                command = 0x1D,
                subcommand = 0x1B,
                opcode = 0x01,
                parameters = byteArrayOf(1, 0, 2, 0xFF.toByte()),
            ),
        ).single()
        assertEquals(
            MoondropZzzAngelsWireCodec.BatteryState(reading(null), reading(null), null),
            MoondropZzzAngelsWireCodec.parseBattery(allUnreadable),
        )

        val oneBudInCase = decoder.offer(
            MoondropZzzAngelsWireCodec.frame(
                command = 0x1D,
                subcommand = 0x1B,
                opcode = 0x01,
                parameters = byteArrayOf(1, 0, 2, 0xFF.toByte(), 3, 42),
            ),
        ).single()
        assertEquals(
            MoondropZzzAngelsWireCodec.BatteryState(reading(null), reading(null), reading(42)),
            MoondropZzzAngelsWireCodec.parseBattery(oneBudInCase),
        )
    }

    @Test
    fun malformedBatteryFramesAreRejected() {
        val decoder = MoondropZzzAngelsWireCodec.Decoder()

        val unknownId = decoder.offer(
            MoondropZzzAngelsWireCodec.frame(
                command = 0x1D,
                subcommand = 0x1B,
                opcode = 0x01,
                parameters = byteArrayOf(1, 91, 9, 76),
            ),
        ).single()
        val duplicateId = decoder.offer(
            MoondropZzzAngelsWireCodec.frame(
                command = 0x1D,
                subcommand = 0x1B,
                opcode = 0x01,
                parameters = byteArrayOf(1, 91, 1, 76),
            ),
        ).single()
        val oddLength = decoder.offer(
            MoondropZzzAngelsWireCodec.frame(
                command = 0x1D,
                subcommand = 0x1B,
                opcode = 0x01,
                parameters = byteArrayOf(1, 91, 2),
            ),
        ).single()
        val wrongOpcode = decoder.offer(
            MoondropZzzAngelsWireCodec.frame(
                command = 0x1D,
                subcommand = 0x1B,
                opcode = 0x02,
                parameters = byteArrayOf(1, 91, 2, 76),
            ),
        ).single()
        assertNull(MoondropZzzAngelsWireCodec.parseBattery(unknownId))
        assertNull(MoondropZzzAngelsWireCodec.parseBattery(duplicateId))
        assertNull(MoondropZzzAngelsWireCodec.parseBattery(oddLength))
        assertNull(MoondropZzzAngelsWireCodec.parseBattery(wrongOpcode))
    }

    @Test
    fun decoderHandlesFragmentationCoalescingAndTrailingBytes() {
        val decoder = MoondropZzzAngelsWireCodec.Decoder()
        val first = MoondropZzzAngelsWireCodec.frame(
            command = 0x1D,
            subcommand = 0x1B,
            opcode = 0x01,
            parameters = byteArrayOf(1, 91, 2, 76, 3, 88),
        ) + byteArrayOf(0x40)
        val second = MoondropZzzAngelsWireCodec.frame(
            command = 0x1D,
            subcommand = 0x1B,
            opcode = 0x01,
            parameters = byteArrayOf(1, 55, 2, 60),
        )
        assertTrue(decoder.offer(first.copyOfRange(0, 5)).isEmpty())
        val frames = decoder.offer(first.copyOfRange(5, first.size) + second)
        assertEquals(2, frames.size)
        assertEquals(91, MoondropZzzAngelsWireCodec.parseBattery(frames[0])?.left?.percent)
        assertEquals(55, MoondropZzzAngelsWireCodec.parseBattery(frames[1])?.left?.percent)
    }

    @Test
    fun absentComponentsAndExplicitUnknownHaveDifferentMeanings() {
        val partial = parseBattery(byteArrayOf(2, 0xFF.toByte()))!!
        assertNull(partial.left)
        assertNotNull(partial.right)
        assertNull(partial.right?.percent)
        assertNull(partial.chargingCase)
        assertEquals(0, parseBattery(byteArrayOf(3, 0))?.chargingCase?.percent)
    }

    @Test
    fun malformedPercentagesDoNotErasePreviousState() {
        for (value in listOf(101, 127, 254)) {
            assertNull(parseBattery(byteArrayOf(1, value.toByte(), 2, 70)))
        }
    }

    @Test
    fun unverifiedPushDialectsCannotPublishState() {
        for ((sub, opcode) in listOf(0x1A to 0x81, 0x81 to 0x01)) {
            val frame = MoondropZzzAngelsWireCodec.Frame(0x1D, sub, opcode, byteArrayOf(1, 60, 2, 70))
            assertNull(MoondropZzzAngelsWireCodec.parseBattery(frame))
        }
    }

    private fun reading(percent: Int?) = MoondropZzzAngelsWireCodec.BatteryReading(percent)

    private fun parseBattery(parameters: ByteArray) = MoondropZzzAngelsWireCodec.parseBattery(
        MoondropZzzAngelsWireCodec.Frame(0x1D, 0x1B, 0x01, parameters),
    )

    private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun String.hexBytes(): ByteArray =
        split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
