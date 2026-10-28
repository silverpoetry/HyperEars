package dev.hyperears.protocol.apple

import org.junit.Assert.*
import org.junit.Test

class AppleBehaviorCodecTest {
    private fun frame(vararg values: Int) = values.map(Int::toByte).toByteArray()
    @Test fun knownTelemetryFramesProduceIndependentTypedStatesAcrossEverySplit() {
        val frames = listOf(
            frame(4,0,4,0,6,0,0,3),
            frame(4,0,4,0,0x4b,0,2,0,1,2),
            frame(4,0,4,0,0x19,0,8,1),
            frame(4,0,4,0,0x63,0,5,0,1,2,54,50,50),
        )
        val names = listOf("Wear", "Conversation", "Stem", "Equalizer")
        frames.forEachIndexed { index, bytes ->
            for (split in 0..bytes.size) {
                val decoder = AppleAapWireCodec.Decoder()
                val reports = decoder.offer(bytes.copyOfRange(0, split)) + decoder.offer(bytes.copyOfRange(split, bytes.size))
                assertEquals("frame=$index split=$split", listOf(names[index]), reports.map { it.javaClass.simpleName })
            }
        }
    }
    @Test fun invalidDomainsAndFullOpcodeNeverPublishTelemetry() {
        listOf(
            frame(4,0,4,0,6,0,4,0), frame(4,0,4,0,6,1,0,0),
            frame(4,0,4,0,0x19,0,9,1), frame(4,0,4,0,0x19,0,8,3),
            frame(4,0,4,0,0x63,0,5,0,2,2,50,50,50),
            frame(4,0,4,0,0x63,0,5,0,1,0,50,50,50),
            frame(4,0,4,0,0x63,0,5,0,1,2,101,50,50),
        ).forEach { assertTrue(AppleAapWireCodec.Decoder().offer(it).isEmpty()) }
    }
    @Test fun opaqueConversationPayloadDoesNotParseEmbeddedWearFrame() {
        val bytes = frame(4,0,4,0,0x4b,0,8,0,4,0,4,0,6,0,0,0)
        assertTrue(AppleAapWireCodec.Decoder().offer(bytes).isEmpty())
    }
}
