package dev.hyperears.protocol.rose

import org.junit.Assert.*
import org.junit.Test

class RoseLegacyFindWireTest {
    @Test fun sourceF8AndRsCommonTargetsHaveIndependentStopValuesAndFraming() {
        assertArrayEquals(byteArrayOf(0,0x2a,1,0,1,1),RoseLegacyWireCodec.f8Find(1))
        assertArrayEquals(byteArrayOf(0,0x2a,1,0,1,2),RoseLegacyWireCodec.f8Find(2))
        assertArrayEquals(byteArrayOf(0,0x2a,1,0,1,3),RoseLegacyWireCodec.f8Find(3))
        assertArrayEquals(byteArrayOf(0,0x2a,1,0,1,0),RoseLegacyWireCodec.f8Find(0))
        assertArrayEquals(byteArrayOf(0xff.toByte(),0,2,0x2f,1,0x31,0xaa.toByte()),RoseLegacyWireCodec.rsFind(0,1))
        assertArrayEquals(byteArrayOf(0xff.toByte(),0,2,0x2f,4,0x34,0xaa.toByte()),RoseLegacyWireCodec.rsFind(0,4))
        assertTrue(runCatching{RoseLegacyWireCodec.f8Find(4)}.isFailure)
        assertTrue(runCatching{RoseLegacyWireCodec.rsFind(0,0)}.isFailure)
    }
    @Test fun ztModelsUseTheirOwnCommandsAndNeverInventDualStartOrGetter() {
        val i5=RoseZtWireCodec.Model.I5_SOURCE;val head=RoseZtWireCodec.Model.HEADFEEL
        assertArrayEquals(byteArrayOf(8,0xee.toByte(),0,0,0,14,0x82.toByte(),12,0,0,1,0x93.toByte()),RoseZtWireCodec.find(i5,0,true))
        assertArrayEquals(byteArrayOf(8,0xee.toByte(),0,0,0,14,0x82.toByte(),12,0,1,1,0x94.toByte()),RoseZtWireCodec.find(i5,1,true))
        assertArrayEquals(byteArrayOf(8,0xee.toByte(),0,0,0,14,0x82.toByte(),12,0,3,0,0x95.toByte()),RoseZtWireCodec.find(i5,3,false))
        assertArrayEquals(byteArrayOf(8,0xee.toByte(),0,0,0,14,2,12,0,0,1,0x13),RoseZtWireCodec.find(head,0,true))
        assertArrayEquals(byteArrayOf(8,0xee.toByte(),0,0,0,14,2,12,0,3,0,0x15),RoseZtWireCodec.find(head,3,false))
        assertNull(RoseZtWireCodec.find(i5,2,true));assertNull(RoseZtWireCodec.find(head,1,true))
        assertNull(RoseZtWireCodec.find(RoseZtWireCodec.Model.I3,0,true))
        assertNull(RoseZtWireCodec.find(i5,0,false))
    }
    @Test fun onlyExactI5TerminalNotificationMeansReportedStop() {
        val model=RoseZtWireCodec.Model.I5_SOURCE
        val frame=RoseZtWireCodec.Frame(14,2,listOf(0,0))
        assertTrue(RoseZtWireCodec.findStopped(model,frame))
        for(payload in listOf(emptyList(),listOf(0),listOf(0,1),listOf(1,0),listOf(2,0),listOf(3,0),listOf(0,0,0)))
            assertFalse(RoseZtWireCodec.findStopped(model,frame.copy(payload=payload)))
        assertFalse(RoseZtWireCodec.findStopped(model,frame.copy(command=0x82)))
        assertFalse(RoseZtWireCodec.findStopped(RoseZtWireCodec.Model.HEADFEEL,frame))
    }
}
