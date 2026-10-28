package dev.hyperears.protocol.apple

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Minimal Apple Accessory Protocol subset required for AirPods battery and ANC controls. */
object AppleAapWireCodec {
    enum class NoiseMode(val value: Int) {
        OFF(1),
        ANC(2),
        TRANSPARENCY(3),
        ADAPTIVE(4),
    }

    sealed interface State {
        data class Battery(
            val left: Component,
            val right: Component,
            val case: Component,
        ) : State

        data class Noise(val mode: NoiseMode) : State

        /** Opcode 0x09: unsigned identifier and exactly four unsigned payload bytes. */
        data class Control(val identifier: Int, val values: List<Int>) : State

        /** Only non-private fields with a defined protocol consumer; never retain raw identity data. */
        data class DeviceInformation(val modelIdentifier: String, val firmwareActive: String) : State
    }

    data class Component(
        val percent: Int?,
        val charging: Boolean,
    )

    val handshake: ByteArray = hex("00 00 04 00 01 00 02 00 00 00 00 00 00 00 00 00")
    val enableSpecificFeatures: ByteArray =
        hex("04 00 04 00 4D 00 FF 00 00 00 00 00 00 00")
    val requestNotifications: ByteArray = hex("04 00 04 00 0F 00 FF FF FF FF")

    fun setNoiseMode(mode: NoiseMode): ByteArray =
        NOISE_PREFIX + byteArrayOf(mode.value.toByte(), 0, 0, 0)

    /** Fixed-width AACP control write; unowned bytes must be supplied by the session. */
    fun setControl(identifier: Int, values: List<Int>): ByteArray {
        require(identifier in 0..255)
        require(values.size == 4 && values.all { it in 0..255 })
        return CONTROL_PREFIX + byteArrayOf(identifier.toByte()) + values.map(Int::toByte).toByteArray()
    }

    class Decoder {
        private var pending = ByteArray(0)

        fun offer(bytes: ByteArray): List<State> {
            if (bytes.isEmpty()) return emptyList()
            val result = mutableListOf<State>()
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(bytes.size - offset, MAX_FRAME_SIZE - pending.size)
                pending += bytes.copyOfRange(offset, offset + count)
                offset += count
                drain(result)
            }
            return result
        }

        private fun drain(result: MutableList<State>) {
            while (pending.isNotEmpty()) {
                val match = PACKETS
                    .mapNotNull { packet -> pending.indexOf(packet.prefix).takeIf { it >= 0 }?.let { it to packet } }
                    .minByOrNull { it.first }
                if (match == null) {
                    pending = pending.takeLast(MAX_PREFIX_SIZE - 1).toByteArray()
                    break
                }

                val (offset, packet) = match
                if (offset > 0) pending = pending.copyOfRange(offset, pending.size)
                val frameSize = packet.frameSize(pending)
                if (frameSize == FRAME_INCOMPLETE) break
                if (frameSize == FRAME_INVALID) {
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }
                if (pending.size < frameSize) break

                val frame = pending.copyOfRange(0, frameSize)
                pending = pending.copyOfRange(frameSize, pending.size)
                packet.parse(frame)?.let(result::add)
            }
        }

        fun reset() {
            pending = ByteArray(0)
        }
    }

    // AndPods 4.3.8 schema, independently reconstructed; see apple-device-identity-followup.md.
    // Header bytes 6 and 9..10 have unknown semantics and deliberately are not constrained.
    private fun parseDeviceInformation(frame: ByteArray): State.DeviceInformation? {
        if (frame.size < 58) return null
        var cursor = 11
        fun readString(): String? {
            val start = cursor
            while (cursor < frame.size && frame[cursor] != 0.toByte()) cursor++
            if (cursor == frame.size) return null
            val value = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(frame, start, cursor - start)).toString()
            } catch (_: CharacterCodingException) {
                return null
            }
            cursor++
            return value
        }
        var model = ""
        var firmware = ""
        repeat(11) { index ->
            val value = readString() ?: return null
            when (index) {
                1 -> model = value
                4 -> firmware = value
            }
        }
        if (frame.size - cursor < 36) return null
        cursor += 34 // Two opaque 17-byte identifiers; embedded headers and NULs are ordinary data.
        repeat(2) { readString() ?: return null }
        if (cursor != frame.size) return null
        // Bound projected text without truncating a model into a different, recognized identifier.
        if (model.length > 128 || firmware.length > 128) return null
        return State.DeviceInformation(model, firmware)
    }

    private fun parseBattery(frame: ByteArray): State.Battery? {
        val count = frame[BATTERY_COUNT_OFFSET].unsigned()
        val components = mutableMapOf<Int, Component>()
        for (index in 0 until count) {
            val offset = BATTERY_RECORDS_OFFSET + index * BATTERY_RECORD_SIZE
            if (frame[offset + 1].unsigned() != BATTERY_RECORD_MARKER ||
                frame[offset + 4].unsigned() != BATTERY_RECORD_MARKER
            ) {
                return null
            }
            val (component, state) = parseComponent(frame, offset)
            components[component] = state
        }
        return State.Battery(
            left = components[LEFT_COMPONENT] ?: unavailableComponent,
            right = components[RIGHT_COMPONENT] ?: unavailableComponent,
            case = components[CASE_COMPONENT] ?: unavailableComponent,
        )
    }

    private fun parseComponent(
        frame: ByteArray,
        offset: Int,
    ): Pair<Int, Component> {
        val status = frame[offset + 3].unsigned()
        val available = status == CHARGING || status == NOT_CHARGING
        return frame[offset].unsigned() to Component(
            percent = frame[offset + 2].unsigned().takeIf { available && it in 0..100 },
            charging = status == CHARGING,
        )
    }

    private fun parseControl(frame: ByteArray): State? {
        if (frame.size != 11) return null
        if (frame[6].unsigned() == 0x0d) return parseNoise(frame)
        return State.Control(frame[6].unsigned(), frame.slice(7..10).map { it.unsigned() })
    }

    private fun parseNoise(frame: ByteArray): State.Noise? =
        NoiseMode.entries
            .firstOrNull { it.value == frame[7].unsigned() }
            ?.let { State.Noise(it) }

    private fun ByteArray.indexOf(prefix: ByteArray): Int {
        if (size < prefix.size) return -1
        return (0..size - prefix.size).firstOrNull { offset ->
            prefix.indices.all { this[offset + it] == prefix[it] }
        } ?: -1
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private fun hex(value: String): ByteArray {
        val compact = value.filterNot(Char::isWhitespace)
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private data class PacketSpec(
        val prefix: ByteArray,
        val frameSize: (ByteArray) -> Int,
        val parse: (ByteArray) -> State?,
    )

    private const val LEFT_COMPONENT = 4
    private const val RIGHT_COMPONENT = 2
    private const val CASE_COMPONENT = 8
    private const val CHARGING = 1
    private const val NOT_CHARGING = 2
    private const val BATTERY_COUNT_OFFSET = 6
    private const val BATTERY_RECORDS_OFFSET = 7
    private const val BATTERY_RECORD_SIZE = 5
    private const val BATTERY_RECORD_MARKER = 1
    private const val MAX_BATTERY_COMPONENTS = 3
    private const val MAX_FRAME_SIZE = 9 + 0xFFFF
    private const val FRAME_INCOMPLETE = 0
    private const val FRAME_INVALID = -1
    private val unavailableComponent = Component(null, false)
    private val BATTERY_PREFIX = hex("04 00 04 00 04 00")
    private val CONTROL_PREFIX = hex("04 00 04 00 09 00")
    private val NOISE_PREFIX = CONTROL_PREFIX + byteArrayOf(0x0d)
    private val PACKETS = listOf(
        PacketSpec(
            prefix = BATTERY_PREFIX,
            frameSize = { bytes ->
                if (bytes.size <= BATTERY_COUNT_OFFSET) {
                    FRAME_INCOMPLETE
                } else {
                    val count = bytes[BATTERY_COUNT_OFFSET].unsigned()
                    if (count !in 1..MAX_BATTERY_COMPONENTS) {
                        FRAME_INVALID
                    } else {
                        BATTERY_RECORDS_OFFSET + count * BATTERY_RECORD_SIZE
                    }
                }
            },
            parse = ::parseBattery,
        ),
        PacketSpec(CONTROL_PREFIX, { 11 }, ::parseControl),
        PacketSpec(
            prefix = hex("04 00 04 00 1D 00"),
            frameSize = { bytes ->
                if (bytes.size < 9) FRAME_INCOMPLETE
                else 9 + bytes[7].unsigned() + (bytes[8].unsigned() shl 8)
            },
            parse = ::parseDeviceInformation,
        ),
    )
    private val MAX_PREFIX_SIZE = PACKETS.maxOf { it.prefix.size }
}
