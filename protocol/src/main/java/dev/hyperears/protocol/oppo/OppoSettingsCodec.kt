package dev.hyperears.protocol.oppo

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Leaf OppoPods 929d813f: structured switch reports and device-owned EQ entries.
 * No heuristic byte scans, success ACK promotion, guessed preset IDs, or long-frame writes.
 */
object OppoSettingsCodec {
    val switchIds = setOf(0x04, 0x06, 0x11, 0x28)
    val queryPromptVolume: ByteArray get() = OppoWireCodec.packet(0x0130)
    fun setPromptVolume(level: Int): ByteArray {
        require(level in 1..10)
        return OppoWireCodec.packet(0x0427,payload=byteArrayOf(level.toByte()))
    }
    fun parsePromptVolume(frame: OppoWireCodec.Frame): Int? = frame.payload.takeIf {
        frame.command == 0x8130 && it.size == 2 && it[0].u() == 0 && it[1].u() in 1..10
    }?.get(1)?.u()
    fun querySwitches(ids: Collection<Int> = switchIds): ByteArray {
        require(ids.isNotEmpty() && ids.all { it in switchIds || it == 0x1B })
        return OppoWireCodec.packet(0x010D, payload = byteArrayOf(ids.size.toByte()) + ids.map(Int::toByte))
    }
    fun setSwitch(id: Int, enabled: Boolean): ByteArray {
        require(id in switchIds || id == 0x1B)
        return OppoWireCodec.packet(0x0403, payload = byteArrayOf(id.toByte(), if (enabled) 1 else 0))
    }
    fun parseSwitches(frame: OppoWireCodec.Frame): Map<Int, Boolean>? {
        val p = frame.payload
        if (frame.command != 0x810D || p.size < 2 || p[0].u() != 0 || p.size != 2 + p[1].u() * 2) return null
        val result = mutableMapOf<Int, Boolean>()
        val seen = mutableSetOf<Int>()
        for (i in 2 until p.size step 2) {
            val id = p[i].u()
            if (!seen.add(id)) return null
            if (id !in switchIds && id != 0x1B) continue
            if (p[i + 1].u() !in 0..1) return null
            result[id] = p[i + 1].u() == 1
        }
        return result
    }
    val queryCurrentEq: ByteArray get() = OppoWireCodec.packet(0x010F)
    val queryEqualizers: ByteArray get() = OppoWireCodec.packet(0x0122, payload = byteArrayOf(1, 5))
    fun selectEqualizer(id: Int): ByteArray {
        require(id in 0..255)
        return OppoWireCodec.packet(0x0406, payload = byteArrayOf(id.toByte()))
    }
    fun parseCurrentEq(frame: OppoWireCodec.Frame): Int? = frame.payload.takeIf {
        frame.command in setOf(0x810F, 0x0504) && it.size == 2 && it[0].u() == 0
    }?.get(1)?.u()

    data class Equalizer(val id: Int, val name: String, val selected: Boolean,
        val minimum: Int, val maximum: Int, val frequencies: List<Int>, val gains: List<Int>)

    fun parseEqualizers(frame: OppoWireCodec.Frame): List<Equalizer>? {
        val p = frame.payload
        if (frame.command != 0x8122 || p.size < 2 || p[0].u() != 0) return null
        val entries = mutableListOf<Equalizer>()
        var offset = 2
        repeat(p[1].u()) {
            if (offset + 6 > p.size) return null
            val selected = p[offset].u()
            val minimum = p[offset + 1].toInt()
            val maximum = p[offset + 2].toInt()
            val id = p[offset + 3].u()
            val nameLength = p[offset + 4].u()
            offset += 5
            if (selected !in 0..1 || minimum > maximum || offset + nameLength + 1 > p.size) return null
            val name = runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(p, offset, nameLength)).toString()
            }.getOrNull() ?: return null
            if (name.any(Char::isISOControl)) return null
            offset += nameLength
            val count = p[offset++].u()
            if (count > 32 || offset + count * 3 > p.size) return null
            val frequencies = mutableListOf<Int>()
            val gains = mutableListOf<Int>()
            repeat(count) {
                val frequency = p[offset].u() or (p[offset + 1].u() shl 8)
                val gain = p[offset + 2].toInt()
                if (frequency == 0 || gain !in minimum..maximum) return null
                frequencies += frequency; gains += gain; offset += 3
            }
            if (entries.any { it.id == id } || (selected == 1 && entries.any { it.selected })) return null
            entries += Equalizer(id, name, selected == 1, minimum, maximum, frequencies, gains)
        }
        return entries.takeIf { offset == p.size }
    }
    fun canUpdate(entry: Equalizer, gains: List<Int>, maximumPayloadSize: Int = 120): Boolean =
        entry.id in 1..255 && entry.name.isNotBlank() && entry.frequencies.size in 1..32 &&
            entry.minimum in -128..127 && entry.maximum in entry.minimum..127 &&
            entry.frequencies.all { it in 1..65535 } &&
            gains.size == entry.frequencies.size && gains.all { it in entry.minimum..entry.maximum } &&
            entry.name.toByteArray(Charsets.UTF_8).size in 1..255 &&
            6 + entry.name.toByteArray(Charsets.UTF_8).size + gains.size * 3 <= maximumPayloadSize

    /** Legacy short update API; callers must explicitly select canonical framing for longer writes. */
    fun updateEqualizer(entry: Equalizer, gains: List<Int>): ByteArray {
        require(canUpdate(entry, gains))
        return OppoWireCodec.packet(0x0418, payload = updatePayload(entry, gains))
    }
    fun updateEqualizerPackets(entry: Equalizer, gains: List<Int>, frameBudget: Int): List<ByteArray> {
        require(canUpdate(entry, gains, maximumPayloadSize = 357))
        return OppoWireCodec.canonicalPackets(0x0418, payload = updatePayload(entry, gains), frameBudget = frameBudget)
    }
    private fun updatePayload(entry: Equalizer, gains: List<Int>): ByteArray {
        val name = entry.name.toByteArray(Charsets.UTF_8)
        return byteArrayOf(2, entry.minimum.toByte(), entry.maximum.toByte(), entry.id.toByte(), name.size.toByte()) +
            name + byteArrayOf(gains.size.toByte()) + entry.frequencies.flatMapIndexed { index, frequency ->
                listOf(frequency.toByte(), (frequency ushr 8).toByte(), gains[index].toByte())
            }
    }
    private fun Byte.u() = toInt() and 255
}
