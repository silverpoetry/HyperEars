package dev.hyperears.integration

import org.junit.Assert.*
import org.junit.Test

class SonySpeakParametersTest {
    private fun b(vararg n:Int)=n.map(Int::toByte).toByteArray()
    @Test fun preservesOtherFieldsAndRejectsStaleOrUnadvertisedWrites() {
        for(type in listOf(2,12)) {
            val s=SonyDailyControlsSession(true)
            val report=if(type==2)b(0xFB,2,0,0,1) else b(0xFB,12,0,1)
            s.accept(report);assertNull(s.audioState().speakParameters)
            s.accept(b(7,0,1,if(type==2)0xF2 else 0xFC,0))
            s.accept(b(0xF3,type,0,0));s.accept(b(0xF7,type,0,1));s.accept(report)
            val before=requireNotNull(s.audioState().speakParameters)
            val request=SonyControlRequest.SetSpeakParameters(SonyAudioDefinition(2,type),before,before.copy(sensitivity=2))
            assertEquals(request,ControlRequestTransport.decode(ControlRequestTransport.encode(request)))
            val encoded=s.encode(request)
            assertArrayEquals(if(type==2)b(0xFC,2,2,0,1)else b(0xFC,12,2,1),encoded.first())
            assertArrayEquals(b(0xFA,type),encoded[1])
            assertEquals(before,s.audioState().speakParameters)
            s.accept(if(type==2)b(0xFD,2,1,0,1)else b(0xFD,12,1,1))
            assertTrue(s.encode(request).isEmpty())
            s.accept(b(7,0,0));assertNull(s.audioState().speakParameters)
            assertTrue(s.encode(request).isEmpty())
        }
    }
    @Test fun malformedAndWrongVersionReportsDoNotGrantParameters() {
        val s=SonyDailyControlsSession(true);s.accept(b(7,0,1,0xFC,0))
        listOf(b(0xFB,12,0),b(0xFB,12,255,1),b(0xFB,12,0,4),b(0xFC,12,0,1),b(0xFB,2,0,0,1)).forEach{s.accept(it)}
        assertNull(s.audioState().speakParameters)
        val v1=SonyDailyControlsSession(false);v1.accept(b(7,0,1,0xF5));v1.accept(b(0xFB,5,0,1))
        assertNull(v1.audioState().speakParameters)
    }
}
