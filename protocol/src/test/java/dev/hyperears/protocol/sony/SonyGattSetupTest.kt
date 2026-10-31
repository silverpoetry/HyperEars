package dev.hyperears.protocol.sony
import org.junit.Assert.*
import org.junit.Test
class SonyGattSetupTest {
    @Test fun joinsServicesAndMtuAndAcceptsEarlyReadyNotification() {
        for(servicesFirst in listOf(true,false)) {
            val s=SonyGattSetup()
            if(servicesFirst){assertNull(s.services(true));assertEquals(SonyGattSetup.Action.PROBE,s.mtuSettled(247))}
            else {assertNull(s.mtuSettled(247));assertEquals(SonyGattSetup.Action.PROBE,s.services(true))}
            assertNull(s.probeNotification(byteArrayOf(1)))
            assertEquals(SonyGattSetup.Action.READ_LENGTH,s.probeSubscribed(true))
            assertEquals(SonyGattSetup.Action.SUBSCRIBE_DATA,s.length(byteArrayOf(0,100)))
            assertEquals(SonyGattSetup.Action.READY,s.dataSubscribed(true))
            assertEquals(100,s.fragmentLimit)
            assertNull(s.mtuSettled(517))
        }
    }
    @Test fun missingProbeAndProbeTimeoutFallBackToMandatoryLengthRead() {
        val s=SonyGattSetup();s.mtuSettled(23)
        assertEquals(SonyGattSetup.Action.READ_LENGTH,s.services(false))
        assertEquals(SonyGattSetup.Action.FAILED,s.length(byteArrayOf(20)))
        assertNull(s.length(byteArrayOf(0,20)))
        val t=SonyGattSetup();t.services(true);t.mtuSettled(23)
        assertEquals(SonyGattSetup.Action.WAIT_PROBE,t.probeSubscribed(true))
        assertNull(t.probeNotification(byteArrayOf(0)))
        assertEquals(SonyGattSetup.Action.READ_LENGTH,t.timeout(SonyGattSetup.Action.WAIT_PROBE))
        assertNull(t.timeout(SonyGattSetup.Action.WAIT_PROBE))
        assertEquals(SonyGattSetup.Action.SUBSCRIBE_DATA,t.length(byteArrayOf(1,0)))
        assertEquals(20,t.fragmentLimit)
        assertEquals(SonyGattSetup.Action.FAILED,t.dataSubscribed(false))
    }
    @Test fun zeroLengthIsNotWritableAndCloseRejectsCallbacks() {
        val s=SonyGattSetup();s.mtuSettled(23);s.services(false)
        assertEquals(SonyGattSetup.Action.FAILED,s.length(byteArrayOf(0,0)))
        val t=SonyGattSetup();t.close()
        assertNull(t.services(true));assertNull(t.mtuSettled(247));assertNull(t.probeSubscribed(true))
    }
    @Test fun fragmentsPreserveEscapedWireBytesExactlyWithinBothLimits() {
        val s=SonyGattSetup();s.services(false);s.mtuSettled(23);s.length(byteArrayOf(0,7));s.dataSubscribed(true)
        val raw=SonyHeadphonesWireCodec.encode(SonyHeadphonesWireCodec.MessageType.COMMAND_1,0,ByteArray(40){0x3e})
        val chunks=s.fragments(raw)
        assertTrue(chunks.all {it.size in 1..7})
        assertArrayEquals(raw,chunks.fold(byteArrayOf()){a,b->a+b})
        assertEquals(1,SonyHeadphonesWireCodec.Decoder().offer(chunks.fold(byteArrayOf()){a,b->a+b}).size)
    }
}
