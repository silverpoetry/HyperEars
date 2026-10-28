package dev.hyperears.runtime

import org.junit.Assert.*
import org.junit.Test

class ProtocolPayloadLogTest {
    @Test fun arbitraryFragmentsNeverExposeSensitiveStreamBytes() {
        val packet = byteArrayOf(4, 0, 4, 0, 0x31, 0, 1, 1, 0, 16, 0) + ByteArray(16) { 0x5a }
        (1 until packet.size).forEach { split ->
            listOf(packet.copyOfRange(0, split), packet.copyOfRange(split, packet.size)).forEach {
                assertEquals("<redacted ${it.size} bytes>", protocolPayloadLog(it, true))
            }
        }
        assertEquals("04 00 FF", protocolPayloadLog(byteArrayOf(4, 0, -1), false))
    }
}
