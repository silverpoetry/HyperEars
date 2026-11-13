package dev.hyperears.protocol.swarmoo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SwarmooMo3WireCodecTest {
    @Test
    fun decodesCapturedNotificationsAndRejectsTruncatedMtuPayload() {
        val mode = hex("55 AA 0B 00 00 01 02 00 00 00 00 00 01 FF 09 01 00 FF FF FF 01 00 00")
        assertEquals(SwarmooMo3WireCodec.ModeState(1, 2), SwarmooMo3WireCodec.parseMode(mode))
        assertNull(SwarmooMo3WireCodec.parseMode(mode.copyOf(20)))
        assertNull(SwarmooMo3WireCodec.parseMode(mode.copyOf(7)))
        assertNull(SwarmooMo3WireCodec.parseMode(mode + byteArrayOf(0)))
        assertNull(SwarmooMo3WireCodec.parseBattery(mode))
        assertEquals(
            SwarmooMo3WireCodec.BatteryState(100, 100, 20),
            SwarmooMo3WireCodec.parseBattery(hex("55 AA 0B 00 64 64 14")),
        )
    }

    @Test
    fun boxZeroAndSentinelAreUnknownButRealEarZeroIsPreserved() {
        assertEquals(SwarmooMo3WireCodec.BatteryState(0, null, null),
            SwarmooMo3WireCodec.parseBattery(hex("55 AA 0B 00 00 FF 00")))
        assertEquals(SwarmooMo3WireCodec.BatteryState(22, 20, null),
            SwarmooMo3WireCodec.parseBattery(hex("55 AA 0B 00 16 14 FF")))
        assertNull(SwarmooMo3WireCodec.parseBattery(hex("55 AA 0B 00 65 64 14")))
    }

    @Test
    fun commandVectorsMatchCapturesAndNeverInventSubmodeZero() {
        assertArrayEquals(hex("55 AA 0A 00 FF"), SwarmooMo3WireCodec.query)
        assertArrayEquals(hex("55 AA 0A 01 02"), SwarmooMo3WireCodec.setMain(2))
        assertArrayEquals(hex("55 AA 0A 02 01"), SwarmooMo3WireCodec.setSub(1))
        assertArrayEquals(hex("55 AA 0A 02 02"), SwarmooMo3WireCodec.setSub(2))
        assertFalse(SwarmooMo3WireCodec.isKnownCommand(hex("55 AA 0A 02 00")))
        assertThrows(IllegalArgumentException::class.java) { SwarmooMo3WireCodec.setSub(0) }
    }

    private fun hex(text: String): ByteArray = text.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
