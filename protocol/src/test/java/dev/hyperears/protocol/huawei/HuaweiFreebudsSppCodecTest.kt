package dev.hyperears.protocol.huawei

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec.NoiseMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiFreebudsSppCodecTest {

    @Test
    fun batteryQueryMatchesCapturedVendorFrame() {
        assertEquals(
            "5A 00 09 00 01 08 01 00 02 00 03 00 FB B9",
            HuaweiFreebudsSppCodec.queryBattery.hex(),
        )
    }

    @Test
    fun noiseStateQueryMatchesReferenceClient() {
        assertEquals(
            "5A 00 07 00 2B 2A 01 00 02 00 1D 33",
            HuaweiFreebudsSppCodec.queryNoiseState.hex(),
        )
    }

    @Test
    fun noiseModeCommandsMatchReferenceFrames() {
        assertEquals(
            "5A 00 07 00 2B 04 01 02 00 00 D2 2D",
            HuaweiFreebudsSppCodec.noiseModeCommand(NoiseMode.OFF).hex(),
        )
        assertEquals(
            "5A 00 07 00 2B 04 01 02 01 FF FF EC",
            HuaweiFreebudsSppCodec.noiseModeCommand(NoiseMode.ANC).hex(),
        )
        assertEquals(
            "5A 00 07 00 2B 04 01 02 02 FF AA BF",
            HuaweiFreebudsSppCodec.noiseModeCommand(NoiseMode.TRANSPARENCY).hex(),
        )
    }

    @Test
    fun noiseLevelCommandsMatchReferenceFrames() {
        assertEquals(
            "5A 00 07 00 2B 04 01 02 01 00 E1 1C",
            HuaweiFreebudsSppCodec.noiseLevelCommand(NoiseMode.ANC, 0).hex(),
        )
        assertEquals(
            "5A 00 07 00 2B 04 01 02 01 01 F1 3D",
            HuaweiFreebudsSppCodec.noiseLevelCommand(NoiseMode.ANC, 1).hex(),
        )
        assertEquals(
            "5A 00 07 00 2B 04 01 02 01 02 C1 5E",
            HuaweiFreebudsSppCodec.noiseLevelCommand(NoiseMode.ANC, 2).hex(),
        )
        assertEquals(
            "5A 00 07 00 2B 04 01 02 01 03 D1 7F",
            HuaweiFreebudsSppCodec.noiseLevelCommand(NoiseMode.ANC, 3).hex(),
        )
        assertEquals(
            "5A 00 07 00 2B 04 01 02 02 01 A4 6E",
            HuaweiFreebudsSppCodec.noiseLevelCommand(NoiseMode.TRANSPARENCY, 1).hex(),
        )
        assertEquals(
            "5A 00 07 00 2B 04 01 02 02 02 94 0D",
            HuaweiFreebudsSppCodec.noiseLevelCommand(NoiseMode.TRANSPARENCY, 2).hex(),
        )
    }

    @Test
    fun batteryResponseDecodesComponentsAndCharging() {
        val state = HuaweiFreebudsSppCodec.parseBatteryFrame(
            hex("5A 00 14 00 01 08 01 01 40 02 03 10 20 30 03 03 00 01 00 04 02 14 0A 14 61"),
        )!!
        assertEquals(0x40, state.globalPercent)
        assertEquals(0x10, state.leftPercent)
        assertEquals(0x20, state.rightPercent)
        assertEquals(0x30, state.casePercent)
        assertTrue(state.isCharging)
    }

    @Test
    fun batteryPushCommandDecodesLikeResponse() {
        val state = HuaweiFreebudsSppCodec.parseBatteryFrame(
            hex("5A 00 14 00 01 27 01 01 40 02 03 10 20 30 03 03 00 01 00 04 02 14 0A E0 04"),
        )!!
        assertEquals(0x40, state.globalPercent)
        assertEquals(0x10, state.leftPercent)
        assertEquals(0x20, state.rightPercent)
        assertEquals(0x30, state.casePercent)
        assertTrue(state.isCharging)
    }

    @Test
    fun legacyBatteryResponseHasNoComponents() {
        val state = HuaweiFreebudsSppCodec.parseBatteryFrame(
            hex("5A 00 09 00 01 08 01 01 40 03 01 00 ED 2E"),
        )!!
        assertEquals(0x40, state.globalPercent)
        assertNull(state.leftPercent)
        assertNull(state.rightPercent)
        assertNull(state.casePercent)
        assertTrue(!state.isCharging)
    }

    @Test
    fun zeroPercentComponentsDecodeAsNotConnected() {
        val state = HuaweiFreebudsSppCodec.parseBatteryFrame(
            hex("5A 00 14 00 01 08 01 01 40 02 03 00 20 00 03 03 00 01 00 04 02 14 0A 8E AA"),
        )!!
        assertNull(state.leftPercent)
        assertEquals(0x20, state.rightPercent)
        assertNull(state.casePercent)
    }

    @Test
    fun nonBatteryFrameReturnsNull() {
        assertNull(
            HuaweiFreebudsSppCodec.parseBatteryFrame(
                hex("5A 00 07 00 2B 2A 01 02 01 01 36 21"),
            ),
        )
    }

    /**
     * Parameter 1 of a `2B 2A` report is `[level, mode]` — the mode byte is the second byte
     * (reference client `anc.py`: `active_mode = data[1]`). Vectors below are constructed with
     * that layout; CRCs are verified against the reference client's crc16_xmodem.
     */
    @Test
    fun noiseStateDecodesModesAndLevels() {
        assertEquals(
            HuaweiFreebudsSppCodec.NoiseState(NoiseMode.OFF, null),
            HuaweiFreebudsSppCodec.parseNoiseState(hex("5A 00 07 00 2B 2A 01 02 00 00 15 31")),
        )
        assertEquals(
            HuaweiFreebudsSppCodec.NoiseState(NoiseMode.ANC, 0),
            HuaweiFreebudsSppCodec.parseNoiseState(hex("5A 00 07 00 2B 2A 01 02 00 01 05 10")),
        )
        assertEquals(
            HuaweiFreebudsSppCodec.NoiseState(NoiseMode.ANC, 1),
            HuaweiFreebudsSppCodec.parseNoiseState(hex("5A 00 07 00 2B 2A 01 02 01 01 36 21")),
        )
        assertEquals(
            HuaweiFreebudsSppCodec.NoiseState(NoiseMode.ANC, 2),
            HuaweiFreebudsSppCodec.parseNoiseState(hex("5A 00 07 00 2B 2A 01 02 02 01 63 72")),
        )
        assertEquals(
            HuaweiFreebudsSppCodec.NoiseState(NoiseMode.ANC, 3),
            HuaweiFreebudsSppCodec.parseNoiseState(hex("5A 00 07 00 2B 2A 01 02 03 01 50 43")),
        )
        assertEquals(
            HuaweiFreebudsSppCodec.NoiseState(NoiseMode.TRANSPARENCY, 1),
            HuaweiFreebudsSppCodec.parseNoiseState(hex("5A 00 07 00 2B 2A 01 02 01 02 06 42")),
        )
        assertEquals(
            HuaweiFreebudsSppCodec.NoiseState(NoiseMode.TRANSPARENCY, 2),
            HuaweiFreebudsSppCodec.parseNoiseState(hex("5A 00 07 00 2B 2A 01 02 02 02 53 11")),
        )
    }

    @Test
    fun parseFrameRejectsMalformedBytes() {
        assertNull(HuaweiFreebudsSppCodec.parseFrame(ByteArray(0)))
        assertNull(HuaweiFreebudsSppCodec.parseFrame(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00)))
        // Truncated frame: length claims 9 but only 10 bytes present (needs 14).
        assertNull(
            HuaweiFreebudsSppCodec.parseFrame(
                hex("5A 00 09 00 01 08 01 00 02 00 03"),
            ),
        )
        // Wrong fixed byte at offset 3.
        assertNull(
            HuaweiFreebudsSppCodec.parseFrame(
                hex("5A 00 09 01 01 08 01 00 02 00 03 00 FB B9"),
            ),
        )
        val badCrc = HuaweiFreebudsSppCodec.queryBattery.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }
        assertNull(HuaweiFreebudsSppCodec.parseFrame(badCrc))
    }

    @Test
    fun decoderSplitsCompleteFrames() {
        val decoder = HuaweiFreebudsSppCodec.Decoder()
        val frames = decoder.offer(
            hex("5A 00 07 00 2B 2A 01 02 01 01 36 21") +
                hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"),
        )
        assertEquals(2, frames.size)
        assertArrayEquals(hex("5A 00 07 00 2B 2A 01 02 01 01 36 21"), frames[0])
        assertArrayEquals(hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"), frames[1])
    }

    @Test
    fun decoderBuffersPartialFramesAcrossOffers() {
        val decoder = HuaweiFreebudsSppCodec.Decoder()
        val full = hex("5A 00 07 00 2B 2A 01 02 01 01 36 21")
        assertEquals(0, decoder.offer(full.copyOfRange(0, 5)).size)
        val frames = decoder.offer(full.copyOfRange(5, full.size))
        assertEquals(1, frames.size)
        assertArrayEquals(full, frames[0])
    }

    @Test
    fun decoderDropsLeadingNoiseAndResyncs() {
        val decoder = HuaweiFreebudsSppCodec.Decoder()
        val frames = decoder.offer(
            byteArrayOf(0x01, 0x02, 0x03) + hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"),
        )
        assertEquals(1, frames.size)
        assertArrayEquals(hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"), frames[0])
    }

    @Test
    fun decoderConsumesSmallKeepaliveFrames() {
        val decoder = HuaweiFreebudsSppCodec.Decoder()
        // Length 3 frame (keepalive, no command payload beyond CRC) followed by a real frame.
        val frames = decoder.offer(
            hex("5A 00 03 00 01 02 FF 00") + hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"),
        )
        assertEquals(1, frames.size)
        assertArrayEquals(hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"), frames[0])
    }

    @Test
    fun decoderSkipsMalformedLengthAndResyncs() {
        val decoder = HuaweiFreebudsSppCodec.Decoder()
        val frames = decoder.offer(
            hex("5A 00 00 00 01 02 03 04") + hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"),
        )
        assertEquals(1, frames.size)
        assertArrayEquals(hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"), frames[0])
    }

    @Test
    fun decoderResetClearsBuffer() {
        val decoder = HuaweiFreebudsSppCodec.Decoder()
        decoder.offer(hex("5A 00 07 00 2B 2A 01 02"))
        decoder.reset()
        val frames = decoder.offer(hex("5A 00 07 00 2B 2A 01 02 00 00 15 31"))
        assertEquals(1, frames.size)
    }

    @Test
    fun decoderEmptyInputReturnsNothing() {
        assertEquals(0, HuaweiFreebudsSppCodec.Decoder().offer(ByteArray(0)).size)
    }

    @Test
    fun crcMatchesXmodemCheckValue() {
        // CRC-16/XMODEM reference: crc16("123456789") == 0x31C3.
        assertEquals(0x31C3, HuaweiFreebudsSppCodec.crc16Xmodem("123456789".toByteArray()))
    }

    private fun hex(value: String): ByteArray {
        val compact = value.filterNot(Char::isWhitespace)
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.hex(): String = joinToString(" ") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
    }
}
