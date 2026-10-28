package dev.hyperears.protocol.rose

/** Qualified RS Common v2 framing, reconstructed from ROSELINK 3.8.2; see Coral source audit.
 * TX has a payload length; RX is a top-level LTV chain, not a recursively searchable payload.
 */
object RoseRsV2WireCodec {
    sealed interface State {
        data class Battery(val left: Int, val right: Int, val case: Int) : State
        data class Value(val type: Int, val value: Int) : State
        data class Firmware(val major: Int, val minor: Int, val patch: Int) : State
        /** Validated complete slot order: left 1..4, right 0x11..0x14. */
        data class Touch(val actions: List<Int>) : State
    }

    fun command(sequence: Int, payload: ByteArray): ByteArray {
        require(payload.size in 1..255)
        val body = byteArrayOf(0xFF.toByte(), sequence.toByte(), payload.size.toByte()) + payload
        return body + byteArrayOf(body.sumOf { it.u() }.toByte(), 0xAA.toByte())
    }

    fun query(sequence: Int, model: RoseRsV2IdentityCodec.Model): ByteArray = command(sequence, buildList {
        add(0xFA); add(0x0D); add(0x0C)
        if (model.noise) add(9)
        if (model.ancLevels.isNotEmpty()) add(0x2C)
        if (model.transparencyLevels.isNotEmpty()) add(0x2D)
        if (model.languageSupported) add(0x31)
        if (model.wearDetectionSupported) add(8)
        if (model.eqPresets.isNotEmpty() || model.bass) add(0x2A)
        if (model.gainSupported) add(0x45)
        if (model.hearingProtectionSupported) add(0x46)
        add(0x0E); add(0x32)
        if (model.audio) add(0x2B)
        if (model.touch) add(1)
        if (model.touchMaster) add(7)
        if (model.findSupported) add(0x2F)
        add(0x2E)
    }.map(Int::toByte).toByteArray())

    class Decoder(private val model: RoseRsV2IdentityCodec.Model) {
        // Incremental append keeps memory bounded even when offered an arbitrarily large chunk.
        private val pending = ArrayList<Byte>(MAX_FRAME)

        fun reset() = pending.clear()

        fun offer(bytes: ByteArray): List<State> = buildList {
            for (byte in bytes) {
                if (pending.isEmpty() && byte.u() != 0xDD) continue
                pending.add(byte)
                while (pending.isNotEmpty()) {
                    val end = frameEnd(0)
                    if (end != null) {
                        addAll(parse(pending.subList(2, end - 2), model))
                        pending.subList(0, end).clear()
                        discardNoise()
                        continue
                    }
                    // Do not search an incomplete opaque LTV for nested frames. Recover only
                    // once the bounded candidate is exhausted; nested data is never evidence.
                    if (pending.size >= MAX_FRAME) {
                        pending.removeAt(0)
                        discardNoise()
                        continue
                    }
                    break
                }
            }
        }

        private fun discardNoise() {
            while (pending.isNotEmpty() && pending[0].u() != 0xDD) pending.removeAt(0)
        }

        /** Only LTV boundaries may terminate a frame; AA inside an opaque value stays opaque. */
        private fun frameEnd(start: Int): Int? {
            var cursor = start + 2
            var sum = 0xDD + (pending.getOrNull(start + 1)?.u() ?: return null)
            var entries = 0
            while (cursor + 1 < pending.size && cursor - start < MAX_FRAME) {
                if (entries > 0 && pending[cursor + 1].u() == 0xAA &&
                    pending[cursor].u() == (sum and 255)
                ) return cursor + 2
                val length = pending[cursor].u()
                if (length < 1 || cursor + length >= pending.size) return null
                for (index in cursor..cursor + length) sum += pending[index].u()
                cursor += length + 1
                entries++
            }
            return null
        }
    }

    private fun parse(body: List<Byte>, model: RoseRsV2IdentityCodec.Model): List<State> = buildList {
        var index = 0
        while (index < body.size) {
            val length = body[index].u()
            if (length < 1 || index + length >= body.size) return emptyList()
            val type = body[index + 1].u()
            val values = body.subList(index + 2, index + length + 1).map { it.u() }
            when (type) {
                0x0D -> if (values.size >= 3) add(State.Firmware(values[0], values[1], values[2]))
                0x0C -> if (values.size == 3) {
                    add(State.Battery(values[0], values[1], values[2]))
                }
                0x2C -> if (values.size == 1 && values[0] in model.ancLevels) add(State.Value(type,values[0]))
                0x2D -> if (values.size == 1 && values[0] in model.transparencyLevels) add(State.Value(type,values[0]))
                0x31 -> if (model.languageSupported && values.size == 1 && values[0] in 0..1) add(State.Value(type,values[0]))
                8 -> if (model.wearDetectionSupported && values.size == 1 && values[0] in 0..1) add(State.Value(type,values[0]))
                0x09 -> if (values.size == 1 && values[0] in model.noiseValues) add(State.Value(type, values[0]))
                0x45 -> if (model.gainSupported && values.size == 1 && values[0] in 0..2) add(State.Value(type,values[0]))
                0x46 -> if (model.hearingProtectionSupported && values.size == 1 && values[0] in 0..6) add(State.Value(type,values[0]))
                0x0E, 0x32 -> if (values.size == 1 && values[0] in 0..1) add(State.Value(type, values[0]))
                0x07 -> if (model.touchMaster && values.size == 1 && values[0] in 0..1) add(State.Value(type,values[0]))
                0x2A -> {
                    if (values.size == 1) {
                        val preset = when(values[0]) { 0 -> 1; 1 -> 0; else -> values[0] }
                        if (preset in model.eqPresets) add(State.Value(type,preset))
                    }
                    if (model.bass && values.size == 2 && values[0] == 5 && values[1] in model.bassLevels)
                        add(State.Value(0x105,values[1]))
                }
                0x2E -> if (values.size == 1 && values[0] in 1..5) add(State.Value(type, values[0]))
                0x2B -> if (model.audio && values.size == 1 && values[0] in model.audioModes) add(State.Value(type, values[0]))
                0x2F -> if (model.findSupported && values.size == 1 && values[0] in 1..6) add(State.Value(type, values[0]))
                0x01 -> if (model.touch && values.size == 16) {
                    val pairs = values.chunked(2)
                    if (pairs.map { it[0] }.toSet() == TOUCH_SLOTS && pairs.all { it[1] in model.touchActions }) {
                        add(State.Touch(TOUCH_SLOTS.map { slot -> pairs.single { it[0] == slot }[1] }))
                    }
                }
            }
            index += length + 1
        }
    }

    private fun Byte.u() = toInt() and 255
    private val TOUCH_SLOTS = linkedSetOf(1, 2, 3, 4, 0x11, 0x12, 0x13, 0x14)
    private const val MAX_FRAME = 1024
}
