package dev.hyperears.protocol.jieli

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JlRcspWireCodecTest {

    // ── Real frames captured from DaiLing T3 (JL AC697) over SPP ──

    /** Device information response: FE DC BA 00 03 00 7C + 124 data bytes. */
    private val deviceInfoFrame =
        "fedcba0003007c000502002005010000000109024b147ae878288e0107040000000000000305000102060a0307980004080001010409000000050a00000000110b6" +
            "8453979667365583655644b37724668150c6a6c5f73646b5f61633639375f7075626c697368050d0110021c0811004b147ae8782802120003134680051500000000"

    /** Device noise report: opcode 0x09, transparency with the 0x4000 parameter block. */
    private val noiseTransparencyReport = "fedcba8009000d0eff0a0d024000400040004000"

    /** App noise write captured from the vendor app: transparency. */
    private val noiseTransparencyWrite = "fedcbac008000d1eff0a0d024000400040004000ef"

    /** App noise write captured from the vendor app: active noise cancelling. */
    private val noiseAncWrite = "fedcbac008000d20ff0a0d016000600060006000ef"

    /** Battery channel: 04 00 5A 5A -> left 90%, right 90%. */
    private val batteryFrame = "fedcba00c10007000804005a5a00"

    /** TWS state packet with peer address and both component levels. */
    private val twsStateFrame = "fedcba80c200130f05d600000000224b147ae87828005a5a0017"

    private fun bytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `decodes a device information frame and marks it as a device frame`() {
        val frames = JlRcspWireCodec.Decoder().offer(bytes(deviceInfoFrame))
        assertEquals(1, frames.size)
        val frame = frames[0]
        assertTrue(frame.fromDevice)
        assertEquals(JlRcspWireCodec.OP_DEVICE_INFO, frame.opCode)
        assertEquals(124, frame.data.size)
        assertTrue(JlRcspWireCodec.isDeviceInfo(frame))
    }

    @Test
    fun `reassembles a frame split across two transport reads`() {
        val bytes = bytes(batteryFrame)
        val decoder = JlRcspWireCodec.Decoder()
        assertTrue(decoder.offer(bytes.copyOfRange(0, 5)).isEmpty())
        val frames = decoder.offer(bytes.copyOfRange(5, bytes.size))
        assertEquals(1, frames.size)
        assertEquals(JlRcspWireCodec.OP_NAME_BATTERY, frames[0].opCode)
    }

    @Test
    fun `resynchronises after leading noise bytes`() {
        val noisy = bytes("00ff11aa") + bytes(batteryFrame)
        val frames = JlRcspWireCodec.Decoder().offer(noisy)
        assertEquals(1, frames.size)
        assertEquals(JlRcspWireCodec.OP_NAME_BATTERY, frames[0].opCode)
    }

    @Test
    fun `parses component battery from the 0xC1 channel`() {
        val frame = JlRcspWireCodec.Decoder().offer(bytes(batteryFrame)).single()
        val battery = JlRcspWireCodec.parseBattery(frame)
        assertNotNull(battery)
        assertEquals(90, battery!!.leftPercent)
        assertEquals(90, battery.rightPercent)
    }

    @Test
    fun `parses component battery from the 0xC2 TWS state packet`() {
        val frame = JlRcspWireCodec.Decoder().offer(bytes(twsStateFrame)).single()
        val battery = JlRcspWireCodec.parseBattery(frame)
        assertNotNull(battery)
        assertEquals(90, battery!!.leftPercent)
        assertEquals(90, battery.rightPercent)
    }

    @Test
    fun `parses transparency from the captured device report`() {
        val frame = JlRcspWireCodec.Decoder().offer(bytes(noiseTransparencyReport)).single()
        assertEquals(
            JlRcspWireCodec.NoiseState.TRANSPARENCY,
            JlRcspWireCodec.parseNoiseState(frame),
        )
    }

    @Test
    fun `app write frames never confirm battery or noise state`() {
        val frame = JlRcspWireCodec.Decoder().offer(bytes(noiseAncWrite)).single()
        assertTrue(!frame.fromDevice)
        assertNull(JlRcspWireCodec.parseNoiseState(frame))
        assertNull(JlRcspWireCodec.parseBattery(frame))
    }

    @Test
    fun `noise write reuses the captured app frame shape`() {
        assertArrayEquals(
            bytes(noiseAncWrite),
            JlRcspWireCodec.setNoiseMode(JlRcspWireCodec.NoiseState.ANC, sequence = 0x20),
        )
        assertArrayEquals(
            bytes(noiseTransparencyWrite),
            JlRcspWireCodec.setNoiseMode(
                JlRcspWireCodec.NoiseState.TRANSPARENCY,
                sequence = 0x1E,
            ),
        )
    }

    @Test
    fun `off mode carries the zero parameter block`() {
        assertArrayEquals(
            bytes("fedcbac008000d21ff0a0d000000000000000000ef"),
            JlRcspWireCodec.setNoiseMode(JlRcspWireCodec.NoiseState.OFF, sequence = 0x21),
        )
    }

    @Test
    fun `status query matches the captured app request`() {
        assertArrayEquals(bytes("fedcbac0c300020700ef"), JlRcspWireCodec.queryStatus())
    }

    @Test
    fun `rejects a battery frame with an out of range level`() {
        val frame = JlRcspWireCodec.Decoder()
            .offer(bytes("fedcba00c100070008040000ff00"))
            .single()
        assertNull(JlRcspWireCodec.parseBattery(frame))
    }
}
