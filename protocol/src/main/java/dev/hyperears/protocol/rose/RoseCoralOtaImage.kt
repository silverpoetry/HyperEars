package dev.hyperears.protocol.rose

import java.security.MessageDigest

/** Immutable exact-model artifact binding, not proof of vendor authenticity. */
class RoseCoralOtaImage private constructor(val version: Int, private val content: ByteArray) {
    val model = "coral"
    val target = "self"
    val size: Int get() = content.size
    val sha256: String = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    /** AB protocol checksum field only; MD5 is not a signature or trust decision. */
    fun md5Prefix(): ByteArray = MessageDigest.getInstance("MD5").digest(content).copyOf(4)
    internal fun slice(offset: Int, length: Int): ByteArray = content.copyOfRange(offset, offset + length)

    companion object {
        // Independent memory policy, not a claimed firmware/device limit.
        const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
        fun fromHex(model: String, target: String, version: Int, hex: String, expectedSha256: String? = null): RoseCoralOtaImage {
            require(model == "coral" && target == "self") { "Exact Coral self artifact required" }
            require(version in 1..65535) { "Version must fit positive LE16" }
            require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.length <= MAX_IMAGE_BYTES * 2) { "Invalid image length" }
            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid hex image" }
            val image = RoseCoralOtaImage(version, ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
            if (expectedSha256 != null) {
                require(expectedSha256.length == 64 && expectedSha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid SHA-256" }
                require(image.sha256.equals(expectedSha256, ignoreCase = true)) { "Artifact digest mismatch" }
            }
            return image
        }
    }
}
