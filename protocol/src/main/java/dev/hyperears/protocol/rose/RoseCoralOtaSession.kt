package dev.hyperears.protocol.rose

import java.util.concurrent.atomic.AtomicLong

/**
 * Pure, single-caller AB OTA engine. Source: rose-coral-ota-static-spec.md (ROSELINK 3.8.2).
 * The exclusive transport owner executes effects in order on its serialized event loop.
 * Feed raw OTA bytes only, never normal DD traffic. Each Write MUST complete before the next.
 * Deadlines are relative to effect delivery and callbacks must preserve both generation and id.
 * Receive sequence matching is not established by the source: sequence is observed, never
 * interpreted as a device cursor or an authenticated ACK. Hardware revalidation is outstanding.
 */
class RoseCoralOtaSession(private val image: RoseCoralOtaImage) {
    enum class State { IDLE, AWAIT_START, AWAIT_PARAMETERS, WRITING_BLOCK, AWAIT_BLOCK_ACK, AWAIT_COMPLETION, RECONNECTING, COMPLETE, FAILED, CANCELLED }
    sealed interface Effect {
        class Write(val generation: Long, val id: Long, bytes: ByteArray) : Effect {
            private val payload = bytes.copyOf()
            val bytes: ByteArray get() = payload.copyOf()
        }
        data class ArmDeadline(val generation: Long, val id: Long, val delayMs: Long) : Effect
        data class CancelDeadline(val generation: Long, val id: Long) : Effect
        data class Progress(val committed: Int, val pending: Int, val total: Int) : Effect
        data class StatusObserved(val sequence: Int, val status: Int) : Effect
        /** Device FF received. Owner must reconnect the same device and read back version. */
        data class TransferAccepted(val expectedVersion: Int) : Effect
        data class Failed(val reason: String) : Effect
        data object Cancelled : Effect
        data object Complete : Effect
    }

    var state = State.IDLE
        private set
    var generation = generations.incrementAndGet()
        private set
    var committedBytes = 0
        private set
    var pendingBytes = 0
        private set
    private var serial = 0L
    private var sequence = 0
    private var writeId: Long? = null
    private var deadlineId: Long? = null
    private var rx = byteArrayOf()
    private var blockSize = 0
    private var packetSize = 0
    private var blockStart = 0
    private var blockSequence = 0
    private var blockStream = byteArrayOf()
    private var blockCursor = 0
    private var retries = 0
    private var restarts = 0
    private val terminal get() = state in setOf(State.FAILED, State.CANCELLED, State.COMPLETE, State.RECONNECTING)

    fun start(): List<Effect> = mutableListOf<Effect>().also { out ->
        if (state == State.IDLE) handshake(out)
    }

    fun onWriteCompleted(generation: Long, id: Long, success: Boolean): List<Effect> = mutableListOf<Effect>().also { out ->
        if (generation != this.generation || terminal || writeId != id) return@also
        writeId = null
        if (!success) { fail("Transport write failed; connection state is uncertain", out); return@also }
        if (state == State.WRITING_BLOCK && blockCursor < blockStream.size) {
            // A complete retry request received during a write supersedes further block packets.
            if (rx.size == 3 && u(rx[0]) == 0x90 && u(rx[2]) == 0x40) {
                rx = byteArrayOf()
                recover(out)
            } else sendBlockPacket(out)
        } else {
            if (state == State.WRITING_BLOCK) state = State.AWAIT_BLOCK_ACK
            arm(if (state == State.AWAIT_START || state == State.AWAIT_PARAMETERS) 6000 else 5000, out)
            parse(out)
        }
    }

    fun onBytes(generation: Long, bytes: ByteArray): List<Effect> = mutableListOf<Effect>().also { out ->
        if (generation != this.generation || terminal || state == State.IDLE || bytes.isEmpty()) return@also
        if (bytes.size > MAX_RX_BYTES - rx.size) { fail("OTA receive bound exceeded", out); return@also }
        rx += bytes
        // Wait for pending writes; a synchronous RX callback is not permission to overlap writers.
        if (writeId == null) parse(out)
    }

    fun onDeadline(generation: Long, id: Long): List<Effect> = mutableListOf<Effect>().also { out ->
        if (generation != this.generation || terminal || deadlineId != id) return@also
        deadlineId = null
        if (rx.isNotEmpty()) fail("Incomplete or ambiguous OTA response", out)
        else if (state == State.AWAIT_BLOCK_ACK) recover(out)
        else restart(out)
    }

    /** Socket loss cannot be interpreted as FF or successful reboot. */
    fun onTransportLost(): List<Effect> = mutableListOf<Effect>().also { out ->
        if (!terminal && state != State.IDLE) fail("OTA transport lost", out)
    }

    /** The owner validates device identity before invoking this after same-device reconnect. */
    fun onVersionReadback(sameDevice: Boolean, version: Int): List<Effect> = mutableListOf<Effect>().also { out ->
        if (state != State.RECONNECTING) return@also
        if (!sameDevice || version != image.version) fail("Reconnect identity/version mismatch", out)
        else { state = State.COMPLETE; out += Effect.Complete }
    }

    fun cancel(): List<Effect> = mutableListOf<Effect>().also { out ->
        if (state == State.COMPLETE || state == State.CANCELLED || state == State.FAILED) return@also
        clear(out)
        generation = generations.incrementAndGet()
        state = State.CANCELLED
        out += Effect.Cancelled
    }

    private fun parse(out: MutableList<Effect>) {
        while (rx.isNotEmpty() && writeId == null && !terminal) {
            val expectedType = when (state) { State.AWAIT_START -> 1; State.AWAIT_PARAMETERS -> 2; else -> null }
            val length = if (expectedType != null) { if (expectedType == 1) 5 else 14 } else 3
            if (u(rx[0]) != if (expectedType != null) 0x91 else 0x90) {
                fail("Unexpected OTA opcode for $state", out); return
            }
            if (rx.size < 3) return
            if (expectedType != null && u(rx[2]) != expectedType) { fail("Unexpected handshake type", out); return }
            if (rx.size < length) return
            val frame = rx.copyOfRange(0, length)
            rx = rx.copyOfRange(length, rx.size)
            // No length grammar exists for status extras or unsolicited handshake data. Fail closed.
            if (rx.isNotEmpty() && (expectedType != null || rx.indices.step(3).any { u(rx[it]) != 0x90 })) {
                fail("Ambiguous OTA response layout", out); return
            }
            if (expectedType == 1) {
                cancelDeadline(out)
                state = State.AWAIT_PARAMETERS
                send(packet(0x91, byteArrayOf(2, image.version.toByte(), (image.version ushr 8).toByte()) + image.md5Prefix()), out)
            } else if (expectedType == 2) parameters(frame, out)
            else status(u(frame[1]), u(frame[2]), out)
        }
    }

    private fun parameters(frame: ByteArray, out: MutableList<Effect>) {
        val offset = le32(frame, 3)
        val block = le32(frame, 7)
        val advertisedPacket = u(frame[11]) or (u(frame[12]) shl 8)
        val packet = if (advertisedPacket == 0) 240 else advertisedPacket
        // Signed-load sentinel and negative packet interpretations are unproven: reject them.
        if (offset < 0 || offset > image.size || block <= 0 || packet !in 3..32767 || u(frame[13]) != 1) {
            fail("Invalid/unsupported OTA parameters", out); return
        }
        cancelDeadline(out)
        committedBytes = offset
        pendingBytes = offset
        blockSize = block
        packetSize = packet
        out += Effect.Progress(committedBytes, pendingBytes, image.size)
        if (offset == image.size) { state = State.AWAIT_COMPLETION; arm(5000, out) }
        else beginBlock(out)
    }

    private fun beginBlock(out: MutableList<Effect>) {
        blockStart = committedBytes
        blockSequence = sequence
        val count = minOf(blockSize, image.size - blockStart)
        pendingBytes = blockStart + count
        blockStream = packet(0xA0, le(blockStart) + le(count)) + image.slice(blockStart, count)
        blockCursor = 0
        state = State.WRITING_BLOCK
        out += Effect.Progress(committedBytes, pendingBytes, image.size)
        sendBlockPacket(out)
    }

    private fun sendBlockPacket(out: MutableList<Effect>) {
        val first = blockCursor == 0
        val count = minOf(if (first) packetSize else packetSize - 2, blockStream.size - blockCursor)
        val payload = blockStream.copyOfRange(blockCursor, blockCursor + count)
        blockCursor += count
        send(if (first) payload else packet(0x20, payload), out)
    }

    private fun status(seq: Int, status: Int, out: MutableList<Effect>) {
        out += Effect.StatusObserved(seq, status)
        when (status) {
            0xFD, 0xFE -> Unit // Vendor logs these as unsupported; retain watchdog and progress.
            0x80, 1, 2, 3, 0x0B, 0x41 -> fail("Device OTA error 0x${status.toString(16)}", out)
            0x40 -> if (state == State.AWAIT_BLOCK_ACK) recover(out) else fail("Retry outside pending block", out)
            0 -> {
                if (state != State.AWAIT_BLOCK_ACK) { fail("ACK without pending block", out); return }
                cancelDeadline(out)
                committedBytes = pendingBytes
                retries = 0
                out += Effect.Progress(committedBytes, pendingBytes, image.size)
                if (committedBytes == image.size) { state = State.AWAIT_COMPLETION; arm(5000, out) }
                else if (rx.isNotEmpty()) fail("Multiple ACKs for one block", out)
                else beginBlock(out)
            }
            0xFF -> {
                if (pendingBytes != image.size || state !in setOf(State.AWAIT_BLOCK_ACK, State.AWAIT_COMPLETION)) {
                    fail("Premature OTA completion", out); return
                }
                if (rx.isNotEmpty()) { fail("Unexpected data after completion", out); return }
                committedBytes = image.size
                clear(out)
                state = State.RECONNECTING
                out += Effect.Progress(committedBytes, pendingBytes, image.size)
                out += Effect.TransferAccepted(image.version)
            }
            else -> fail("Unknown status or unsupported status payload", out)
        }
    }

    private fun recover(out: MutableList<Effect>) {
        cancelDeadline(out)
        if (retries++ == 0) {
            rx = byteArrayOf()
            sequence = blockSequence
            committedBytes = blockStart
            beginBlock(out)
        } else restart(out)
    }

    private fun restart(out: MutableList<Effect>) {
        if (restarts >= 2) { fail("OTA handshake restart limit exhausted", out); return }
        restarts++
        clear(out)
        handshake(out)
    }

    private fun handshake(out: MutableList<Effect>) {
        sequence = 0
        committedBytes = 0
        pendingBytes = 0
        retries = 0
        state = State.AWAIT_START
        send(packet(0x91, byteArrayOf(1)), out)
    }

    private fun packet(opcode: Int, payload: ByteArray): ByteArray {
        val result = byteArrayOf(opcode.toByte(), sequence.toByte()) + payload
        sequence = (sequence + 1) and 255
        return result
    }
    private fun send(bytes: ByteArray, out: MutableList<Effect>) {
        check(writeId == null)
        writeId = ++serial
        out += Effect.Write(generation, serial, bytes)
    }
    private fun arm(delay: Long, out: MutableList<Effect>) {
        cancelDeadline(out)
        deadlineId = ++serial
        out += Effect.ArmDeadline(generation, serial, delay)
    }
    private fun cancelDeadline(out: MutableList<Effect>) {
        deadlineId?.let { out += Effect.CancelDeadline(generation, it) }
        deadlineId = null
    }
    private fun clear(out: MutableList<Effect>) {
        cancelDeadline(out)
        writeId = null
        rx = byteArrayOf()
        blockStream = byteArrayOf()
    }
    private fun fail(reason: String, out: MutableList<Effect>) {
        clear(out)
        // Do not let a write effect queued earlier in this same callback survive terminal failure.
        out.removeAll { it is Effect.Write || it is Effect.ArmDeadline || it is Effect.TransferAccepted }
        state = State.FAILED
        out += Effect.Failed(reason)
    }
    companion object {
        const val MAX_RX_BYTES = 64
        private val generations = AtomicLong()
        private fun u(value: Byte) = value.toInt() and 255
        private fun le(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }
        private fun le32(bytes: ByteArray, offset: Int) = (0..3).fold(0) { value, index -> value or (u(bytes[offset + index]) shl (index * 8)) }
    }
}
