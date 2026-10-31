package dev.hyperears.protocol.tozo
import org.junit.Assert.*
import org.junit.Test
class TozoIdentityCodecTest {
    private val mac=byteArrayOf(0x10,0x20,0x30,0x40,0x50,0x60)
    private fun ad(payload:ByteArray)=byteArrayOf((payload.size+1).toByte(),0xff.toByte())+payload
    @Test fun exactNc20ProIdsSeparateEarbudsAndBox() {
        for((id,batch) in listOf(0xd5 to 0x0d,0x14 to 0x0e,0x21 to 0x0e,0xd5 to 9,0x14 to 9,0x21 to 9)) {
            val value=TozoIdentityCodec.parseAdvertisement(ad(mac+byteArrayOf(id.toByte(),0x57,0x52,batch.toByte())))!!
            assertEquals("10:20:30:40:50:60",value.classicAddress)
            assertEquals(if(batch==9)TozoIdentityCodec.Nc20ProPart.CASE else TozoIdentityCodec.Nc20ProPart.EARBUDS,value.nc20ProPart)
        }
        assertNull(TozoIdentityCodec.parseAdvertisement(ad(mac+byteArrayOf(0xd4.toByte(),0x57,0x52,0x0d)))!!.nc20ProPart)
    }
    @Test fun legacyMarkersDoNotInventBatchOrExactModel() {
        for(marker in listOf(byteArrayOf(0x57,0x52),byteArrayOf(0x57,0x4f,0x45,0x52))) {
            val value=TozoIdentityCodec.parseAdvertisement(ad(mac+byteArrayOf(0xd5.toByte())+marker))!!
            assertNull(value.batch);assertEquals("D5",value.mark);assertNull(value.nc20ProPart)
        }
    }
    @Test fun respectsAdBoundariesAndRejectsTruncationAndConflicts() {
        val valid=ad(mac+byteArrayOf(0xd5.toByte(),0x57,0x52,0x0d))
        assertNotNull(TozoIdentityCodec.parseAdvertisement(byteArrayOf(2,1,6)+valid+byteArrayOf(0)))
        for(end in 1 until valid.size)assertNull(TozoIdentityCodec.parseAdvertisement(valid.copyOf(end)))
        assertNull(TozoIdentityCodec.parseAdvertisement(valid+ad(mac+byteArrayOf(0x14,0x57,0x52,0x0e))))
        assertNull(TozoIdentityCodec.parseAdvertisement(byteArrayOf((valid.size+1).toByte(),9)+valid)) // marker inside name is not manufacturer data
    }
    @Test fun androidManufacturerEntryRestoresCompanyBytesWithoutMacArithmetic() {
        val payload=mac+byteArrayOf(0xd5.toByte(),0x57,0x52,0x0d)
        assertEquals(TozoIdentityCodec.parseAdvertisement(ad(payload)),TozoIdentityCodec.parseManufacturer(0x2010,payload.copyOfRange(2,payload.size)))
        assertNull(TozoIdentityCodec.parseManufacturer(-1,byteArrayOf()))
    }
}
