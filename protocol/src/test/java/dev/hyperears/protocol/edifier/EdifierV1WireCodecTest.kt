package dev.hyperears.protocol.edifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V1 (bleVersion=1) framing verified against the live Edifier Connect v8.4.48 RFCOMM capture of
 * an Edifier W820NB 双金标版 (2026-08-24).
 */
class EdifierV1WireCodecTest {

    // ── Query packets (exact bytes captured from the app) ──

    @Test
    fun `battery query frame matches live capture`() {
        assertEquals("AA 01 D0 21 94", EdifierV1WireCodec.queryBattery.toHex())
    }

    @Test
    fun `ANC query frame matches live capture`() {
        assertEquals("AA 01 CC 21 90", EdifierV1WireCodec.queryAnc.toHex())
    }

    @Test
    fun `function query frame matches live capture`() {
        assertEquals("AA 01 D8 21 9C", EdifierV1WireCodec.queryFunction.toHex())
    }

    @Test
    fun `game state query frame matches live capture`() {
        assertEquals("AA 01 08 20 CC", EdifierV1WireCodec.queryGameState.toHex())
    }

    @Test
    fun `game state set frames match live capture`() {
        assertEquals("AA 02 09 01 20 CF", EdifierV1WireCodec.setGameState(true).toHex())
        assertEquals("AA 02 09 00 20 CE", EdifierV1WireCodec.setGameState(false).toHex())
    }

    // ── Receive parsing ──

    /** Battery response: BB 02 D0 35 21 DB -> 0x35 = 53%. */
    @Test
    fun `parse battery response 0x35 gives 53 percent`() {
        val frames = EdifierV1WireCodec.Decoder().offer(hex("BB 02 D0 35 21 DB"))
        assertEquals(1, frames.size)
        assertEquals(53, EdifierV1WireCodec.parseBatteryState(frames.single()))
    }

    /** ANC response: BB 03 CC 02 06 21 AB -> mode 0x02 (降噪), level 0x06. */
    @Test
    fun `parse ANC response keeps mode and level`() {
        val frames = EdifierV1WireCodec.Decoder().offer(hex("BB 03 CC 02 06 21 AB"))
        assertEquals(1, frames.size)
        val anc = EdifierV1WireCodec.parseAncState(frames.single())
        assertEquals(0x02, anc!!.mode)
        assertEquals(0x06, anc.level)
    }

    /** Game query response: BB 02 08 00 20 DE -> off. */
    @Test
    fun `parse game query response off`() {
        val frames = EdifierV1WireCodec.Decoder().offer(hex("BB 02 08 00 20 DE"))
        assertEquals(1, frames.size)
        assertEquals(false, EdifierV1WireCodec.parseGameState(frames.single()))
    }

    /** Game set echo: BB 02 09 01 20 E0 -> on. */
    @Test
    fun `parse game set echo on`() {
        val frames = EdifierV1WireCodec.Decoder().offer(hex("BB 02 09 01 20 E0"))
        assertEquals(1, frames.size)
        assertEquals(true, EdifierV1WireCodec.parseGameState(frames.single()))
    }

    /** Older firmware response header 0xCC is accepted: CC 02 C4 00 21 AB. */
    @Test
    fun `old receive header is accepted`() {
        val frames = EdifierV1WireCodec.Decoder().offer(hex("CC 02 C4 00 21 AB"))
        assertEquals(1, frames.size)
        assertEquals(0xC4, frames.single().commandIndex)
        assertTrue(EdifierV1WireCodec.isProtocolResponse(frames.single()))
    }

    /** Device function response: BB 16 D8 0D ... (21-byte payload, captured). */
    @Test
    fun `function response decodes with full payload`() {
        val bytes = hex("BB 16 D8 0D 01 01 02 01 01 01 01 00 02 01 01 00 01 00 00 0F 70 04 00 04 22 63")
        val frames = EdifierV1WireCodec.Decoder().offer(bytes)
        assertEquals(1, frames.size)
        val frame = frames.single()
        assertEquals(EdifierV1WireCodec.CMD_FUNCTION_QUERY, frame.commandIndex)
        assertEquals(21, frame.payload.size)
    }

    /** Unsolicited V1 state pushes (A2DP state 0xC3=03, codec 0x68=04) parse as frames. */
    @Test
    fun `unsolicited state pushes parse as frames`() {
        val frames = EdifierV1WireCodec.Decoder().offer(hex("BB 02 C3 03 21 9C BB 02 68 04 21 42"))
        assertEquals(2, frames.size)
        assertEquals(0xC3, frames[0].commandIndex)
        assertEquals(3, frames[0].payload.single().toInt())
        assertEquals(0x68, frames[1].commandIndex)
        assertEquals(4, frames[1].payload.single().toInt())
    }

    // ── ANC set framing ──

    /** ANC set (降噪): AA 02 C1 02 21 88. */
    @Test
    fun `set ANC mode 2 produces captured frame`() {
        assertEquals("AA 02 C1 02 21 88", EdifierV1WireCodec.setAnc(2).toHex())
    }

    /** ANC set with level (通透 + level 6): AA 03 C1 03 06 21 90. */
    @Test
    fun `set ANC mode 3 with level produces captured frame`() {
        assertEquals("AA 03 C1 03 06 21 90", EdifierV1WireCodec.setAnc(3, level = 6).toHex())
    }

    /** ANC set response echo: BB 03 C1 03 06 21 A1 parses (level preserved). */
    @Test
    fun `ANC set response echo parses as frame`() {
        val frames = EdifierV1WireCodec.Decoder().offer(hex("BB 03 C1 03 06 21 A1"))
        assertEquals(1, frames.size)
        assertEquals(EdifierV1WireCodec.CMD_ANC_SET, frames.single().commandIndex)
    }

    // ── Robustness ──

    @Test
    fun `corrupted CRC frame is rejected`() {
        val frames = EdifierV1WireCodec.Decoder().offer(hex("BB 02 D0 35 21 DC"))
        assertEquals(0, frames.size)
    }

    @Test
    fun `battery payload above 100 is not a battery reading`() {
        val frame = EdifierV1WireCodec.Frame(
            header = EdifierV1WireCodec.RECEIVE_HEADER,
            commandIndex = EdifierV1WireCodec.CMD_BATTERY_QUERY,
            payload = byteArrayOf(0x7F.toByte()),
            bytes = byteArrayOf(),
        )
        assertNull(EdifierV1WireCodec.parseBatteryState(frame))
    }

    @Test
    fun `outbound echo cannot establish battery or ANC evidence`() {
        val batteryEcho = EdifierV1WireCodec.Decoder().offer(EdifierV1WireCodec.queryBattery).single()
        assertNull(EdifierV1WireCodec.parseBatteryState(batteryEcho))
    }

    @Test
    fun `sticky stream of frames decodes`() {
        val bytes = hex(
            "AA 01 D0 21 94" +
                " BB 02 D0 35 21 DB" +
                " AA 01 CC 21 90" +
                " BB 03 CC 02 06 21 AB",
        )
        val frames = EdifierV1WireCodec.Decoder().offer(bytes)
        assertEquals(4, frames.size)
        assertNotNull(EdifierV1WireCodec.parseBatteryState(frames[1]))
        assertNotNull(EdifierV1WireCodec.parseAncState(frames[3]))
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun hex(value: String): ByteArray = value
        .trim()
        .split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
