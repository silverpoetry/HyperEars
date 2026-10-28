package dev.hyperears.protocol.rose

/** Exact ZT219/231 profiles from ROSELINK 3.8.2; never inferred from the i5 product line. */
object RoseZtWireCodec {
    enum class Model(val display: String, val prefixes: List<String>) {
        I3("EarFree-i3", listOf("f10203", "010203")), HEADFEEL("HeadFeel", listOf("525300020002"));
        val id get() = if (this == I3) "rose-zt-i3" else "rose-zt-headfeel"
    }
    enum class Setting(val group: Int, val command: Int) {
        EQ(2, 1), GAME(6, 3), AUDIO(8, 1), MULTIPOINT(8, 2), GAIN(2, 3);
        fun values(model: Model): IntRange = when(this) {
            EQ -> 0..2; GAME -> 0..1; AUDIO -> if (model == Model.I3) 0..1 else 0..3
            MULTIPOINT -> if (model == Model.HEADFEEL) 0..1 else IntRange.EMPTY
            GAIN -> if (model == Model.HEADFEEL) 1..3 else IntRange.EMPTY
        }
    }
    data class Frame(val group: Int, val command: Int, val payload: List<Int>)
    fun identify(data: Map<Int, List<Int>>): Model? {
        if (data.size !in 1..8) return null
        val models = data.mapNotNull { (company, payload) ->
            if (company !in 0..65535 || payload.size !in 1..512 || payload.any { it !in 0..255 }) return@mapNotNull null
            val bytes = listOf(company and 255, company ushr 8) + payload
            Model.entries.singleOrNull { model -> model.prefixes.any { prefix ->
                bytes.size >= prefix.length / 2 && bytes.take(prefix.length / 2).joinToString("") { it.toString(16).padStart(2, '0') } == prefix
            } }
        }.distinct()
        return models.singleOrNull()
    }
    fun command(group: Int, command: Int, payload: List<Int> = emptyList()): ByteArray {
        require(payload.size <= 245 && payload.all { it in 0..255 })
        val bytes = listOf(8, 0xEE, 0, 0, 0, group, command, 10 + payload.size, 0) + payload
        return (bytes + (bytes.sum() and 255)).map(Int::toByte).toByteArray()
    }
    fun query(model: Model): List<ByteArray> = listOf(command(1, if (model == Model.I3) 1 else 3), command(6, 2)) +
        Setting.entries.filter { !it.values(model).isEmpty() }.map { command(it.group, it.command) }
    fun write(model: Model, setting: Setting, value: Int): ByteArray? =
        value.takeIf { it in setting.values(model) }?.let { command(setting.group, setting.command or 0x80, listOf(it)) }
    fun readSetting(model: Model, frame: Frame): Pair<Setting, Int>? {
        if (frame.payload.size != 1) return null
        val setting = Setting.entries.singleOrNull { it.group == frame.group && it.command == frame.command } ?: return null
        return (setting to frame.payload.single()).takeIf { it.second in setting.values(model) }
    }
    /** Response always uses the common four-bit representation; ZT219 writes use three bits. */
    fun noise(frame: Frame): Int? {
        if (frame.group != 6 || frame.command != 2 || frame.payload.size != 4) return null
        val index = frame.payload.indexOf(1)
        return index.takeIf { it >= 0 && frame.payload.sum() == 1 && frame.payload.all { v -> v in 0..1 } }
    }
    fun writeNoise(model: Model, mode: Int): ByteArray? {
        val index = if (model == Model.I3) when(mode) { 0 -> 0; 1 -> 2; 3 -> 1; else -> return null } else mode
        if (index !in 0..3) return null
        return command(6, 0x82, List(if (model == Model.I3) 3 else 4) { if (it == index) 1 else 0 })
    }
    /** Fixed 255-byte buffer: corrupted length/checksum resynchronizes without unbounded concatenation. */
    class Decoder {
        private val pending = ArrayList<Int>(255)
        fun reset() = pending.clear()
        fun offer(bytes: ByteArray): List<Frame> = buildList {
            for (byte in bytes) {
                pending.add(byte.toInt() and 255)
                while (pending.isNotEmpty()) {
                    if (pending[0] != 9 || (pending.size >= 2 && pending[1] != 255)) { pending.removeAt(0); continue }
                    if (pending.size < 8) break
                    val size = pending[7]
                    if (size !in 10..255) { pending.removeAt(0); continue }
                    if (pending.size < size) break
                    if ((pending.take(size - 1).sum() and 255) != pending[size - 1]) { pending.removeAt(0); continue }
                    add(Frame(pending[5], pending[6], pending.subList(9, size - 1).toList()))
                    pending.subList(0, size).clear()
                }
            }
        }
    }
}
