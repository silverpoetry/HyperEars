package dev.hyperears.protocol.flos2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloS2WireCodecTest {
    @Test
    fun requestAndControlFramesMatchCapturedWireBytes() {
        assertEquals("AE 66 FF 00 D8 E6", FloS2WireCodec.queryAllStatus.hex())
        assertEquals(
            "AE 67 03 01 00 5E 96",
            FloS2WireCodec.setSoundEffect(FloS2WireCodec.SoundEffect.STANDARD).hex(),
        )
        assertEquals(
            "AE 67 03 01 01 7F 86",
            FloS2WireCodec.setSoundEffect(FloS2WireCodec.SoundEffect.SWIMMING).hex(),
        )
        assertEquals(
            "AE 67 03 01 02 1C B6",
            FloS2WireCodec.setSoundEffect(FloS2WireCodec.SoundEffect.VOCALS).hex(),
        )
        assertEquals(
            "AE 67 03 01 03 3D A6",
            FloS2WireCodec.setSoundEffect(FloS2WireCodec.SoundEffect.ENHANCED).hex(),
        )
    }

    @Test
    fun fullStatusResponseDecodesVerifiedFields() {
        val frames = FloS2WireCodec.Decoder().offer(STATUS_RESPONSE.hexBytes())

        assertEquals(1, frames.size)
        val status = requireNotNull(FloS2WireCodec.parseStatus(frames[0]))
        assertEquals(70, status.batteryPercent)
        assertEquals(FloS2WireCodec.SoundEffect.STANDARD, status.soundEffect)
        assertEquals("1.6.6", status.firmwareVersion)
        assertEquals("1.0.0", status.hardwareVersion)
        assertEquals("2.2.6", status.mcuFirmwareVersion)
        assertEquals("Flo S2", status.deviceName)
        assertEquals("E02003TEST000001", status.serial)
        assertFalse(FloS2WireCodec.isSoundEffectAck(frames[0]))
    }

    @Test
    fun statusResponseRejectsWrongLengthOrType() {
        val frame = FloS2WireCodec.Decoder().offer(STATUS_RESPONSE.hexBytes()).single()

        assertNull(FloS2WireCodec.parseStatus(frame.copy(payload = frame.payload.copyOf(71))))
        assertNull(
            FloS2WireCodec.parseStatus(
                FloS2WireCodec.Frame(
                    primaryType = FloS2WireCodec.PRIMARY_SPORT,
                    subType = 0x23,
                    payload = frame.payload,
                ),
            ),
        )
    }

    @Test
    fun batteryPushDecodesPercentAndChargingState() {
        val decoder = FloS2WireCodec.Decoder()
        val at70 = decoder.offer("AE 66 E0 02 46 00 22 F6".hexBytes()).single()
        val at60 = decoder.offer("AE 66 E0 02 3C 00 B0 11".hexBytes()).single()
        val charging = decoder.offer("AE 66 E0 02 64 01 87 86".hexBytes()).single()

        assertEquals(FloS2WireCodec.BatteryReport(70, 0), FloS2WireCodec.parseBatteryReport(at70))
        assertEquals(FloS2WireCodec.BatteryReport(60, 0), FloS2WireCodec.parseBatteryReport(at60))
        // Captured plug-in frame: the last push before the earbud powers off carries charging = 1.
        assertEquals(
            FloS2WireCodec.BatteryReport(100, 1),
            FloS2WireCodec.parseBatteryReport(charging),
        )
        // The vendor handler clamps the percentage byte to 100 instead of dropping the frame.
        assertEquals(
            FloS2WireCodec.BatteryReport(100, 0),
            FloS2WireCodec.parseBatteryReport(at70.copy(payload = byteArrayOf(0x7F, 0x00))),
        )
        // A charging byte outside the vendor enum {0, 1, 2} leaves the state unparsed.
        assertEquals(
            FloS2WireCodec.BatteryReport(70, null),
            FloS2WireCodec.parseBatteryReport(at70.copy(payload = byteArrayOf(0x46, 0x05))),
        )
    }

    @Test
    fun batteryPushToleratesDeviatingPayloadLengths() {
        val frame = FloS2WireCodec.Decoder().offer("AE 66 E0 02 46 00 22 F6".hexBytes()).single()

        // No payload carries no battery at all.
        assertNull(FloS2WireCodec.parseBatteryReport(frame.copy(payload = ByteArray(0))))
        // The percentage byte still decodes without the charging byte.
        assertEquals(
            FloS2WireCodec.BatteryReport(70, null),
            FloS2WireCodec.parseBatteryReport(frame.copy(payload = byteArrayOf(0x46))),
        )
        // Bytes beyond the two documented ones are ignored, as in the vendor handler.
        assertEquals(
            FloS2WireCodec.BatteryReport(70, 0),
            FloS2WireCodec.parseBatteryReport(
                frame.copy(payload = byteArrayOf(0x46, 0x00, 0x2A)),
            ),
        )
    }

    @Test
    fun soundEffectAckIsRecognizedButCarriesNoState() {
        val frame = FloS2WireCodec.Decoder().offer("AE 67 83 01 00 04 AD".hexBytes()).single()

        assertTrue(FloS2WireCodec.isSoundEffectAck(frame))
        assertNull(FloS2WireCodec.parseStatus(frame))
        assertNull(FloS2WireCodec.parseBatteryReport(frame))
    }

    @Test
    fun decoderReassemblesFragmentedAndCoalescedStreamsAndDropsBadCrc() {
        val status = STATUS_RESPONSE.hexBytes()
        val battery = "AE 66 E0 02 46 00 22 F6".hexBytes()
        val decoder = FloS2WireCodec.Decoder()

        assertEquals(emptyList<FloS2WireCodec.Frame>(), decoder.offer(status.copyOfRange(0, 9)))
        assertEquals(emptyList<FloS2WireCodec.Frame>(), decoder.offer(status.copyOfRange(9, 40)))

        val both = decoder.offer(status.copyOfRange(40, status.size) + battery)
        assertEquals(2, both.size)
        assertEquals(FloS2WireCodec.SUB_QUERY_ALL_STATUS, both[0].subType)
        assertEquals(FloS2WireCodec.SUB_BATTERY_REPORT, both[1].subType)

        val corrupt = STATUS_RESPONSE.hexBytes().also { it[it.size - 2] = 0x00 }
        assertEquals(emptyList<FloS2WireCodec.Frame>(), decoder.offer(corrupt))
        decoder.reset()
        assertEquals(1, decoder.offer(battery).size)
    }

    @Test
    fun leadingGarbageBeforeTheMarkerIsDiscarded() {
        val decoder = FloS2WireCodec.Decoder()
        val noisy = byteArrayOf(0x01, 0x02, 0x03) + "AE 66 E0 02 3C 00 B0 11".hexBytes()

        val frames = decoder.offer(noisy)

        assertEquals(1, frames.size)
        assertEquals(60, FloS2WireCodec.parseBatteryReport(frames.single())?.batteryPercent)
    }

    private companion object {
        /**
         * Real full-status response captured on 2026-10-24 (battery 70%, sound effect standard).
         * The MAC is replaced by the synthetic address 66:55:44:33:22:11 and the serial number by
         * a placeholder; the CRC was recomputed for the anonymized bytes.
         */
        val STATUS_RESPONSE =
            "AE 66 FF 48 A3 8D DB 6A E0 01 31 2E 36 2E 36 31 2E 30 2E 30 32 2E 32 2E 36 FF FF FF FF " +
                "46 00 38 00 72 74 00 01 1A B4 3C 46 6C 6F 20 53 32 66 55 44 33 22 11 32 00 02 00 00 " +
                "01 01 45 30 32 30 30 33 54 45 53 54 30 30 30 30 30 31 00 3D 08"
    }

    private fun String.hexBytes(): ByteArray = trim()
        .split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
