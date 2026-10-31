package dev.hyperears.protocol.rayneo

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Legacy RayNeo JSON GATT framing. Does not open a connection or issue commands. */
object RayNeoBleWireCodec {
    private const val PAYLOAD_SIZE = 252

    /** One decoder per connection; reset on disconnect or ownership transfer. */
    class Decoder(private val maxBytes: Int = 64 * 1024) {
        private var pending: ByteArrayOutputStream? = null
        private var discarding = false

        init { require(maxBytes > 0) }

        fun reset() { pending = null; discarding = false }

        fun offer(packet: ByteArray): String? {
            if (packet.size !in 2..253 || packet[0].toInt() !in 0..2) {
                pending = null
                discarding = true
                return null
            }
            val marker = packet[0].toInt()
            if (marker == 1) { pending = ByteArrayOutputStream(); discarding = false }
            if (discarding) {
                if (marker == 0) reset()
                return null
            }
            if (marker == 2 && pending == null) return null
            val buffer = pending ?: ByteArrayOutputStream()
            if (buffer.size() + packet.size - 1 > maxBytes) {
                pending = null
                discarding = marker != 0
                return null
            }
            buffer.write(packet, 1, packet.size - 1)
            if (marker != 0) { pending = buffer; return null }
            reset()
            val bytes = buffer.toByteArray()
            var end = bytes.size
            while (end > 0 && bytes[end - 1] == 0.toByte()) end--
            if (end == 0) return null
            return runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, end)).toString()
            }.getOrNull()
        }
    }

    fun encode(message: String): List<ByteArray> {
        val bytes = message.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "Empty messages are not sent" }
        val count = (bytes.size - 1) / PAYLOAD_SIZE + 1
        return List(count) { index ->
            ByteArray(PAYLOAD_SIZE + 1).also { packet ->
                packet[0] = when {
                    index == count - 1 -> 0
                    index == 0 -> 1
                    else -> 2
                }.toByte()
                val offset = index * PAYLOAD_SIZE
                bytes.copyInto(packet, 1, offset, minOf(offset + PAYLOAD_SIZE, bytes.size))
            }
        }
    }
}
