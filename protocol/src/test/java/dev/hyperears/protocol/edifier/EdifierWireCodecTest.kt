package dev.hyperears.protocol.edifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EdifierWireCodecTest {

    // ── Real captured frames from Edifier W860NB PRO (via protocol-test) ──

    /** Battery response: BB EC D0 00 01 99 11 -> payload[0]=0x99 ^ 0xA5 = 0x3C = 60% */
    @Test
    fun `parse battery response 0x99 gives 60 percent`() {
        val bytes = byteArrayOf(
            0xBB.toByte(), 0xEC.toByte(), 0xD0.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x99.toByte(), 0x11.toByte(),
        )
        val frames = EdifierWireCodec.Decoder().offer(bytes)
        assertEquals(1, frames.size)
        val battery = EdifierWireCodec.parseBatteryState(frames[0])
        assertNotNull(battery)
        assertEquals(EdifierWireCodec.BatteryState.Aggregate(60), battery)
    }

    /** ANC response for NC off: BB EC CC 00 02 B5 A0 CA -> payload B5 A0 -> 10 05 */
    @Test
    fun `parse ANC response B5 A0 gives ancIndex 16 ancValue 5`() {
        val bytes = byteArrayOf(
            0xBB.toByte(), 0xEC.toByte(), 0xCC.toByte(), 0x00.toByte(), 0x02.toByte(),
            0xB5.toByte(), 0xA0.toByte(), 0xCA.toByte(),
        )
        val frames = EdifierWireCodec.Decoder().offer(bytes)
        assertEquals(1, frames.size)
        val anc = EdifierWireCodec.parseAncState(frames[0])
        assertNotNull(anc)
        assertEquals(0x10, anc!!.mode) // ancIndex 16
        assertEquals(5, anc.level) // ancValue 5 = NC off
    }

    /** ANC response for comfort NC: BB EC CC 00 02 B5 A7 D1 -> 10 02 */
    @Test
    fun `parse ANC response B5 A7 gives ancValue 2 comfort`() {
        val bytes = byteArrayOf(
            0xBB.toByte(), 0xEC.toByte(), 0xCC.toByte(), 0x00.toByte(), 0x02.toByte(),
            0xB5.toByte(), 0xA7.toByte(), 0xD1.toByte(),
        )
        val frames = EdifierWireCodec.Decoder().offer(bytes)
        assertEquals(1, frames.size)
        val anc = EdifierWireCodec.parseAncState(frames[0])
        assertEquals(2, anc!!.level)
    }

    /** Evo Pro device state decrypts to 03 64 62 00 03 11; byte 0 is not a percentage. */
    @Test
    fun `parse Evo Pro F2 response gives independent ear batteries`() {
        val frame = EdifierWireCodec.Decoder().offer(
            hex("BB EC F2 00 06 A6 C1 C7 A5 A6 B4 CC"),
        ).single()

        assertEquals(EdifierWireCodec.CMD_DEVICE_STATE_QUERY, frame.commandIndex)
        assertEquals(
            EdifierWireCodec.BatteryState.TwsComponents(
                leftPercent = 100,
                rightPercent = 98,
            ),
            EdifierWireCodec.parseBatteryState(frame),
        )
    }

    /** FitClip Ultra: decrypted payload 03 39 06 46 01 11 carries L/R/case and charging state. */
    @Test
    fun `parse FitClip Ultra F2 response includes an online charging case`() {
        val frame = EdifierWireCodec.Decoder().offer(
            hex("BB EC F2 00 06 A6 9C A3 E3 A4 B4 BF"),
        ).single()

        assertEquals(
            EdifierWireCodec.BatteryState.TwsComponents(
                leftPercent = 57,
                rightPercent = 6,
                casePercent = 70,
                caseCharging = true,
            ),
            EdifierWireCodec.parseBatteryState(frame),
        )
    }

    @Test
    fun `single worn earbud reports percent 0 as not-connected null`() {
        // Left earbud disconnected (decrypts to 0) should surface as null, right earbud 98%.
        // payload bytes 1/2 = 0xA5/0xC7 -> decrypt 0x00/0x62 = 0%/98%. Byte 0 metadata=0x03.
        val frame = EdifierWireCodec.Decoder().offer(
            hex("BB EC F2 00 06 A6 A5 C7 A5 A6 B4 B0"),
        ).single()

        assertEquals(
            EdifierWireCodec.BatteryState.TwsComponents(
                leftPercent = null,
                rightPercent = 98,
            ),
            EdifierWireCodec.parseBatteryState(frame),
        )
    }

    @Test
    fun `Evo Pro F2 metadata byte is never accepted as aggregate battery`() {
        val frame = EdifierWireCodec.Decoder().offer(
            hex("BB EC F2 00 06 A6 C1 C7 A5 A6 B4 CC"),
        ).single()

        val battery = EdifierWireCodec.parseBatteryState(frame)
        assertEquals(100, (battery as EdifierWireCodec.BatteryState.TwsComponents).leftPercent)
    }

    /** Evo Pro ANC response: BE^A5=1B slot, A3^A5=06 (off). */
    @Test
    fun `parse Evo Pro ANC response preserves slot and value`() {
        val frame = EdifierWireCodec.Decoder().offer(
            hex("BB EC CC 00 02 BE A3 D6"),
        ).single()

        val anc = requireNotNull(EdifierWireCodec.parseAncState(frame))
        assertEquals(0x1B, anc.mode)
        assertEquals(6, anc.level)
    }

    // ── Send framing ──

    /** Battery query: AA EC D0 00 00 66 */
    @Test
    fun `battery query frame matches real capture`() {
        val expected = byteArrayOf(
            0xAA.toByte(), 0xEC.toByte(), 0xD0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x66.toByte(),
        )
        val actual = EdifierWireCodec.queryBattery
        assertEquals(expected.toHex(), actual.toHex())
    }

    /**
     * ANC set (deep NC): payload 10 01 -> encrypted B5 A4. raw = AA EC C1 00 02 B5 A4 B2
     */
    @Test
    fun `set ANC deep produces expected frame`() {
        val expected = byteArrayOf(
            0xAA.toByte(), 0xEC.toByte(), 0xC1.toByte(), 0x00.toByte(), 0x02.toByte(),
            0xB5.toByte(), 0xA4.toByte(), 0xB2.toByte(),
        )
        val actual = EdifierWireCodec.setAnc(EdifierWireCodec.ANC_VALUE_DEEP)
        assertEquals(expected.toHex(), actual.toHex())
    }

    /** ANC set (NC off): payload 10 05 -> encrypted B5 A0. raw = AA EC C1 00 02 B5 A0 AE */
    @Test
    fun `set ANC off produces expected frame`() {
        val expected = byteArrayOf(
            0xAA.toByte(), 0xEC.toByte(), 0xC1.toByte(), 0x00.toByte(), 0x02.toByte(),
            0xB5.toByte(), 0xA0.toByte(), 0xAE.toByte(),
        )
        val actual = EdifierWireCodec.setAnc(EdifierWireCodec.ANC_VALUE_OFF)
        assertEquals(expected.toHex(), actual.toHex())
    }

    @Test
    fun `game-mode query and writes match captured framing`() {
        assertEquals(
            hex("AA EC 08 00 00 9E").toHex(),
            EdifierWireCodec.queryGameState.toHex(),
        )
        assertEquals(
            hex("AA EC 09 00 01 A4 44").toHex(),
            EdifierWireCodec.setGameMode(enabled = true).toHex(),
        )
        assertEquals(
            hex("AA EC 09 00 01 A5 45").toHex(),
            EdifierWireCodec.setGameMode(enabled = false).toHex(),
        )
    }

    @Test
    fun `game-mode query and set responses expose only valid boolean values`() {
        val queryOn = EdifierWireCodec.Decoder().offer(
            hex("BB EC 08 00 01 A4 54"),
        ).single()
        val queryOff = EdifierWireCodec.Decoder().offer(
            hex("BB EC 08 00 01 A5 55"),
        ).single()
        val setOn = EdifierWireCodec.Decoder().offer(
            hex("BB EC 09 00 01 A4 55"),
        ).single()

        assertEquals(true, EdifierWireCodec.parseGameModeState(queryOn))
        assertEquals(false, EdifierWireCodec.parseGameModeState(queryOff))
        assertEquals(true, EdifierWireCodec.parseGameModeState(setOn))
    }

    @Test
    fun `garbage input is discarded`() {
        val bytes = byteArrayOf(
            0x01, 0x02, 0x03, 0xAA.toByte(), 0xEC.toByte(), 0xD0.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x66.toByte(),
        )
        val frames = EdifierWireCodec.Decoder().offer(bytes)
        assertEquals(1, frames.size)
    }

    @Test
    fun `null battery on empty payload`() {
        val frame = EdifierWireCodec.Frame(
            header = EdifierWireCodec.RECEIVE_HEADER,
            appCode = EdifierWireCodec.APP_CODE,
            commandIndex = EdifierWireCodec.CMD_BATTERY_QUERY,
            payload = byteArrayOf(),
            bytes = byteArrayOf(),
        )
        assertNull(EdifierWireCodec.parseBatteryState(frame))
    }

    @Test
    fun `outbound echo cannot establish battery or ANC evidence`() {
        val batteryEcho = EdifierWireCodec.Decoder().offer(EdifierWireCodec.queryBattery).single()
        val ancEcho = EdifierWireCodec.Decoder().offer(
            EdifierWireCodec.setAnc(EdifierWireCodec.ANC_VALUE_DEEP),
        ).single()

        assertNull(EdifierWireCodec.parseBatteryState(batteryEcho))
        assertNull(EdifierWireCodec.parseAncState(ancEcho))
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun hex(value: String): ByteArray = value
        .trim()
        .split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
