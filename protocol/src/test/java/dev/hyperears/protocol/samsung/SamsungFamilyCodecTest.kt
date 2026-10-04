package dev.hyperears.protocol.samsung

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungFamilyCodecTest {
    @Test
    fun liveUsesLegacyLockAndBooleanAncWithoutAmbientOrChargingBits() {
        val frame = status(SamsungBudsCodec.Model.BUDS_LIVE)
        val state = SamsungBudsCodec.parseExtendedStatus(frame, SamsungBudsCodec.Model.BUDS_LIVE)!!
        assertFalse(state.settings.touchpadLocked)
        assertFalse(state.settings.touchGesturesSupported)
        assertFalse(state.settings.outsideDoubleTapSupported)
        assertFalse(state.settings.optionalSettingsSupported)
        assertNull(state.settings.touchHoldLeftCycle)
        assertEquals(SamsungBudsCodec.NoiseMode.ANC, state.noiseMode)
        val lock = SamsungBudsCodec.parseFrame(SamsungBudsCodec.touchpadLockCommand(
            true, state.settings, SamsungBudsCodec.Model.BUDS_LIVE,
        ))!!
        assertArrayEquals(byteArrayOf(1), lock.payload)
        val anc = SamsungBudsCodec.parseFrame(SamsungBudsCodec.noiseModeCommand(
            SamsungBudsCodec.NoiseMode.ANC, SamsungBudsCodec.Model.BUDS_LIVE,
        ))!!
        assertEquals(0x98, anc.id)
        assertArrayEquals(byteArrayOf(1), anc.payload)
        assertNull(SamsungBudsCodec.parseNoiseMode(SamsungBudsCodec.Frame(0x9B, byteArrayOf(2)), SamsungBudsCodec.Model.BUDS_LIVE))
        assertFalse(SamsungBudsCodec.parseBattery(SamsungBudsCodec.Frame(0x60,
            byteArrayOf(1, 80, 90, 1, 0, 0, 70, 0x15)), SamsungBudsCodec.Model.BUDS_LIVE)!!.leftCharging)
    }

    @Test
    fun proUsesItsOwnOutsideDoubleTapOffsetAndRejectsMissingEvidence() {
        val model = SamsungBudsCodec.Model.BUDS_PRO
        val frame = status(model)
        val state = SamsungBudsCodec.parseExtendedStatus(frame, model)!!.settings
        assertTrue(state.outsideDoubleTapSupported)
        assertTrue(state.outsideDoubleTapEnabled)
        assertFalse(state.voiceDetectSupported)
        assertFalse(state.touchGesturesSupported)
        assertNull(state.touchHoldLeftCycle)
        val old = SamsungBudsCodec.parseExtendedStatus(frame.copy(payload = frame.payload.copyOf(28).apply { this[0] = 6 }), model)!!
        assertFalse(old.settings.outsideDoubleTapSupported)
        assertFalse(old.settings.outsideDoubleTapEnabled)
    }

    @Test
    fun allNewerProfilesExposeCommonHoldButNotBuds2ProCyclesOrOptionalControls() {
        SamsungBudsCodec.Model.entries.filter { it.commonHoldOnly }.forEach { model ->
            val settings = SamsungBudsCodec.parseExtendedStatus(status(model), model)!!.settings
            assertEquals(2, settings.touchHoldLeftAction)
            assertTrue(settings.touchHoldEnabled)
            assertFalse(settings.touchGesturesSupported)
            assertFalse(settings.outsideDoubleTapSupported)
            assertFalse(settings.optionalSettingsSupported)
            assertFalse(settings.voiceDetectSupported)
            assertNull(settings.touchHoldLeftCycle)
            assertNull(settings.touchHoldRightCycle)
        }
    }

    @Test
    fun buds4ProAmbientFourAndCustomEqualizerDoNotSuppressCommonHold() {
        val frame = status(SamsungBudsCodec.Model.BUDS4_PRO)
        frame.payload[9] = 6
        frame.payload[23] = 4
        val settings = SamsungBudsCodec.parseExtendedStatus(frame, SamsungBudsCodec.Model.BUDS4_PRO)!!.settings
        assertEquals(4, settings.ambientVolume)
        assertFalse(settings.optionalSettingsSupported)
    }

    @Test
    fun plainBuds3AndBuds4NeverAdvertiseAmbient() {
        listOf(SamsungBudsCodec.Model.BUDS3, SamsungBudsCodec.Model.BUDS4).forEach { model ->
            assertNull(SamsungBudsCodec.parseNoiseMode(SamsungBudsCodec.Frame(0x77, byteArrayOf(2)), model))
            assertTrue(runCatching { SamsungBudsCodec.noiseModeCommand(SamsungBudsCodec.NoiseMode.AMBIENT, model) }.isFailure)
        }
    }

    @Test
    fun malformedOrShortCommonReportsNeverPromoteSettings() {
        SamsungBudsCodec.Model.entries.filter { it.commonHoldOnly || it.legacyTouch }.forEach { model ->
            val frame = status(model)
            assertNull(SamsungBudsCodec.parseExtendedStatus(frame.copy(payload = frame.payload.copyOf(12)), model))
            frame.payload[11] = 0x52
            assertNull(SamsungBudsCodec.parseExtendedStatus(frame, model))
            assertNotNull(SamsungBudsCodec.parseBattery(frame, model))
        }
    }

    @Test
    fun legacyLockBooleanMustNotBeDecodedAsAdvancedEnabledBit() {
        listOf(SamsungBudsCodec.Model.BUDS_LIVE, SamsungBudsCodec.Model.BUDS_PRO).forEach { model ->
            val frame = status(model)
            frame.payload[10] = 1
            assertTrue(SamsungBudsCodec.parseExtendedStatus(frame, model)!!.settings.touchpadLocked)
            frame.payload[10] = 0xBF.toByte()
            assertNull(SamsungBudsCodec.parseExtendedStatus(frame, model))
        }
    }

    private fun status(model: SamsungBudsCodec.Model) = SamsungBudsCodec.Frame(0x61, ByteArray(46).apply {
        this[0] = 10
        this[2] = 80; this[3] = 90; this[7] = 70
        this[10] = if (model.legacyTouch) 0 else 0xBF.toByte()
        this[11] = 0x22; this[12] = 1; this[21] = 0x65
        this[31] = 1
    })
}
