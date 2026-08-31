package dev.hyperears.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothEarbudIdentityTest {
    @Test
    fun recognizesConservativeEarbudNameFallbacks() {
        assertTrue(isLikelyEarbudName("vivo TWS 3e"))
        assertTrue(isLikelyEarbudName("OPPO Enco Air5"))
        assertTrue(isLikelyEarbudName("Galaxy Buds3 Pro"))
        assertTrue(isLikelyEarbudName("LE-电音耳机"))
        assertTrue(isLikelyEarbudName("HUAWEI FreeClip 2"))
        assertFalse(isLikelyEarbudName("HUAWEI FreeLace Pro"))
    }

    @Test
    fun doesNotClassifySpeakersCarsOrUnrelatedDevicesByName() {
        assertFalse(isLikelyEarbudName("Living Room Speaker"))
        assertFalse(isLikelyEarbudName("Xiaomi Sound"))
        assertFalse(isLikelyEarbudName("Car Multimedia"))
        assertFalse(isLikelyEarbudName(null))
    }

    @Test
    fun recognizesNamesThatMustRemainOnXiaomiNativePath() {
        assertTrue(isNativeXiaomiEarbudName("Xiaomi Buds 5 Pro"))
        assertTrue(isNativeXiaomiEarbudName("REDMI Buds 6 Pro"))
        assertTrue(isNativeXiaomiEarbudName("Mi True Wireless Earphones 2"))
        assertFalse(isNativeXiaomiEarbudName("vivo TWS Air3 Pro"))
        assertFalse(isNativeXiaomiEarbudName("Galaxy Buds3 Pro"))
    }
}
