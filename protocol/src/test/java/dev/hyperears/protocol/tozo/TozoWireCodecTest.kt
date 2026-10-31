package dev.hyperears.protocol.tozo
import org.junit.Assert.*
import org.junit.Test
class TozoWireCodecTest {
    @Test fun capturedNc20ProFramesDecodeAndReencodeExactly() {
        val resource=requireNotNull(javaClass.getResourceAsStream("/tozo/nc20-pro-5.4.3-frames.txt"))
        val frames=resource.bufferedReader().use {it.readLines()}.filter {it.isNotBlank() && !it.startsWith("#")}
        assertTrue(frames.size>=20)
        for(hex in frames) {
            val bytes=hex.chunked(2).map {it.toInt(16).toByte()}.toByteArray()
            val frame=requireNotNull(TozoWireCodec.decode(bytes)) {hex}
            assertArrayEquals(hex,bytes,TozoWireCodec.encode(frame.command,frame.payload))
        }
    }
    @Test fun documentedQueryAndWriteFrameShapes() {
        assertArrayEquals(byteArrayOf(0,2,0,0),TozoWireCodec.encode(0x0002,byteArrayOf()))
        assertArrayEquals(byteArrayOf(0x10,4,1,2,2),TozoWireCodec.encode(0x1004,byteArrayOf(2)))
        val frame=TozoWireCodec.decode(byteArrayOf(0,2,2,80,90,170.toByte()))!!
        assertEquals(2,frame.command);assertArrayEquals(byteArrayOf(80,90),frame.payload)
    }
    @Test fun unsignedLengthAndPayloadChecksumWrapArePreserved() {
        val payload=ByteArray(200){255.toByte()}
        val encoded=TozoWireCodec.encode(0x2030,payload)
        assertEquals(200,encoded[2].toInt() and 255)
        assertEquals(56,encoded.last().toInt() and 255)
        assertArrayEquals(payload,TozoWireCodec.decode(encoded)!!.payload)
    }
    @Test fun malformedFramesNeverYieldState() {
        val valid=byteArrayOf(0,2,2,80,90,170.toByte())
        for(n in 0 until valid.size)assertNull(TozoWireCodec.decode(valid.copyOf(n)))
        assertNull(TozoWireCodec.decode(valid+byteArrayOf(0)))
        assertNull(TozoWireCodec.decode(valid.copyOf().also{it[3]=81}))
    }
    @Test fun rejectsUnrepresentableCommandAndLength() {
        for(command in listOf(-1,65536))assertTrue(runCatching {TozoWireCodec.encode(command,byteArrayOf())}.isFailure)
        assertTrue(runCatching {TozoWireCodec.encode(1,ByteArray(256))}.isFailure)
    }
}
