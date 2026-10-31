package dev.hyperears.protocol.rayneo

import java.security.MessageDigest
import java.util.Locale

/** RayNeo V1 digest primitives. No key material, device I/O or binding changes.
 * Session-key derivation is documented protocol behaviour, not evidence that
 * subsequent business messages use encryption.
 */
object RayNeoAuthCodec {
    private val addressPattern = Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")
    private val hexPattern = Regex("[0-9a-fA-F]*")

    fun androidIdentifier(androidId: String): String {
        val suffix = androidId.takeLast(12)
        require(suffix.length == 12 && hexPattern.matches(suffix)) { "Invalid Android identifier" }
        return suffix.uppercase(Locale.ROOT).chunked(2).joinToString(":")
    }

    fun authValue(random: ByteArray, phoneAddress: String, glassAddress: String, material: ByteArray): ByteArray {
        require(random.size == 4) { "V1 random must be four bytes" }
        require(material.size == 16) { "V1 material must be sixteen bytes" }
        return digest(random, address(phoneAddress), address(glassAddress), material)
    }

    fun sessionKey(first: ByteArray, second: ByteArray, phoneAddress: String, glassAddress: String, material: ByteArray): ByteArray {
        require(first.size == 4 && second.size == 4) { "V1 randoms must be four bytes" }
        require(material.size == 16) { "V1 material must be sixteen bytes" }
        return digest(first, second, address(phoneAddress), address(glassAddress), material)
    }

    fun verify(random: ByteArray, phoneAddress: String, glassAddress: String, material: ByteArray, received: ByteArray): Boolean {
        if (received.size != 32) return false
        val expected = runCatching { authValue(random, phoneAddress, glassAddress, material) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, received)
    }

    fun parseHex(value: String): ByteArray {
        require(value.length % 2 == 0 && hexPattern.matches(value)) { "Invalid hexadecimal encoding" }
        return ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun address(value: String): ByteArray {
        require(addressPattern.matches(value)) { "Invalid address encoding" }
        return parseHex(value.replace(":", ""))
    }

    private fun digest(vararg chunks: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        chunks.forEach(::update)
        digest()
    }
}
