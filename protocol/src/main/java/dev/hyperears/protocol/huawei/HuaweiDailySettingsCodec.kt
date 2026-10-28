package dev.hyperears.protocol.huawei

/** Wire facts independently implemented from OpenFreebuds 0b4f14c0 and HuaweiPods dc5b9d14. */
object HuaweiDailySettingsCodec {
    const val WEAR_READ = 0x2B11
    const val WEAR_WRITE = 0x2B10
    const val MULTIPOINT_READ = 0x2B2F
    const val MULTIPOINT_WRITE = 0x2B2E
    const val LOW_LATENCY = 0x2B6C
    const val SOUND_QUALITY_READ = 0x2BA3
    const val SOUND_QUALITY_WRITE = 0x2BA2
    const val EQ_READ = 0x2B4A
    const val EQ_WRITE = 0x2B49
    enum class Gesture(val read: Int, val write: Int) {
        DOUBLE(0x0120, 0x011F), TRIPLE(0x0126, 0x0125),
        HOLD(0x2B17, 0x2B16), CYCLE(0x2B19, 0x2B18), SWIPE(0x2B1F, 0x2B1E),
    }
    data class Gestures(val left: Int?, val right: Int?, val call: Int?, val advertised: List<Int>?)
    data class CustomPreset(val id: Int, val name: String, val gains: List<Int>)
    data class Equalizer(val selected: Int, val advertised: List<Int>?, val custom: List<CustomPreset>)

    fun query(command: Int, vararg fields: Int): ByteArray = HuaweiFreebudsSppCodec.packet(
        command, fields.map { it to byteArrayOf() },
    )
    fun queryEqualizer() = query(EQ_READ, 1, 2, 3, 4, 5, 6, 7, 8)
    fun queryGesture(kind: Gesture) = query(kind.read, 1, 2)
    fun setBoolean(command: Int, enabled: Boolean): ByteArray {
        require(command in setOf(WEAR_WRITE, MULTIPOINT_WRITE, LOW_LATENCY, SOUND_QUALITY_WRITE))
        return HuaweiFreebudsSppCodec.packet(command, listOf(1 to byteArrayOf(if (enabled) 1 else 0)))
    }
    fun setGesture(kind: Gesture, field: Int, value: Int, mirrored: Boolean = false): ByteArray {
        require(field in setOf(1, 2, 4) && value in -128..127)
        val fields = if (kind == Gesture.SWIPE || mirrored) listOf(1, 2) else listOf(field)
        return HuaweiFreebudsSppCodec.packet(kind.write, fields.map { it to byteArrayOf(value.toByte()) })
    }
    fun setPreset(id: Int): ByteArray {
        require(id in 0..127)
        return HuaweiFreebudsSppCodec.packet(EQ_WRITE, listOf(1 to byteArrayOf(id.toByte())))
    }
    /** Edit an existing ten-band custom slot; no guessed allocation or deletion. */
    fun setCustom(preset: CustomPreset): ByteArray {
        val name = preset.name.toByteArray(Charsets.UTF_8)
        require(preset.id in 100..102 && name.isNotEmpty() && name.size <= 24 && '\u0000' !in preset.name)
        require(preset.gains.size == 10 && preset.gains.all { it in -60..60 })
        return HuaweiFreebudsSppCodec.packet(EQ_WRITE, listOf(
            1 to byteArrayOf(preset.id.toByte()), 2 to byteArrayOf(10),
            3 to preset.gains.map(Int::toByte).toByteArray(), 4 to name, 5 to byteArrayOf(1),
        ))
    }
    private fun successful(frame: HuaweiFreebudsSppCodec.Frame): Boolean =
        // 7F is an acknowledgement/error envelope, not a setting readback.
        frame.params[127] == null
    fun parseBoolean(frame: HuaweiFreebudsSppCodec.Frame, expected: Int): Boolean? {
        if (frame.command != expected || !successful(frame)) return null
        val value = frame.params[1]?.singleOrNull()?.toInt() ?: return null
        return when (value) { 0 -> false; 1 -> true; else -> null }
    }
    /** Both preference reports carry field2; field1 writes/echoes never confirm state. */
    fun parsePreference(frame: HuaweiFreebudsSppCodec.Frame, expected: Int): Boolean? {
        if (expected !in setOf(LOW_LATENCY, SOUND_QUALITY_READ) || frame.command != expected || !successful(frame)) return null
        return when (frame.params[2]?.singleOrNull()?.toInt()) { 0 -> false; 1 -> true; else -> null }
    }
    fun parseGesture(frame: HuaweiFreebudsSppCodec.Frame, kind: Gesture): Gestures? = runCatching {
        require(frame.command == kind.read && successful(frame))
        fun one(field: Int): Int? = frame.params[field]?.let { require(it.size == 1); it[0].toInt() }
        val left = one(1); val right = one(2); val call = one(4)
        require(left != null || right != null || call != null)
        val options = frame.params[3]?.let { require(it.isNotEmpty()); it.map(Byte::toInt).distinct() }
        Gestures(left, right, call, options)
    }.getOrNull()
    fun parseEqualizer(frame: HuaweiFreebudsSppCodec.Frame): Equalizer? = runCatching {
        require(frame.command == EQ_READ && successful(frame))
        frame.params[1]?.let { require(it.size == 1 && it[0] == 1.toByte()) }
        val selected = frame.params[2]?.singleOrNull()?.toInt()?.and(255) ?: return null
        val advertised = frame.params[3]?.let { require(it.isNotEmpty()); it.map { b -> b.toInt() and 255 }.distinct() }
        val records = frame.params[8] ?: byteArrayOf()
        require(records.size % 36 == 0)
        val custom = records.asList().chunked(36).map { record ->
            val id = record[0].toInt() and 255
            val count = record[1].toInt() and 255
            require(id in 100..102 && count == 10)
            val gains = record.subList(2, 12).map(Byte::toInt)
            require(gains.all { it in -60..60 })
            val nameBytes = record.subList(12, 36).takeWhile { it != 0.toByte() }.toByteArray()
            val name = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(nameBytes)).toString()
            require(name.isNotBlank())
            CustomPreset(id, name, gains)
        }
        require(custom.map { it.id }.distinct().size == custom.size)
        Equalizer(selected, advertised, custom)
    }.getOrNull()
}

