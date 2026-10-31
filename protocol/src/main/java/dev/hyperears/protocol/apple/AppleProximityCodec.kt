package dev.hyperears.protocol.apple

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Reconstructed from HyperPods-X Proximity.kt / LibrePods; see source audit section 5. */
data class AppleProximityReport(
    val modelId: Int,
    val lidOpen: Boolean,
    val left: AppleAapWireCodec.Component,
    val right: AppleAapWireCodec.Component,
    val case: AppleAapWireCodec.Component,
    val precise: Boolean,
)

object AppleProximityCodec {
    fun messages(data: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var cursor = 0
        while (cursor < data.size) {
            if (cursor + 2 > data.size) return emptyList()
            val end = cursor + 2 + data.u(cursor + 1)
            if (end <= cursor + 2 || end > data.size) return emptyList()
            if (data.u(cursor) == 7) result += data.copyOfRange(cursor, end)
            cursor = end
        }
        return result
    }

    fun matchesIdentity(address: String, irk: ByteArray): Boolean = runCatching {
        if (irk.size != 16 || !address.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"))) return false
        val rpa = address.split(':').map { it.toInt(16).toByte() }.reversed().toByteArray()
        if (rpa.u(5) and 0xc0 != 0x40) return false
        val block = ByteArray(16).also { rpa.copyInto(it, 0, 3, 6) }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(irk.reversedArray(), "AES"))
        cipher.doFinal(block.reversedArray()).reversedArray().take(3) == rpa.take(3)
    }.getOrDefault(false)

    /** Identity must be established by the owner before entering this decoder. ECB is not a MAC. */
    internal fun decode(data: ByteArray, encryptionKey: ByteArray?): AppleProximityReport? {
        if (data.size < 11 || data.u(0) != 7 || data.u(1) + 2 != data.size) return null
        val swapped = data.u(5) and 0x20 == 0
        val plain = if (data.size >= 27 && encryptionKey?.size == 16) runCatching {
            Cipher.getInstance("AES/ECB/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"))
                doFinal(data.copyOfRange(data.size - 16, data.size))
            }
        }.getOrNull() else null
        // A malformed precise payload must not silently become credible approximate telemetry.
        if (plain != null && (1..3).any { plain.u(it) != 255 && plain.u(it) and 127 > 100 }) {
            plain.fill(0)
            return null
        }
        fun precise(index: Int): AppleAapWireCodec.Component {
            val raw = plain!!.u(index)
            return AppleAapWireCodec.Component((raw and 127).takeIf { raw != 255 }, raw != 255 && raw and 128 != 0)
        }
        fun approximate(n: Int, mask: Int): AppleAapWireCodec.Component = AppleAapWireCodec.Component(
            when (n) { in 0..9 -> n * 10; in 10..14 -> 100; else -> null },
            n != 15 && (data.u(7) shr 4) and mask != 0,
        )
        val left = if (plain != null) precise(if (swapped) 2 else 1)
            else approximate(if (swapped) data.u(6) shr 4 else data.u(6) and 15, if (swapped) 2 else 1)
        val right = if (plain != null) precise(if (swapped) 1 else 2)
            else approximate(if (swapped) data.u(6) and 15 else data.u(6) shr 4, if (swapped) 1 else 2)
        val case = if (plain != null) precise(3) else approximate(data.u(7) and 15, 4)
        plain?.fill(0)
        return AppleProximityReport((data.u(3) shl 8) or data.u(4), data.u(8) and 8 == 0, left, right, case, plain != null)
    }
}

class AppleProximityLidSession {
    var open: Boolean? = null
        private set
    private var lastCaptured = -1L
    private var candidateAt: Long? = null
    private var lastPopup = -3000L
    fun observe(value: Boolean, capturedAt: Long, now: Long): Boolean {
        if (capturedAt == lastCaptured && value != open) {
            // Equal-time contradictory observations are ambiguous, not an opening edge.
            candidateAt = null
            return false
        }
        if (capturedAt <= lastCaptured || now - capturedAt !in 0..15000) return false
        lastCaptured = capturedAt
        if (open != value) {
            open = value
            candidateAt = if (value && now - lastPopup >= 3000) now else null
        }
        return true
    }
    fun confirmationDelay(now: Long): Long? = candidateAt?.let { (it + 900 - now).coerceAtLeast(0) }
    fun confirm(now: Long): Boolean {
        val started = candidateAt ?: return false
        if (open != true || now - started < 900 || now - lastCaptured !in 0..15000) return false
        candidateAt = null
        lastPopup = now
        return true
    }
}
