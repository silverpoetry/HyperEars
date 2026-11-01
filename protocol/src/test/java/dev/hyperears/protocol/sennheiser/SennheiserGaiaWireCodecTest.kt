package dev.hyperears.protocol.sennheiser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SennheiserGaiaWireCodecTest {
    @Test
    fun batteryQueryMatchesCapturedGaiaFrame() {
        assertHex("FF 03 00 00 04 95 06 03", SennheiserGaiaWireCodec.queryBattery)
    }

    @Test
    fun noiseModeQueriesUseTheGetVariantOfEachSetCommand() {
        assertHex("FF 03 00 00 04 95 1A 01", SennheiserGaiaWireCodec.queryCombinedNoiseMode)
        assertHex("FF 03 00 00 04 95 1A 05", SennheiserGaiaWireCodec.queryAncToggle)
        assertHex("FF 03 00 00 04 95 18 05", SennheiserGaiaWireCodec.queryTransparencyToggle)
    }

    @Test
    fun modelAndVersionQueriesMatchCapturedGaiaFrames() {
        assertHex("FF 03 00 00 04 95 12 06", SennheiserGaiaWireCodec.queryModelId)
        assertHex("FF 03 00 00 00 1D 00 00", SennheiserGaiaWireCodec.queryQualcommApiVersion)
    }

    @Test
    fun ancToggleWritesOneBytePayload() {
        assertHex("FF 03 00 01 04 95 1A 04 01", SennheiserGaiaWireCodec.setAncToggle(true))
        assertHex("FF 03 00 01 04 95 1A 04 00", SennheiserGaiaWireCodec.setAncToggle(false))
    }

    @Test
    fun transparencyToggleUsesTheStandaloneVendorCommand() {
        assertHex("FF 03 00 01 04 95 18 04 01", SennheiserGaiaWireCodec.setTransparencyToggle(true))
        assertHex("FF 03 00 01 04 95 18 04 00", SennheiserGaiaWireCodec.setTransparencyToggle(false))
    }

    @Test
    fun combinedNoiseModeWriteCarriesSelectorAndState() {
        assertHex(
            "FF 03 00 02 04 95 1A 00 03 01",
            SennheiserGaiaWireCodec.setCombinedNoiseMode(
                SennheiserGaiaWireCodec.NOISE_MODE_ADAPTIVE,
                SennheiserGaiaWireCodec.STATE_ON,
            ),
        )
        assertHex(
            "FF 03 00 02 04 95 1A 00 01 02",
            SennheiserGaiaWireCodec.setCombinedNoiseMode(
                SennheiserGaiaWireCodec.NOISE_MODE_ANTI_WIND,
                ANTI_WIND_AUTO,
            ),
        )
    }

    @Test
    fun notificationRegistrationCarriesOneFeatureId() {
        assertHex(
            "FF 03 00 01 04 95 00 07 03",
            SennheiserGaiaWireCodec.registerNotification(SennheiserGaiaWireCodec.FEATURE_BATTERY),
        )
        assertHex(
            "FF 03 00 01 04 95 00 07 0D",
            SennheiserGaiaWireCodec.registerNotification(SennheiserGaiaWireCodec.FEATURE_ANC),
        )
    }

    @Test
    fun parsesCapturedBatteryResponse() {
        val frames = decode("FF 03 00 01 04 95 07 03 2A")

        assertEquals(1, frames.size)
        assertEquals(SennheiserGaiaWireCodec.FrameType.RESPONSE, frames.single().type)
        assertEquals(0x0603, frames.single().baseCommandId)
        assertEquals(42, SennheiserGaiaWireCodec.parseBatteryPercent(frames.single().payload))
    }

    @Test
    fun parsesCapturedCombinedNoiseModeResponse() {
        val frames = decode("FF 03 00 06 04 95 1B 01 01 02 02 00 03 01")

        assertEquals(1, frames.size)
        assertEquals(0x1A01, frames.single().baseCommandId)
        assertEquals(
            listOf(
                SennheiserGaiaWireCodec.NoiseModeEntry(ANTI_WIND, ANTI_WIND_AUTO),
                SennheiserGaiaWireCodec.NoiseModeEntry(COMFORT, 0),
                SennheiserGaiaWireCodec.NoiseModeEntry(ADAPTIVE, 1),
            ),
            SennheiserGaiaWireCodec.parseCombinedNoiseMode(frames.single().payload),
        )
    }

    @Test
    fun rejectsCombinedNoiseModePayloadWithWrongLength() {
        assertNull(SennheiserGaiaWireCodec.parseCombinedNoiseMode(hex("01 02 02 00")))
        assertNull(SennheiserGaiaWireCodec.parseCombinedNoiseMode(ByteArray(0)))
    }

    @Test
    fun parsesCapturedModelIdResponse() {
        val frames = decode("FF 03 00 0D 04 95 13 06 4D 4D 4D 4D 42 54 20 42 6C 61 63 6B 00")

        assertEquals(0x1206, frames.single().baseCommandId)
        assertEquals("MMMMBT Black", SennheiserGaiaWireCodec.parseModelId(frames.single().payload))
    }

    @Test
    fun modelIdWithoutTerminatorKeepsWholePayload() {
        assertEquals(
            "M4AEBT",
            SennheiserGaiaWireCodec.parseModelId(hex("4D 34 41 45 42 54")),
        )
        assertNull(SennheiserGaiaWireCodec.parseModelId(hex("00")))
    }

    @Test
    fun derivesFrameTypeAndBaseCommandFromEveryDocumentedEncoding() {
        assertFrame(0x1A04, SennheiserGaiaWireCodec.FrameType.COMMAND, 0x1A04)
        assertFrame(0x1B04, SennheiserGaiaWireCodec.FrameType.RESPONSE, 0x1A04)
        assertFrame(0x1A85, SennheiserGaiaWireCodec.FrameType.NOTIFICATION, 0x1A05)
        assertFrame(0x1B84, SennheiserGaiaWireCodec.FrameType.ERROR, 0x1A04)
        assertFrame(0x1A81, SennheiserGaiaWireCodec.FrameType.NOTIFICATION, 0x1A01)
        assertFrame(0x0683, SennheiserGaiaWireCodec.FrameType.NOTIFICATION, 0x0603)
        assertFrame(0x1B01, SennheiserGaiaWireCodec.FrameType.RESPONSE, 0x1A01)
        assertFrame(0x1306, SennheiserGaiaWireCodec.FrameType.RESPONSE, 0x1206)
        assertFrame(0x1904, SennheiserGaiaWireCodec.FrameType.RESPONSE, 0x1804)
        assertFrame(0x1885, SennheiserGaiaWireCodec.FrameType.NOTIFICATION, 0x1805)
    }

    @Test
    fun acceptsACombinedWriteAndItsNotificationInOneRead() {
        val frames = decode(
            "FF 03 00 01 04 95 1B 04 01" +
                "FF 03 00 01 04 95 1A 85 01",
        )

        assertEquals(2, frames.size)
        assertEquals(SennheiserGaiaWireCodec.FrameType.RESPONSE, frames[0].type)
        assertEquals(SennheiserGaiaWireCodec.FrameType.NOTIFICATION, frames[1].type)
        assertArrayEquals(hex("01"), frames[1].payload)
    }

    @Test
    fun reassemblesFrameSplitAcrossReads() {
        val frame = hex("FF 03 00 06 04 95 1B 01 01 02 02 00 03 01")
        val reader = SennheiserGaiaFrameReader()

        assertTrue(reader.offer(frame.copyOfRange(0, 3)).isEmpty())
        assertTrue(reader.offer(frame.copyOfRange(3, 10)).isEmpty())

        val frames = reader.offer(frame.copyOfRange(10, frame.size))
        assertEquals(1, frames.size)
        assertEquals(0x1A01, frames.single().baseCommandId)
        assertEquals(6, frames.single().payload.size)
    }

    @Test
    fun skipsLeadingNoiseBeforeTheMarker() {
        val frames = decode("AA BB CC FF 03 00 01 04 95 07 03 2A")

        assertEquals(1, frames.size)
        assertEquals(42, SennheiserGaiaWireCodec.parseBatteryPercent(frames.single().payload))
    }

    @Test
    fun resynchronisesAfterAnOversizedLengthField() {
        val frames = decode(
            "FF 03 FF FF 04 95 06 03" +
                "FF 03 00 01 04 95 07 03 2A",
        )

        assertEquals(1, frames.size)
        assertEquals(42, SennheiserGaiaWireCodec.parseBatteryPercent(frames.single().payload))
    }

    @Test
    fun keepsTrailingMarkerByteForTheNextRead() {
        val reader = SennheiserGaiaFrameReader()
        val frame = hex("FF 03 00 01 04 95 07 03 2A")

        assertTrue(reader.offer(hex("AA FF")).isEmpty())
        val frames = reader.offer(frame.copyOfRange(1, frame.size))

        assertEquals(1, frames.size)
        assertEquals(42, SennheiserGaiaWireCodec.parseBatteryPercent(frames.single().payload))
    }

    @Test
    fun resetDropsAFragmentedFrame() {
        val frame = hex("FF 03 00 01 04 95 07 03 2A")
        val reader = SennheiserGaiaFrameReader()

        assertTrue(reader.offer(frame.copyOfRange(0, 5)).isEmpty())
        reader.reset()
        assertTrue(reader.offer(frame.copyOfRange(5, frame.size)).isEmpty())
    }

    @Test
    fun rejectsOutOfRangeFields() {
        assertIllegalArgument { SennheiserGaiaWireCodec.encode(0x10000, 0x0603) }
        assertIllegalArgument { SennheiserGaiaWireCodec.encode(0x0495, 0x10000) }
        assertIllegalArgument { SennheiserGaiaWireCodec.registerNotification(0x100) }
        assertIllegalArgument { SennheiserGaiaWireCodec.setCombinedNoiseMode(0x100, 0) }
        assertIllegalArgument { SennheiserGaiaWireCodec.setCombinedNoiseMode(0, 0x100) }
        assertIllegalArgument {
            SennheiserGaiaWireCodec.encode(
                0x0495,
                0x0603,
                ByteArray(SennheiserGaiaWireCodec.MAX_PAYLOAD_SIZE + 1),
            )
        }
    }

    @Test
    fun rejectsBatteryPercentAboveOneHundred() {
        assertNull(SennheiserGaiaWireCodec.parseBatteryPercent(hex("65")))
        assertNull(SennheiserGaiaWireCodec.parseBatteryPercent(ByteArray(0)))
        assertEquals(100, SennheiserGaiaWireCodec.parseBatteryPercent(hex("64")))
    }

    private fun assertFrame(
        commandId: Int,
        expectedType: SennheiserGaiaWireCodec.FrameType,
        expectedBase: Int,
    ) {
        val frame = SennheiserGaiaFrame(
            vendorId = SennheiserGaiaWireCodec.VENDOR_SENNHEISER,
            commandId = commandId,
            payload = ByteArray(0),
        )
        assertEquals(expectedType, frame.type)
        assertEquals(expectedBase, frame.baseCommandId)
    }

    private fun assertHex(expected: String, actual: ByteArray) {
        assertArrayEquals(hex(expected), actual)
    }

    private fun decode(value: String): List<SennheiserGaiaFrame> =
        SennheiserGaiaFrameReader().offer(hex(value))

    private fun hex(value: String): ByteArray = SennheiserGaiaWireCodec.hex(value)

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private companion object {
        const val ANTI_WIND = SennheiserGaiaWireCodec.NOISE_MODE_ANTI_WIND
        const val COMFORT = SennheiserGaiaWireCodec.NOISE_MODE_COMFORT
        const val ADAPTIVE = SennheiserGaiaWireCodec.NOISE_MODE_ADAPTIVE
        const val ANTI_WIND_AUTO = SennheiserGaiaWireCodec.STATE_ANTI_WIND_AUTO
    }
}
