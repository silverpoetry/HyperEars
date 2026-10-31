package dev.hyperears.protocol.apple

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleAapWireCodecTest {
    @Test
    fun everyListeningModeSurvivesEveryFragmentBoundary() {
        AppleAapWireCodec.NoiseMode.entries.forEach { mode ->
            val packet = byteArrayOf(4, 0, 4, 0, 9, 0, 0x0d, mode.value.toByte(), 0, 0, 0)
            assertEquals(packet.toList(), AppleAapWireCodec.setNoiseMode(mode).toList())
            for (split in 1 until packet.size) {
                val decoder = AppleAapWireCodec.Decoder()
                assertEquals(emptyList<AppleAapWireCodec.State>(), decoder.offer(packet.copyOfRange(0, split)))
                assertEquals(listOf(AppleAapWireCodec.State.Noise(mode)), decoder.offer(packet.copyOfRange(split, packet.size)))
            }
        }
    }

    @Test
    fun invalidListeningValuesDoNotPoisonFollowingReports() {
        val valid = AppleAapWireCodec.setNoiseMode(AppleAapWireCodec.NoiseMode.ADAPTIVE)
        (0..255).filterNot { it in 1..4 }.forEach { invalid ->
            val packet = valid.copyOf().also { it[7] = invalid.toByte() }
            assertEquals(
                listOf(AppleAapWireCodec.State.Noise(AppleAapWireCodec.NoiseMode.ADAPTIVE)),
                AppleAapWireCodec.Decoder().offer(packet + valid),
            )
        }
    }

    @Test
    fun resetDiscardsIncompleteListeningReport() {
        val packet = AppleAapWireCodec.setNoiseMode(AppleAapWireCodec.NoiseMode.ADAPTIVE)
        val decoder = AppleAapWireCodec.Decoder()
        decoder.offer(packet.take(8).toByteArray())
        decoder.reset()
        assertEquals(emptyList<AppleAapWireCodec.State>(), decoder.offer(packet.drop(8).toByteArray()))
        assertEquals(listOf(AppleAapWireCodec.State.Noise(AppleAapWireCodec.NoiseMode.ADAPTIVE)), decoder.offer(packet))
    }

    @Test
    fun notificationStreamDecodesComponentBatteryAndNoiseState() {
        val battery = batteryPacket(
            component(4, 91, 1),
            component(2, 83, 2),
            component(8, 64, 2),
        )
        val noise = byteArrayOf(4, 0, 4, 0, 9, 0, 0x0D, 3, 0, 0, 0)
        val decoder = AppleAapWireCodec.Decoder()

        assertEquals(emptyList<AppleAapWireCodec.State>(), decoder.offer(battery.take(9).toByteArray()))
        assertEquals(
            listOf(
                AppleAapWireCodec.State.Battery(
                    left = AppleAapWireCodec.Component(91, true),
                    right = AppleAapWireCodec.Component(83, false),
                    case = AppleAapWireCodec.Component(64, false),
                ),
                AppleAapWireCodec.State.Noise(AppleAapWireCodec.NoiseMode.TRANSPARENCY),
            ),
            decoder.offer(battery.drop(9).toByteArray() + noise),
        )
        assertEquals(
            "04 00 04 00 09 00 0D 02 00 00 00",
            AppleAapWireCodec.setNoiseMode(AppleAapWireCodec.NoiseMode.ANC).hex(),
        )
    }

    @Test
    fun batteryPacketLengthFollowsComponentCountWithoutConsumingNextPacket() {
        val oneComponent = batteryPacket(component(4, 85, 2))
        val twoComponents = batteryPacket(
            component(2, 72, 1),
            component(8, 60, 2),
        )
        val noise = byteArrayOf(4, 0, 4, 0, 9, 0, 0x0D, 2, 0, 0, 0)
        val decoder = AppleAapWireCodec.Decoder()

        assertEquals(
            listOf(
                AppleAapWireCodec.State.Battery(
                    left = AppleAapWireCodec.Component(85, false),
                    right = AppleAapWireCodec.Component(null, false),
                    case = AppleAapWireCodec.Component(null, false),
                ),
                AppleAapWireCodec.State.Battery(
                    left = AppleAapWireCodec.Component(null, false),
                    right = AppleAapWireCodec.Component(72, true),
                    case = AppleAapWireCodec.Component(60, false),
                ),
                AppleAapWireCodec.State.Noise(AppleAapWireCodec.NoiseMode.ANC),
            ),
            decoder.offer(oneComponent + twoComponents + noise),
        )
    }

    @Test
    fun malformedBatteryCountIsDiscardedAndDecoderResynchronizes() {
        val malformed = byteArrayOf(4, 0, 4, 0, 4, 0, 4)
        val noise = byteArrayOf(4, 0, 4, 0, 9, 0, 0x0D, 1, 0, 0, 0)

        assertEquals(
            listOf(AppleAapWireCodec.State.Noise(AppleAapWireCodec.NoiseMode.OFF)),
            AppleAapWireCodec.Decoder().offer(malformed + noise),
        )
    }

    private fun component(type: Int, level: Int, status: Int): ByteArray =
        byteArrayOf(type.toByte(), 1, level.toByte(), status.toByte(), 1)

    private fun batteryPacket(vararg components: ByteArray): ByteArray =
        byteArrayOf(4, 0, 4, 0, 4, 0, components.size.toByte()) +
            components.fold(ByteArray(0)) { packet, component -> packet + component }

    private fun ByteArray.hex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
