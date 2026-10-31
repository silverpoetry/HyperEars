package dev.hyperears.protocol.rayneo

import org.junit.Assert.*
import org.junit.Test

class RayNeoAuthCodecTest {
    // Synthetic material; fixtures independently computed with Python hashlib.
    private val material = ByteArray(16) { it.toByte() }
    private val first = byteArrayOf(1, 2, 3, 4)
    private val second = byteArrayOf(5, 6, 7, 8)
    private val phone = "11:22:33:44:55:66"
    private val glasses = "AA:BB:CC:DD:EE:FF"

    @Test fun challengeDigestMatchesReference() {
        assertEquals("8c51081afd21a57d6443f361173e3e2b47945e8405d23c4da13745c8b1d8de8a",
            RayNeoAuthCodec.hex(RayNeoAuthCodec.authValue(first, phone, glasses, material)))
    }

    @Test fun sessionDerivationMatchesReferenceWithoutImplyingEncryption() {
        assertEquals("bc4ec9d2b17dca66ecd948230016452ed1aacfbcbd640e94ffce473f63f880bd",
            RayNeoAuthCodec.hex(RayNeoAuthCodec.sessionKey(first, second, phone, glasses, material)))
    }

    @Test fun validatesDigestAndRejectsWrongIdentityOrShape() {
        val digest = RayNeoAuthCodec.authValue(first, phone, glasses, material)
        assertTrue(RayNeoAuthCodec.verify(first, phone, glasses, material, digest))
        assertFalse(RayNeoAuthCodec.verify(first, "11:22:33:44:55:67", glasses, material, digest))
        assertFalse(RayNeoAuthCodec.verify(first, phone, glasses, material, digest.copyOf(31)))
        assertFalse(RayNeoAuthCodec.verify(byteArrayOf(1), phone, glasses, material, digest))
        assertFalse(RayNeoAuthCodec.verify(first, "invalid", glasses, material, digest))
    }

    @Test fun androidIdentifierIsNotTheAdapterMac() {
        assertEquals("22:33:44:55:66:77", RayNeoAuthCodec.androidIdentifier("0011223344556677"))
        assertEquals("AA:BB:CC:DD:EE:FF", RayNeoAuthCodec.androidIdentifier("0000aabbccddeeff"))
        for (value in listOf("", "1234", "0000gg1122334455")) {
            assertTrue(runCatching { RayNeoAuthCodec.androidIdentifier(value) }.isFailure)
        }
    }

    @Test fun rejectsMalformedHexAndNoncanonicalAddress() {
        assertArrayEquals(byteArrayOf(0, 127, -1), RayNeoAuthCodec.parseHex("007fFF"))
        for (value in listOf("0", "0x01", "0g", " 01")) {
            assertTrue(runCatching { RayNeoAuthCodec.parseHex(value) }.isFailure)
        }
        assertTrue(runCatching { RayNeoAuthCodec.authValue(first, "1:22:33:44:55:66", glasses, material) }.isFailure)
        assertTrue(runCatching { RayNeoAuthCodec.authValue(first, phone, glasses, ByteArray(0)) }.isFailure)
    }
}
