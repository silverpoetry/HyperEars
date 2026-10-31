package dev.hyperears.protocol.tozo
import org.junit.Assert.*
import org.junit.Test
class TozoTouchCodecTest {
    @Test fun eightSlotsAndBothReservedBytesSurviveAnIndividualChange() {
        val original=byteArrayOf(8,3,10,6,99,1,2,4,5,88)
        val read=TozoWireCodec.encode(0x0003,original)
        val decoded=requireNotNull(TozoTouchCodec.parse(read))
        assertArrayEquals(original,decoded)
        val updated=TozoWireCodec.decode(TozoTouchCodec.write(decoded,7,8))!!
        assertEquals(0x1003,updated.command)
        assertArrayEquals(original.copyOf().also{it[7]=8},updated.payload)
        assertArrayEquals(original,decoded)
    }
    @Test fun partialSingleAndAcknowledgementPacketsDoNotGrantTwsTouchSettings() {
        for(size in listOf(0,1,5,9,11)) assertNull(TozoTouchCodec.parse(TozoWireCodec.encode(0x0003,ByteArray(size))))
        assertNull(TozoTouchCodec.parse(TozoWireCodec.encode(0x1003,byteArrayOf(0))))
    }
    @Test fun reservedOffsetsAndUnsupportedActionsAreRejected() {
        val original=byteArrayOf(8,3,10,6,0,1,2,4,5,0)
        assertThrows(IllegalArgumentException::class.java){TozoTouchCodec.write(original,4,1)}
        assertThrows(IllegalArgumentException::class.java){TozoTouchCodec.write(original,9,1)}
        assertThrows(IllegalArgumentException::class.java){TozoTouchCodec.write(original,0,7)}
        assertThrows(IllegalArgumentException::class.java){TozoTouchCodec.write(original,3,1)}
    }
}
