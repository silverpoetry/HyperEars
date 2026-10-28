package dev.hyperears.protocol.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleDeviceInformationTest {
    @Test
    fun validDeviceInfoIsDecodedWithoutScanningOpaqueHeadersAtEveryBoundary() {
        val frame = fixture()
        for (split in 1 until frame.size) {
            val decoder = AppleAapWireCodec.Decoder()
            assertTrue("split $split", decoder.offer(frame.copyOfRange(0, split)).isEmpty())
            assertEquals(listOf(identity), decoder.offer(frame.copyOfRange(split, frame.size)))
        }
    }

    @Test
    fun concatenatedIdentityAndNoiseKeepWireOrder() {
        assertEquals(listOf(identity, AppleAapWireCodec.State.Noise(AppleAapWireCodec.NoiseMode.ANC), identity),
            AppleAapWireCodec.Decoder().offer(fixture() + noise + fixture()))
    }

    @Test
    fun resetDiscardsEachIncompleteIdentity() {
        val frame = fixture(opaque = ByteArray(34))
        for (split in 1 until frame.size) {
            val decoder = AppleAapWireCodec.Decoder()
            decoder.offer(frame.copyOfRange(0, split))
            decoder.reset()
            assertTrue(decoder.offer(frame.copyOfRange(split, frame.size)).isEmpty())
            assertEquals(listOf(identity), decoder.offer(frame))
        }
    }

    @Test
    fun malformedShapeAndEncodingAreRejectedWithoutSwallowingFollowingFrame() {
        val valid = fixture(opaque = ByteArray(34))
        val malformed = listOf(
            resize(valid + byteArrayOf(99)),
            resize(valid.dropLast(1).toByteArray()),
            fixture(name = byteArrayOf(0xc3.toByte(), 0x28)),
            resize(valid.copyOfRange(0, 40)),
        )
        malformed.forEach { frame ->
            assertEquals(listOf(AppleAapWireCodec.State.Noise(AppleAapWireCodec.NoiseMode.ANC)),
                AppleAapWireCodec.Decoder().offer(frame + noise))
        }
    }

    @Test
    fun projectionUsesOnlyPositionalModelAndFirmware() {
        assertEquals(listOf(AppleAapWireCodec.State.DeviceInformation("unknown", "7A305", "A3441")),
            AppleAapWireCodec.Decoder().offer(fixture()))
        val empty = resize(byteArrayOf(4, 0, 4, 0, 0x1d, 0, 55, 0, 0, 99, 88) + ByteArray(47))
        assertEquals(58, empty.size)
        assertEquals(listOf(AppleAapWireCodec.State.DeviceInformation("", "")),
            AppleAapWireCodec.Decoder().offer(empty))
    }

    @Test
    fun allThirteenTextFieldsRejectMalformedUtf8IncludingDiscardedPrivateFields() {
        for (badIndex in 0 until 13) {
            val fields = List(13) { index -> if (index == badIndex) byteArrayOf(0xff.toByte(), 0) else byteArrayOf(0) }
            val body = fields.take(11).fold(byteArrayOf()) { a, b -> a + b } + ByteArray(34) +
                fields.drop(11).fold(byteArrayOf()) { a, b -> a + b }
            val frame = resize(byteArrayOf(4, 0, 4, 0, 0x1d, 0, 0, 0, 0, 0, 0) + body)
            assertEquals("field $badIndex", listOf(AppleAapWireCodec.State.Noise(AppleAapWireCodec.NoiseMode.ANC)),
                AppleAapWireCodec.Decoder().offer(frame + noise))
        }
    }

    @Test
    fun maximumDeclaredFrameIsBoundedAndDoesNotScanBodyOrConsumeNextPacket() {
        val frame = resize(ByteArray(9 + 65535).also {
            byteArrayOf(4, 0, 4, 0, 0x1d, 0).copyInto(it)
            noise.copyInto(it, 40000)
        })
        val decoder = AppleAapWireCodec.Decoder()
        assertTrue(decoder.offer(frame.copyOfRange(0, 65000)).isEmpty())
        assertEquals(listOf(AppleAapWireCodec.State.Noise(AppleAapWireCodec.NoiseMode.ANC)),
            decoder.offer(frame.copyOfRange(65000, frame.size) + noise))
        assertEquals(3000, decoder.offer(ByteArray(80000) + fixture().repeatFrames(3000)).size)
    }

    @Test
    fun shortDeclaredLengthsAndOverlongProjectedTextAreRejected() {
        for (length in 0..48) {
            val frame = resize(byteArrayOf(4, 0, 4, 0, 0x1d, 0, 0, 0, 0) + ByteArray(length))
            assertEquals(listOf(AppleAapWireCodec.State.Noise(AppleAapWireCodec.NoiseMode.ANC)),
                AppleAapWireCodec.Decoder().offer(frame + noise))
        }
    }

    @Test
    fun projectedFieldsAreBoundedWithoutTruncatingModelAndLargeNameIsNotRetained() {
        listOf(127, 128).forEach { size ->
            assertEquals(listOf(AppleAapWireCodec.State.DeviceInformation("A".repeat(size), "F".repeat(size), "A3441")),
                AppleAapWireCodec.Decoder().offer(fixture(model = "A".repeat(size), firmware = "F".repeat(size))))
        }
        assertTrue(AppleAapWireCodec.Decoder().offer(fixture(model = "A".repeat(129))).isEmpty())
        assertTrue(AppleAapWireCodec.Decoder().offer(fixture(firmware = "F".repeat(129))).isEmpty())
        val frame = fixture(name = ByteArray(65474) { 65 })
        assertEquals(9 + 65535, frame.size)
        val decoder = AppleAapWireCodec.Decoder()
        assertTrue(decoder.offer(frame.copyOfRange(0, frame.size - 1)).isEmpty())
        assertEquals(listOf(identity.copy(reportedName = ""), AppleAapWireCodec.State.Noise(AppleAapWireCodec.NoiseMode.ANC)),
            decoder.offer(frame.takeLast(1).toByteArray() + noise))
    }

    private fun ByteArray.repeatFrames(count: Int): ByteArray = ByteArray(size * count) { this[it % size] }

    private val identity = AppleAapWireCodec.State.DeviceInformation("unknown", "7A305", "A3441")
    private val noise = AppleAapWireCodec.setNoiseMode(AppleAapWireCodec.NoiseMode.ANC)

    private fun fixture(name: ByteArray = "A3441".toByteArray(), model: String = "unknown", firmware: String = "7A305", opaque: ByteArray = (noise + ByteArray(34)).copyOf(34)): ByteArray {
        val fields = listOf(name, model.toByteArray(), byteArrayOf(), byteArrayOf(), firmware.toByteArray()) + List(6) { byteArrayOf() }
        val body = fields.fold(byteArrayOf()) { acc, field -> acc + field + byteArrayOf(0) } + opaque + byteArrayOf(0, 0)
        return resize(byteArrayOf(4, 0, 4, 0, 0x1d, 0, 99, 0, 0, 77, 66) + body)
    }

    private fun resize(frame: ByteArray): ByteArray = frame.also {
        val length = frame.size - 9
        it[7] = length.toByte()
        it[8] = (length shr 8).toByte()
    }
}
