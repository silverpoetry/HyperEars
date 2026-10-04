package dev.hyperears.protocol.samsung

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungBudsCodecTest {
    @Test
    fun decodesCapturedBuds2ProExtendedStatus() {
        val raw = hex(
            "FD 31 00 61 0D 04 64 64 01 00 33 58 00 01 BF 22 00 01 46 01 46 01 00 00 " +
                "03 35 00 03 00 10 01 01 01 01 32 03 01 01 01 00 0C AE 01 00 03 00 01 00 " +
                "01 01 B6 37 DD",
        )
        val frame = SamsungBudsCodec.parseFrame(raw)
        assertNotNull(frame)
        val state = SamsungBudsCodec.parseExtendedStatus(requireNotNull(frame))
        assertNotNull(state)
        requireNotNull(state)
        assertEquals(100, state.battery.left)
        assertEquals(100, state.battery.right)
        assertEquals(88, state.battery.case)
        assertEquals(SamsungBudsCodec.NoiseMode.OFF, state.noiseMode)
        assertEquals(3, state.settings.ambientVolume)
        assertTrue(state.settings.voiceDetectEnabled)
        assertFalse(state.settings.touchpadLocked)
        assertEquals(2, state.settings.touchHoldLeftAction)
        assertEquals(2, state.settings.touchHoldRightAction)
        assertTrue(state.settings.extraHighAmbientEnabled)
    }

    @Test
    fun packetRoundTripsAndRejectsCorruptCrc() {
        val packet = SamsungBudsCodec.noiseModeCommand(SamsungBudsCodec.NoiseMode.AMBIENT)
        val frame = SamsungBudsCodec.parseFrame(packet)
        assertEquals(SamsungBudsCodec.NOISE_CONTROLS, frame?.id)
        assertArrayEquals(byteArrayOf(2), frame?.payload)

        packet[4] = 1
        assertEquals(null, SamsungBudsCodec.parseFrame(packet))
    }

    @Test
    fun streamingDecoderBuffersAndSplitsFrames() {
        val one = SamsungBudsCodec.boolCommand(SamsungBudsCodec.SET_SIDETONE, true)
        val two = SamsungBudsCodec.boolCommand(SamsungBudsCodec.SET_DETECT_CONVERSATIONS, false)
        val decoder = SamsungBudsCodec.Decoder()
        assertTrue(decoder.offer(one.copyOfRange(0, 3)).isEmpty())
        val frames = decoder.offer(one.copyOfRange(3, one.size) + two)
        assertEquals(2, frames.size)
        assertArrayEquals(one, frames[0])
        assertArrayEquals(two, frames[1])
    }

    @Test
    fun parsesUniversalAcknowledgement() {
        val frame = requireNotNull(
            SamsungBudsCodec.parseFrame(
                SamsungBudsCodec.packet(
                    SamsungBudsCodec.UNIVERSAL_ACK,
                    byteArrayOf(SamsungBudsCodec.SET_DETECT_CONVERSATIONS.toByte(), 1),
                ),
            ),
        )

        val ack = SamsungBudsCodec.parseAcknowledgement(frame)

        assertEquals(SamsungBudsCodec.SET_DETECT_CONVERSATIONS, ack?.commandId)
        assertArrayEquals(byteArrayOf(1), ack?.parameters)
    }

    @Test
    fun encodesTouchHoldActions() {
        val frame = requireNotNull(
            SamsungBudsCodec.parseFrame(SamsungBudsCodec.touchpadOptionsCommand(2, 3)),
        )
        assertEquals(SamsungBudsCodec.SET_TOUCHPAD_OPTION, frame.id)
        assertArrayEquals(byteArrayOf(2, 3), frame.payload)
    }

    @Test
    fun noiseCyclePairsUseLeftThenRightAncAmbientOffFlags() {
        val flags = mapOf(6 to byteArrayOf(1, 1, 0), 5 to byteArrayOf(1, 0, 1), 3 to byteArrayOf(0, 1, 1))
        flags.forEach { (left, l) -> flags.forEach { (right, r) ->
            val frame = SamsungBudsCodec.parseFrame(SamsungBudsCodec.touchHoldNoiseCyclesCommand(left, right))!!
            assertEquals(0x79, frame.id)
            assertArrayEquals(l + r, frame.payload)
            val payload = ByteArray(35)
            payload[11] = 0x22
            payload[21] = ((left shl 4) or right).toByte()
            val state = SamsungBudsCodec.parseExtendedStatus(SamsungBudsCodec.Frame(0x61, payload))!!.settings
            assertEquals(left, state.touchHoldLeftCycle)
            assertEquals(right, state.touchHoldRightCycle)
        } }
        assertEquals(null, SamsungBudsCodec.parseNoiseCycle(0))
        assertEquals(null, SamsungBudsCodec.parseNoiseCycle(7))
    }

    @Test
    fun sharedServiceFallbackPublishesStableBatteryAndNoiseWithoutModelSettings() {
        val raw = hex(
            "FD 31 00 61 0D 04 64 64 01 00 33 58 00 01 BF 22 00 01 46 01 46 01 00 00 " +
                "03 35 00 03 00 10 01 01 01 01 32 03 01 01 01 00 0C AE 01 00 03 00 01 00 " +
                "01 01 B6 37 DD",
        )
        val frame = SamsungBudsCodec.parseFrame(raw)!!
        assertEquals(100, SamsungBudsCodec.parseBattery(frame, SamsungBudsCodec.Model.UNKNOWN)?.left)
        assertEquals(SamsungBudsCodec.NoiseMode.OFF,
            SamsungBudsCodec.parseNoiseMode(frame))
        assertEquals(null, SamsungBudsCodec.parseExtendedStatus(frame, SamsungBudsCodec.Model.UNKNOWN))
    }

    @Test
    fun corruptFrameLengthDoesNotConsumeFollowingValidFrame() {
        val good = SamsungBudsCodec.boolCommand(SamsungBudsCodec.SET_SIDETONE, true)
        // Invalid candidate overlaps the next valid frame, including its start marker.
        val corrupt = byteArrayOf(0xFD.toByte(), 8, 0, 0)
        val decoder = SamsungBudsCodec.Decoder()
        val frames = decoder.offer(corrupt + good)
        assertEquals(1, frames.size)
        assertArrayEquals(good, frames.single())
    }

    @Test
    fun invalidExtendedSettingsAreRejectedWithoutClamping() {
        val invalidFields = listOf(
            9 to 6, 9 to 255,
            11 to 0x02, 11 to 0x20, 11 to 0x52, 11 to 0x25,
            23 to 4, 23 to 255, 45 to 2,
        )
        invalidFields.forEach { (index, value) ->
            val p = legalSettingsPayload().apply { this[index] = value.toByte() }
            val frame = SamsungBudsCodec.Frame(0x61, p)
            assertEquals("Invalid setting at index $index", null, SamsungBudsCodec.parseExtendedStatus(frame))
            assertNotNull(SamsungBudsCodec.parseBattery(frame))
            assertEquals(SamsungBudsCodec.NoiseMode.OFF, SamsungBudsCodec.parseNoiseMode(frame))
        }
    }

    @Test
    fun validSettingBoundariesArePreserved() {
        (0..5).forEach { eq -> (1..4).forEach { action -> (0..3).forEach { ambient ->
            val p = legalSettingsPayload().apply {
                this[9] = eq.toByte()
                this[11] = ((action shl 4) or action).toByte()
                this[23] = ambient.toByte()
            }
            val settings = SamsungBudsCodec.parseExtendedStatus(SamsungBudsCodec.Frame(0x61, p))!!.settings
            assertEquals(eq != 0, settings.equalizerEnabled)
            assertEquals(if (eq == 0) 0 else eq - 1, settings.equalizerPreset)
            assertEquals(action, settings.touchHoldLeftAction)
            assertEquals(action, settings.touchHoldRightAction)
            assertEquals(ambient, settings.ambientVolume)
        } } }
    }

    @Test
    fun optionalExtraHighAmbientFieldIsModelAndRevisionSpecific() {
        SamsungBudsCodec.Model.entries.filter { it != SamsungBudsCodec.Model.UNKNOWN }.forEach { model ->
            val settings = SamsungBudsCodec.parseExtendedStatus(
                SamsungBudsCodec.Frame(0x61, legalSettingsPayload().apply { this[45] = 1 }), model,
            )!!.settings
            assertEquals(model == SamsungBudsCodec.Model.BUDS2_PRO, settings.extraHighAmbientSupported)
            assertEquals(model == SamsungBudsCodec.Model.BUDS2_PRO, settings.extraHighAmbientEnabled)
        }
    }

    private fun legalSettingsPayload() = ByteArray(46).apply {
        this[0] = 13
        this[11] = 0x22
    }

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }.toByteArray()
}
