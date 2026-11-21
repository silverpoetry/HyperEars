package dev.hyperears.protocol.samsung

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungFamilyCodecTest {
    @Test
    fun liveUsesBooleanAncWithoutAmbientOrChargingBits() {
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
    fun plainBuds3AndBuds4NeverAdvertiseAmbient() {
        listOf(SamsungBudsCodec.Model.BUDS3, SamsungBudsCodec.Model.BUDS4).forEach { model ->
            assertNull(SamsungBudsCodec.parseNoiseMode(SamsungBudsCodec.Frame(0x77, byteArrayOf(2)), model))
            assertTrue(runCatching { SamsungBudsCodec.noiseModeCommand(SamsungBudsCodec.NoiseMode.AMBIENT, model) }.isFailure)
        }
    }
}
