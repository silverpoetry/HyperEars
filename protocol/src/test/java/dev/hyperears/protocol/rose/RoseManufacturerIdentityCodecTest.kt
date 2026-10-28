package dev.hyperears.protocol.rose

import org.junit.Assert.*
import org.junit.Test

class RoseManufacturerIdentityCodecTest {
    @Test fun recognizesIndependentProtocolsIncludingThreeBytePrefixes() {
        assertEquals("rose-zt-i3", RoseManufacturerIdentityCodec.modelId(mapOf(0x02f1 to listOf(3))))
        assertEquals("rose-zt-headfeel", RoseManufacturerIdentityCodec.modelId(mapOf(0x5352 to listOf(0,2,0,2))))
        assertEquals("rose-legacy-explorer", RoseManufacturerIdentityCodec.modelId(mapOf(0x8418 to listOf(1,7))))
        assertEquals(RoseRsV2IdentityCodec.Model.CERAMICS_MK2.id,
            RoseManufacturerIdentityCodec.modelId(mapOf(0x0e0b to listOf(10,12))))
    }
    @Test fun ambiguityAndMalformedManufacturerRecordsNeverSelectAnAdapter() {
        assertNull(RoseManufacturerIdentityCodec.modelId(mapOf(0x02f1 to listOf(3),0x8418 to listOf(1,7))))
        assertNull(RoseManufacturerIdentityCodec.modelId(mapOf(0x02f1 to listOf(3),-1 to listOf(0))))
        assertNull(RoseManufacturerIdentityCodec.modelId(mapOf(0x02f1 to listOf(3,256))))
        assertNull(RoseManufacturerIdentityCodec.modelId(mapOf(0x02f1 to emptyList())))
    }
}
