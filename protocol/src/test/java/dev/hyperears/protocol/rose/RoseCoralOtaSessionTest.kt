package dev.hyperears.protocol.rose

import org.junit.Assert.*
import org.junit.Test

/** Reconstructed fixtures; no headset transfer or vendor authenticity claim. */
class RoseCoralOtaSessionTest {
    private fun image(size: Int = 24) = RoseCoralOtaImage.fromHex("coral", "self", 51,
        ByteArray(size) { it.toByte() }.joinToString("") { "%02x".format(it) })
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun write(effects: List<RoseCoralOtaSession.Effect>) = effects.filterIsInstance<RoseCoralOtaSession.Effect.Write>().single()
    private fun finish(s: RoseCoralOtaSession, w: RoseCoralOtaSession.Effect.Write) = s.onWriteCompleted(w.generation, w.id, true)
    private fun ready(s: RoseCoralOtaSession, block: Int = 24, packet: Int = 16): List<RoseCoralOtaSession.Effect> {
        finish(s, write(s.start()))
        finish(s, write(s.onBytes(s.generation, bytes(0x91, 0, 1, 0, 0))))
        return s.onBytes(s.generation, bytes(0x91, 1, 2, 0, 0, 0, 0, block, block ushr 8, block ushr 16, block ushr 24, packet, packet ushr 8, 1))
    }
    private fun drain(s: RoseCoralOtaSession, initial: List<RoseCoralOtaSession.Effect>): List<RoseCoralOtaSession.Effect> {
        var effects = initial
        while (effects.any { it is RoseCoralOtaSession.Effect.Write }) effects = finish(s, write(effects))
        return effects
    }

    @Test fun rejectsWrongTargetVersionAndMalformedImage() {
        for ((model, target, version, hex) in listOf(
            listOf("Coral", "self", "51", "00"), listOf("coral", "peer", "51", "00"),
            listOf("coral", "self", "0", "00"), listOf("coral", "self", "65536", "00"),
            listOf("coral", "self", "51", "0"), listOf("coral", "self", "51", "gg"),
            listOf("coral", "self", "51", ""),
        )) assertThrows(IllegalArgumentException::class.java) { RoseCoralOtaImage.fromHex(model, target, version.toInt(), hex) }
    }

    @Test fun handshakeGatesMetadataAndTimersFollowWrites() {
        val s = RoseCoralOtaSession(image())
        val start = s.start()
        assertArrayEquals(bytes(0x91, 0, 1), write(start).bytes)
        assertFalse(start.any { it is RoseCoralOtaSession.Effect.ArmDeadline })
        assertEquals(6000L, finish(s, write(start)).filterIsInstance<RoseCoralOtaSession.Effect.ArmDeadline>().single().delayMs)
        for (b in bytes(0x91, 0, 1, 0)) assertTrue(s.onBytes(s.generation, byteArrayOf(b)).isEmpty())
        val metadata = write(s.onBytes(s.generation, bytes(0)))
        assertArrayEquals(bytes(0x91, 1, 2, 51, 0) + image().md5Prefix(), metadata.bytes)
    }

    @Test fun sequentialPacketsRetryIdenticallyAndWaitForFinalFf() {
        val s = RoseCoralOtaSession(image())
        val first = write(ready(s))
        assertArrayEquals(bytes(0xA0, 2, 0, 0, 0, 0, 24, 0, 0, 0, 0, 1, 2, 3, 4, 5), first.bytes)
        val continuation = write(finish(s, first))
        assertArrayEquals(bytes(0x20, 3) + ByteArray(14) { (it + 6).toByte() }, continuation.bytes)
        val last = write(finish(s, continuation))
        assertArrayEquals(bytes(0x20, 4, 20, 21, 22, 23), last.bytes)
        val ackWait = finish(s, last)
        assertEquals(5000L, ackWait.filterIsInstance<RoseCoralOtaSession.Effect.ArmDeadline>().single().delayMs)
        val retry = write(s.onBytes(s.generation, bytes(0x90, 99, 0x40)))
        assertArrayEquals(first.bytes, retry.bytes)
        drain(s, listOf(retry))
        s.onBytes(s.generation, bytes(0x90, 99, 0))
        assertEquals(RoseCoralOtaSession.State.AWAIT_COMPLETION, s.state)
        assertEquals(24, s.committedBytes)
        assertTrue(s.onBytes(s.generation, bytes(0x90, 99, 0xFF)).any { it is RoseCoralOtaSession.Effect.TransferAccepted })
        assertEquals(RoseCoralOtaSession.State.RECONNECTING, s.state)
        assertTrue(s.onBytes(s.generation, bytes(0x90, 99, 0xFF)).isEmpty())
    }

    @Test fun earlyAckDoesNotOverlapWriterAndCancelInvalidatesCallbacks() {
        val s = RoseCoralOtaSession(image())
        val w = write(ready(s))
        assertTrue(s.onBytes(s.generation, bytes(0x90, 0, 0)).isEmpty())
        assertEquals(0, s.committedBytes)
        val old = s.generation
        s.cancel()
        assertTrue(s.onWriteCompleted(old, w.id, true).isEmpty())
        assertTrue(s.onBytes(old, bytes(0x90, 0, 0xFF)).isEmpty())
        assertEquals(RoseCoralOtaSession.State.CANCELLED, s.state)
    }

    @Test fun malformedParameterAndUnknownFrameAreTerminal() {
        for ((block, packet) in listOf(0 to 16, 24 to 2, -1 to 16)) {
            val s = RoseCoralOtaSession(image())
            ready(s, block, packet)
            assertEquals(RoseCoralOtaSession.State.FAILED, s.state)
        }
        val s = RoseCoralOtaSession(image())
        finish(s, write(s.start()))
        s.onBytes(s.generation, bytes(0x92, 0, 1))
        assertEquals(RoseCoralOtaSession.State.FAILED, s.state)
    }

    @Test fun boundedHandshakeRecoveryAndStaleDeadline() {
        val s = RoseCoralOtaSession(image())
        var effects = finish(s, write(s.start()))
        repeat(2) {
            val d = effects.filterIsInstance<RoseCoralOtaSession.Effect.ArmDeadline>().single()
            effects = s.onDeadline(d.generation, d.id)
            assertTrue(s.onDeadline(d.generation, d.id).isEmpty())
            effects = finish(s, write(effects))
        }
        val d = effects.filterIsInstance<RoseCoralOtaSession.Effect.ArmDeadline>().single()
        s.onDeadline(d.generation, d.id)
        assertEquals(RoseCoralOtaSession.State.FAILED, s.state)
    }

    @Test fun coalescedPauseAndPartialContinueRetainDeadlineAndNeverCommit() {
        val s = RoseCoralOtaSession(image())
        drain(s, ready(s))
        s.onBytes(s.generation, bytes(0x90, 0, 0xFD, 0x90))
        assertEquals(RoseCoralOtaSession.State.AWAIT_BLOCK_ACK, s.state)
        s.onBytes(s.generation, bytes(1, 0xFE))
        assertEquals(0, s.committedBytes)
    }

    @Test fun effectBytesCannotReplaceImageAndDigestMismatchIsRejected() {
        val artifact = image()
        assertThrows(IllegalArgumentException::class.java) { RoseCoralOtaImage.fromHex("coral", "self", 51, "00", "0".repeat(64)) }
        val s = RoseCoralOtaSession(artifact)
        val w = write(s.start())
        w.bytes.fill(0)
        assertArrayEquals(bytes(0x91, 0, 1), w.bytes)
    }

    @Test fun finalSuccessRequiresReadbackAndTransportLossNeverCompletes() {
        val s = RoseCoralOtaSession(image())
        drain(s, ready(s))
        s.onBytes(s.generation, bytes(0x90, 0, 0xFF))
        assertEquals(RoseCoralOtaSession.State.RECONNECTING, s.state)
        s.onVersionReadback(true, 50)
        assertEquals(RoseCoralOtaSession.State.FAILED, s.state)
        val lost = RoseCoralOtaSession(image())
        lost.start()
        lost.onTransportLost()
        assertEquals(RoseCoralOtaSession.State.FAILED, lost.state)
    }

    @Test fun sequenceWrapAndShortFinalBlockUseAcknowledgedOffset() {
        val s = RoseCoralOtaSession(image(2040))
        var effects = ready(s, 2030, 10)
        val sequences = mutableListOf<Int>()
        while (effects.any { it is RoseCoralOtaSession.Effect.Write }) {
            val w = write(effects)
            sequences += w.bytes[1].toInt() and 255
            effects = finish(s, w)
        }
        assertTrue(sequences.zipWithNext().any { it.first == 255 && it.second == 0 })
        assertEquals(0, s.committedBytes)
        val next = write(s.onBytes(s.generation, bytes(0x90, 0, 0)))
        assertArrayEquals(bytes(0xEE, 7, 0, 0, 10, 0, 0, 0), next.bytes.copyOfRange(2, 10))
        assertEquals(2030, s.committedBytes)
        drain(s, listOf(next))
        s.onBytes(s.generation, bytes(0x90, 0, 0, 0x90, 1, 0xFF))
        assertEquals(RoseCoralOtaSession.State.RECONNECTING, s.state)
        s.onVersionReadback(true, 51)
        assertEquals(RoseCoralOtaSession.State.COMPLETE, s.state)
    }

    @Test fun earlyAckSettlesOnlyAfterAllPacketWritesAndErrorNeverSucceeds() {
        val s = RoseCoralOtaSession(image())
        val first = ready(s)
        s.onBytes(s.generation, bytes(0x90, 0, 0))
        drain(s, first)
        assertEquals(RoseCoralOtaSession.State.AWAIT_COMPLETION, s.state)
        s.onBytes(s.generation, bytes(0x90, 0, 0x80))
        assertEquals(RoseCoralOtaSession.State.FAILED, s.state)
    }

    @Test fun rxBoundAndExtraPayloadFailWithoutSuccessOrFurtherWrites() {
        val s = RoseCoralOtaSession(image())
        drain(s, ready(s))
        val effects = s.onBytes(s.generation, bytes(0x90, 0, 0xFF, 0x55))
        assertEquals(RoseCoralOtaSession.State.FAILED, s.state)
        assertFalse(effects.any { it is RoseCoralOtaSession.Effect.TransferAccepted })
        val other = RoseCoralOtaSession(image())
        other.start()
        other.onBytes(other.generation, ByteArray(65))
        assertEquals(RoseCoralOtaSession.State.FAILED, other.state)
    }
}
