package dev.hyperears.protocol.jieli

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** 测试向量由杰理官方 Apache-2.0 授权小程序 SDK 的 `jl_auth_2.0.0.js` 生成。 */
class JlAuthCryptoTest {

    @Test
    fun authResponseMatchesOfficialImplementationForZeroInput() {
        val response = JlAuthCrypto.authResponse(ByteArray(16))
        assertEquals(0x01, response[0].toInt() and 0xFF)
        assertArrayEquals(
            bytes("9e43df377b3a46ce751634667e215de5"),
            response.copyOfRange(1, 17),
        )
    }

    @Test
    fun authResponseMatchesOfficialImplementationForSequentialInput() {
        val input = ByteArray(16) { it.toByte() }
        assertArrayEquals(
            bytes("01e5807e887bde07630b4d0e715b3e8991"),
            JlAuthCrypto.authResponse(input),
        )
    }

    @Test
    fun authResponseMatchesOfficialImplementationForMixedInput() {
        val input = bytes("0b30557a9fc4e90e33587da2c7ec1136")
        assertArrayEquals(
            bytes("01f7cdc0ba16a935a8c229a1763c687d80"),
            JlAuthCrypto.authResponse(input),
        )
    }

    @Test
    fun authStartCarriesZeroPrefixAndRandomData() {
        val random = bytes("00112233445566778899aabbccddeeff")
        val start = JlAuthCrypto.authStart(random)
        assertEquals(17, start.size)
        assertEquals(0x00, start[0].toInt() and 0xFF)
        assertArrayEquals(random, start.copyOfRange(1, 17))
    }

    @Test
    fun authOkIsTheCapturedFiveBytePassPacket() {
        assertArrayEquals(bytes("0270617373"), JlAuthCrypto.authOk())
    }

    private fun bytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
