package dev.hyperears.hook
import dev.hyperears.integration.*
import org.junit.Assert.*
import org.junit.Test
class AppleSpatialSessionTest {
    private class Host : AppleSpatialHost {
        override val rendererAvailable=true
        override val trackerAvailable=true
        override val trackingActive=true
        var closes=0;var fixed=0
        override fun fixed():Boolean { fixed++;return true }
        override fun start()=true
        override fun pose(vector:List<Double>)=true
        override fun close(){closes++}
    }
    @Test fun explicitChoiceAndCalibrationGateTracking() {
        val host=Host();val writes=mutableListOf<ByteArray>();val states=mutableListOf<AppleSpatialFeatureState>()
        val session=AppleSpatialSession(host,{writes+=it;true},{states+=it},{0L},{_,_->{} })
        assertFalse(session.request(AppleSpatialControlRequest.SetMode(AppleSpatialMode.FIXED)))
        session.updateEligibility(true)
        assertTrue(writes.isEmpty())
        assertFalse(session.request(AppleSpatialControlRequest.SetMode(AppleSpatialMode.TRACKING)))
        assertTrue(session.request(AppleSpatialControlRequest.SetMode(AppleSpatialMode.FIXED)))
        assertEquals(AppleSpatialMode.FIXED,states.last().mode)
        session.updateEligibility(false)
        assertEquals(AppleSpatialMode.OFF,states.last().mode)
        assertTrue(host.closes>0)
    }
    @Test fun boundedDiscoveryAndLateDeadlinesCannotRestartSession() {
        val host=Host();val states=mutableListOf<AppleSpatialFeatureState>();val timers=mutableListOf<()->Unit>()
        val session=AppleSpatialSession(host,{true},{states+=it},{0L},{_,task->timers+=task;{} })
        session.updateEligibility(true)
        assertTrue(session.request(AppleSpatialControlRequest.BeginCalibration))
        assertEquals(0,states.last().calibrationStep)
        timers.last()()
        assertFalse(states.last().pending)
        session.close();timers.first()()
        assertFalse(states.last().rendererAvailable)
    }
    private fun envelope(body:ByteArray)=byteArrayOf(4,0,4,0,23,0,0,0,16,0,body.size.toByte(),(body.size shr 8).toByte())+body
    private fun descriptor():ByteArray {
        fun word(value:Int)=ByteArray(4){(value ushr (8*it)).toByte()}
        fun text(value:String):ByteArray { val bytes=value.toByteArray();return word(0x09000000+bytes.size)+bytes+ByteArray((4-bytes.size%4)%4) }
        val io=word(0xd3)+word(0x81000001.toInt())+text("AccessoryService")+text("devmotion6")
        val nested=byteArrayOf(8,15,18,io.size.toByte())+io
        return envelope(byteArrayOf(0x2a,nested.size.toByte())+nested)
    }
    private fun pose(axis:Int=-1):ByteArray {
        val blob=ByteArray(58).also { it[0]=1;it[9]=3 }
        if(axis>=0) { val value=(kotlin.math.sin(0.25)*32768).toInt();blob[20+axis*2]=value.toByte();blob[21+axis*2]=(value shr 8).toByte() }
        val nested=byteArrayOf(8,15,26,58)+blob
        return envelope(byteArrayOf(58,nested.size.toByte())+nested)
    }
    @Test fun discoveredServiceAcknowledgementCalibrationAndPoseFormOneWorkflow() {
        val host=Host();val states=mutableListOf<AppleSpatialFeatureState>();val writes=mutableListOf<ByteArray>()
        val session=AppleSpatialSession(host,{writes+=it;true},{states+=it},{100L},{_,_->{} })
        session.updateEligibility(true)
        session.onPacket(byteArrayOf(4,0,4,0,9,0,6,1,0,0,0))
        session.request(AppleSpatialControlRequest.BeginCalibration)
        session.onPacket(descriptor())
        assertFalse(session.request(AppleSpatialControlRequest.CalibrationStep))
        session.onPacket(envelope(byteArrayOf(74,4,8,15,16,0)))
        for(axis in listOf(2,0,1)) {
            session.onPacket(pose())
            assertTrue(session.request(AppleSpatialControlRequest.CalibrationStep))
            session.onPacket(pose(axis))
            assertTrue(session.request(AppleSpatialControlRequest.CalibrationStep))
        }
        assertTrue(states.last().calibrated)
        assertTrue(session.request(AppleSpatialControlRequest.SetMode(AppleSpatialMode.TRACKING)))
        session.onPacket(descriptor())
        session.onPacket(pose())
        assertEquals(AppleSpatialMode.OFF,states.last().mode)
        session.onPacket(envelope(byteArrayOf(74,4,8,15,16,0)))
        session.onPacket(pose())
        assertEquals(AppleSpatialMode.TRACKING,states.last().mode)
        session.updateEligibility(false)
        assertEquals(AppleSpatialMode.OFF,states.last().mode)
        val count=writes.size
        session.onPacket(pose(0))
        assertEquals(count,writes.size)
    }
    @Test fun ownershipReleaseRestoresOnlyTheOwnedByte() {
        val writes=mutableListOf<ByteArray>()
        val session=AppleSpatialSession(Host(),{writes+=it;true},{},{100L},{_,_->{} })
        session.updateEligibility(true)
        session.onPacket(byteArrayOf(4,0,4,0,9,0,6,0,10,11,12))
        session.request(AppleSpatialControlRequest.BeginCalibration)
        session.onPacket(descriptor())
        assertArrayEquals(byteArrayOf(4,0,4,0,9,0,6,1,10,11,12),writes.last())
        session.onPacket(byteArrayOf(4,0,4,0,9,0,6,1,10,11,12))
        session.onPacket(byteArrayOf(4,0,4,0,9,0,6,1,20,21,22))
        session.request(AppleSpatialControlRequest.SetMode(AppleSpatialMode.OFF))
        assertArrayEquals(byteArrayOf(4,0,4,0,9,0,6,0,20,21,22),writes.last())
    }
}
