package dev.hyperears.protocol.edifier

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdifierV1WireCodecTest {

    // -- Verified against a live W820NB session (device push) and the Edifier Connect trace --

    @Test
    fun `query packets match the verified V1 bytes`() {
        assertArrayEquals(hex("AA 01 D0 21 94"), EdifierV1WireCodec.queryBattery)
        assertArrayEquals(hex("AA 01 CC 21 90"), EdifierV1WireCodec.queryAnc)
        assertArrayEquals(hex("AA 01 D8 21 9C"), EdifierV1WireCodec.queryFunction)
    }

    @Test
    fun `battery response decodes to its plaintext percent`() {
        val frame = EdifierV1WireCodec.Decoder()
            .offer(hex("BB 02 D0 35 21 DB"))
            .single()

        assertEquals(0x35, EdifierV1WireCodec.parseBattery(frame))
    }

    @Test
    fun `anc response decodes mode and level`() {
        val frame = EdifierV1WireCodec.Decoder()
            .offer(hex("BB 03 CC 02 06 21 AB"))
            .single()
        val anc = EdifierV1WireCodec.parseAnc(frame)

        assertEquals(EdifierV1WireCodec.ANC_MODE_NOISE_CANCELLATION, anc?.mode)
        assertEquals(6, anc?.level)
    }

    /** The captured `BB 02 C3 03 21 9C` push proves the receive layout and the CRC seed. */
    @Test
    fun `device A2DP push is accepted as a V1 frame`() {
        val frame = EdifierV1WireCodec.Decoder()
            .offer(hex("BB 02 C3 03 21 9C"))
            .single()

        assertTrue(EdifierV1WireCodec.isResponse(frame))
        assertEquals(EdifierV1WireCodec.CMD_A2DP_STATE, frame.commandIndex)
        assertArrayEquals(byteArrayOf(0x03), frame.payload)
        assertNull(EdifierV1WireCodec.parseBattery(frame))
        assertNull(EdifierV1WireCodec.parseAnc(frame))
    }


    @Test
    fun `anc write echo is accepted as noise state`() {
        val frame = EdifierV1WireCodec.Decoder()
            .offer(hex("BB 03 C1 02 06 21 A0"))
            .single()
        val anc = EdifierV1WireCodec.parseAnc(frame)

        assertEquals(EdifierV1WireCodec.ANC_MODE_NOISE_CANCELLATION, anc?.mode)
        assertEquals(6, anc?.level)
    }

    @Test
    fun `anc writes carry the verified mode bytes`() {
        assertArrayEquals(
            hex("AA 02 C1 02 21 88"),
            EdifierV1WireCodec.setAnc(EdifierV1WireCodec.ANC_MODE_NOISE_CANCELLATION),
        )
        assertArrayEquals(
            hex("AA 02 C1 01 21 87"),
            EdifierV1WireCodec.setAnc(EdifierV1WireCodec.ANC_MODE_STANDARD),
        )
        assertArrayEquals(
            hex("AA 03 C1 03 06 21 90"),
            EdifierV1WireCodec.setAnc(EdifierV1WireCodec.ANC_MODE_TRANSPARENCY, level = 6),
        )
    }

    @Test
    fun `crc mismatch is discarded and the following frame still decodes`() {
        val frames = EdifierV1WireCodec.Decoder().offer(
            hex("BB 02 D0 35 21 DC") + hex("BB 02 D0 40 21 E6"),
        )

        assertEquals(1, frames.size)
        assertEquals(0x40, EdifierV1WireCodec.parseBattery(frames.single()))
    }

    @Test
    fun `chunked reads reassemble one frame`() {
        val decoder = EdifierV1WireCodec.Decoder()
        val bytes = hex("BB 03 CC 03 06 21 AC")

        val frames = bytes.flatMap { decoder.offer(byteArrayOf(it)) }

        assertEquals(1, frames.size)
        assertEquals(3, EdifierV1WireCodec.parseAnc(frames.single())?.mode)
    }

    @Test
    fun `bes v2 traffic does not wedge the V1 decoder`() {
        val frames = EdifierV1WireCodec.Decoder().offer(
            hex("BB EC D0 00 01 99 11") + hex("BB 02 D0 35 21 DB"),
        )

        assertEquals(1, frames.size)
        assertEquals(0x35, EdifierV1WireCodec.parseBattery(frames.single()))
    }

    @Test
    fun `crc uses the 8217 seed over every preceding byte`() {
        assertEquals(0x2194, EdifierV1WireCodec.crc16(hex("AA 01 D0")))
        assertEquals(0x219C, EdifierV1WireCodec.crc16(hex("BB 02 C3 03")))
        assertEquals(0x21DB, EdifierV1WireCodec.crc16(hex("BB 02 D0 35")))
    }

    private fun hex(value: String): ByteArray = value
        .trim()
        .split(' ')
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
