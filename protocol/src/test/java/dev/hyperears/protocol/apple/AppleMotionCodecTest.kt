package dev.hyperears.protocol.apple
import org.junit.Assert.*
import org.junit.Test
class AppleMotionCodecTest {
    private fun frame(body: ByteArray) = byteArrayOf(4,0,4,0,0x17,0,0,0,16,0,body.size.toByte(),0) + body
    private fun pose(service: Int): ByteArray {
        val blob = ByteArray(58).also { it[0]=1; it[9]=3 }
        val nested = byteArrayOf(8, service.toByte(), 0x1a,58) + blob
        return frame(byteArrayOf(0x3a,nested.size.toByte()) + nested)
    }
    @Test fun discoveredServiceRequiredAndZeroRotationIsValid() {
        assertNull(AppleMotionCodec.decode(pose(15)))
        assertNull(AppleMotionCodec.decode(pose(15), 16))
        assertEquals(Quaternion(0.0,0.0,0.0,1.0), AppleMotionCodec.decode(pose(15),15)?.quaternion)
        assertNotNull(AppleMotionCodec.decode(pose(99),99))
    }
    @Test fun framingRejectsWrongOpcodeTruncationAndMultipleDescriptorBits() {
        val valid=pose(15)
        for (size in 0 until valid.size) assertNull(AppleMotionCodec.decode(valid.copyOf(size),15))
        assertEquals(-1, AppleMotionCodec.frameLength(valid.copyOf().also { it[5]=1 }))
        assertEquals(-1, AppleMotionCodec.frameLength(valid.copyOf().also { it[8]=17 }))
        assertNull(AppleMotionCodec.decode(valid+byteArrayOf(0),15))
    }
    @Test fun onlyKnownSensorCountAndBoundedIntervalsAccepted() {
        val invalid=pose(15).also { it[27]=4 }
        assertNull(AppleMotionCodec.decode(invalid,15))
        for (interval in listOf(0,20000,40000)) {
            val frame=AppleMotionCodec.subscription(99,interval,256)
            assertEquals(frame.size, AppleMotionCodec.frameLength(frame))
        }
        assertTrue(runCatching { AppleMotionCodec.subscription(0,20000,256) }.isFailure)
        assertTrue(runCatching { AppleMotionCodec.subscription(15,1,256) }.isFailure)
    }
    @Test fun privateMotionFramesSurviveAllSplitBoundariesWithoutBecomingState() {
        val motion = pose(15).also { byteArrayOf(4,0,4,0,9,0,13,2,0,0,0).copyInto(it,35) }
        val control = AppleAapWireCodec.setControl(6,listOf(1,0,0,0))
        for (split in 1 until motion.size) {
            val received=mutableListOf<ByteArray>()
            val decoder=AppleAapWireCodec.Decoder(onPrivateMotion={received+=it})
            assertTrue(decoder.offer(motion.copyOf(split)).isEmpty())
            val states=decoder.offer(motion.copyOfRange(split,motion.size)+control)
            assertEquals(2,received.size)
            assertArrayEquals(motion,received[0])
            assertEquals(listOf(AppleAapWireCodec.State.Control(6,listOf(1,0,0,0))),states)
        }
    }
    @Test fun malformedMotionCannotSmuggleControlThroughResynchronization() {
        val embedded=AppleAapWireCodec.setControl(6,listOf(1,0,0,0))
        val malformed=pose(15).also { it[10]=0;it[11]=0;embedded.copyInto(it,35) }
        val received=mutableListOf<ByteArray>()
        val decoder=AppleAapWireCodec.Decoder(onPrivateMotion={received+=it})
        assertTrue(decoder.offer(malformed).isEmpty())
        assertTrue(received.isEmpty())
        assertEquals(listOf(AppleAapWireCodec.State.Control(6,listOf(1,0,0,0))),decoder.offer(embedded))
    }
    @Test fun acknowledgementCannotBePose() {
        val frame=frame(byteArrayOf(0x4a,4,8,15,16,0))
        assertEquals(MotionAcknowledgement(15,0),AppleMotionCodec.acknowledgement(frame))
        assertNull(AppleMotionCodec.decode(frame,15))
    }
}
