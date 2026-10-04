package dev.hyperears.runtime

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CommandSequenceWriterTest {
    @Test
    fun zeroGapWritesBothFramesImmediatelyInOrder() = runBlocking {
        val events = mutableListOf<String>()
        writeCommandSequence(listOf(byteArrayOf(0x92.toByte()), byteArrayOf(0x79)), 0L,
            wait = { events += "wait:$it" },
            write = { events += "write:${it[0].toInt() and 0xFF}" })
        assertEquals(listOf("write:146", "write:121"), events)
    }

    @Test
    fun defaultPacingWaitsOnlyBetweenFrames() = runBlocking {
        val events = mutableListOf<String>()
        writeCommandSequence(listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)), 120L,
            wait = { events += "wait:$it" },
            write = { events += "write:${it[0]}" })
        assertEquals(listOf("write:1", "wait:120", "write:2", "wait:120", "write:3"), events)
    }

    @Test
    fun singleAndEmptySequencesNeverWait() = runBlocking {
        val events = mutableListOf<String>()
        writeCommandSequence(emptyList(), 120L,
            wait = { fail("No wait for an empty sequence") }, write = { fail("No empty write") })
        writeCommandSequence(listOf(byteArrayOf(1)), 120L,
            wait = { fail("No trailing wait") }, write = { events += "write" })
        assertEquals(listOf("write"), events)
    }

    @Test
    fun failedWriteStopsTheRemainingSequence() = runBlocking {
        var writes = 0
        try {
            writeCommandSequence(listOf(byteArrayOf(1), byteArrayOf(2)), 0L,
                wait = { fail("No wait") }, write = { writes++; throw IOException("test failure") })
            fail("Expected write failure")
        } catch (_: IOException) {
            assertEquals(1, writes)
        }
    }

    @Test
    fun negativeGapIsRejectedBeforeWriting() = runBlocking {
        try {
            writeCommandSequence(listOf(byteArrayOf(1)), -1L,
                wait = { fail("No wait") }, write = { fail("No write") })
            fail("Expected invalid gap")
        } catch (_: IllegalArgumentException) {
            // Invalid adapter policy cannot reach the channel.
        }
    }
}
