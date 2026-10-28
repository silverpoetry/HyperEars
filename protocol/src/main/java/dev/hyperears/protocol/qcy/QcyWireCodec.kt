package dev.hyperears.protocol.qcy

/**
 * Codec for QCY's public `0xFF` command stream.
 *
 * The codec deliberately contains no BLE or Android behavior. It accepts arbitrary byte chunks,
 * validates every nested command length, and exposes independently validated device reports.
 */
object QcyWireCodec {
    data class Frame(
        val command: Int,
        val parameters: ByteArray,
    )

    data class BatteryCell(
        val percent: Int?,
        val charging: Boolean,
    )

    data class BatteryState(
        val left: BatteryCell,
        val right: BatteryCell,
        val case: BatteryCell,
    )

    enum class NoiseMode(val value: Int) {
        OFF(0x00),
        ANC(0x01),
        OUTDOOR(0x02),
        TRANSPARENCY(0x03),
    }

    val queryBattery: ByteArray = request(COMMAND_BATTERY)
    val queryNoiseMode: ByteArray = request(COMMAND_NOISE_MODE)

    fun setNoiseMode(mode: NoiseMode): ByteArray =
        packet(COMMAND_NOISE_MODE, byteArrayOf(mode.value.toByte()))

    fun parseBattery(frame: Frame): BatteryState? {
        if (frame.command != COMMAND_BATTERY || frame.parameters.size < BATTERY_COMPONENTS) {
            return null
        }
        val cells = frame.parameters
            .take(BATTERY_COMPONENTS)
            .map(::parseBatteryCell)
        if (cells.none { it.percent != null }) return null
        return BatteryState(
            left = cells[0],
            right = cells[1],
            case = cells[2],
        )
    }

    fun parseNoiseMode(frame: Frame): NoiseMode? {
        if (frame.command != COMMAND_NOISE_MODE || frame.parameters.size != 1) return null
        val value = frame.parameters.single().unsigned()
        return NoiseMode.entries.firstOrNull { it.value == value }
    }

    /** QCYpods 7bfbd95: command-specific off values, never coerce unknown reports. */
    enum class Toggle(val command: Int, val off: Int) {
        LOW_LATENCY(0x09, 2), SLEEP(0x10, 2), LDAC(0x23, 2), MULTIPOINT(0x24, 2),
        DYNAMIC_EQ(0x27, 0), ADAPTIVE_VOLUME(0x40, 0),
    }
    data class EqBand(val frequency: Int, val gainHundredths: Int, val qHundredths: Int, val type: Int)
    data class Equalizer(val preset: Int, val masterGainHundredths: Int, val bands: List<EqBand>)
    val presetIds = setOf(0, 1, 2, 3, 4, 5, 6, 10)
    val customFrequencies = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val advancedQueries: List<ByteArray> get() =
        (Toggle.entries.map { it.command } + listOf(0x20, 0x22, 0x2D)).map(::query)
    fun query(command: Int): ByteArray = request(command)
    fun setToggle(toggle: Toggle, enabled: Boolean) = packet(toggle.command, byteArrayOf(if (enabled) 1 else toggle.off.toByte()))
    fun parseToggle(frame: Frame): Pair<Toggle, Boolean>? {
        val toggle = Toggle.entries.find { it.command == frame.command } ?: return null
        val value = frame.parameters.singleOrNull()?.unsigned() ?: return null
        return when (value) { 1 -> toggle to true; toggle.off -> toggle to false; else -> null }
    }
    fun setSpatialMode(mode: Int): ByteArray {
        require(mode in 0..2)
        return packet(0x2D, byteArrayOf(mode.toByte()))
    }
    fun parseSpatialMode(frame: Frame): Int? = if (frame.command == 0x2D)
        frame.parameters.singleOrNull()?.unsigned()?.takeIf { it in 0..2 } else null
    fun setEqPreset(preset: Int): ByteArray {
        require(preset in presetIds)
        return packet(0x20, byteArrayOf(preset.toByte()))
    }
    fun parseEqPreset(frame: Frame): Int? {
        if (frame.command !in setOf(0x20, 0x22)) return null
        if (frame.parameters.size != 1 && parseEqualizer(frame) == null) return null
        return frame.parameters.firstOrNull()?.unsigned()?.takeIf { it in presetIds }
    }
    fun parseEqualizer(frame: Frame): Equalizer? {
        val p = frame.parameters
        if (frame.command != 0x22 || p.size < 10 || (p.size - 3) % 7 != 0) return null
        val preset = p[0].unsigned().takeIf { it in presetIds } ?: return null
        fun word(i: Int) = p[i].unsigned() or (p[i + 1].unsigned() shl 8)
        val bands = (3 until p.size step 7).map { i ->
            EqBand(word(i), word(i + 2).toShort().toInt(), word(i + 4), p[i + 6].unsigned())
        }
        if (preset == 0 && bands.size != 10) return null
        if (bands.size > 10 || bands.any { it.frequency !in 1..20000 || it.qHundredths == 0 || it.type !in 0..3 }) return null
        return Equalizer(preset, word(1).toShort().toInt(), bands)
    }
    /** Exact upstream custom PEQ: ten fixed frequencies, type 2, Q=1, master gain=0. */
    fun setCustomEq(gains: List<Int>): ByteArray {
        require(gains.size == 10 && gains.all { it in -8..8 })
        val p = ArrayList<Byte>()
        fun word(value: Int) { p += value.toByte(); p += (value shr 8).toByte() }
        p += 0.toByte(); word(0)
        gains.forEachIndexed { index, gain ->
            word(customFrequencies[index]); word(gain * 100); word(100); p += 2.toByte()
        }
        return packet(0x22, p.toByteArray())
    }

    class Decoder {
        private var pending = ByteArray(0)

        fun offer(bytes: ByteArray): List<Frame> {
            if (bytes.isEmpty()) return emptyList()
            pending += bytes
            val result = mutableListOf<Frame>()

            while (pending.size >= HEADER_SIZE) {
                val marker = pending.indexOf(FRAME_MARKER)
                if (marker < 0) {
                    pending = ByteArray(0)
                    break
                }
                if (marker > 0) pending = pending.copyOfRange(marker, pending.size)
                if (pending.size < HEADER_SIZE) break

                val bodyLength = pending[BODY_LENGTH_OFFSET].unsigned()
                if (bodyLength < MIN_COMMAND_SIZE) {
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }
                val frameLength = HEADER_SIZE + bodyLength
                if (pending.size < frameLength) break

                val candidate = pending.copyOfRange(0, frameLength)
                pending = pending.copyOfRange(frameLength, pending.size)
                decodeCommands(candidate)?.let(result::addAll)
            }
            return result
        }

        fun reset() {
            pending = ByteArray(0)
        }

        private fun decodeCommands(packet: ByteArray): List<Frame>? {
            val bodyEnd = packet.size
            val frames = mutableListOf<Frame>()
            var index = HEADER_SIZE
            while (index < bodyEnd) {
                if (index + 1 >= bodyEnd) return null
                val command = packet[index].unsigned()
                val parameterLength = packet[index + 1].unsigned()
                val parameterStart = index + MIN_COMMAND_SIZE
                val parameterEnd = parameterStart + parameterLength
                if (parameterEnd > bodyEnd) return null
                frames += Frame(
                    command = command,
                    parameters = packet.copyOfRange(parameterStart, parameterEnd),
                )
                index = parameterEnd
            }
            return frames.takeIf { it.isNotEmpty() }
        }
    }

    private fun request(command: Int): ByteArray =
        packet(COMMAND_REQUEST_DATA, byteArrayOf(command.toByte()))

    private fun packet(command: Int, parameters: ByteArray): ByteArray {
        val bodyLength = MIN_COMMAND_SIZE + parameters.size
        require(bodyLength <= 0xFF)
        return byteArrayOf(
            FRAME_MARKER,
            bodyLength.toByte(),
            command.toByte(),
            parameters.size.toByte(),
        ) + parameters
    }

    private fun parseBatteryCell(raw: Byte): BatteryCell {
        val value = raw.unsigned()
        return BatteryCell(
            percent = (value and BATTERY_LEVEL_MASK).takeIf { it in 0..100 },
            charging = value and BATTERY_CHARGING_MASK != 0,
        )
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private const val HEADER_SIZE = 2
    private const val BODY_LENGTH_OFFSET = 1
    private const val MIN_COMMAND_SIZE = 2
    private const val BATTERY_COMPONENTS = 3
    private const val BATTERY_LEVEL_MASK = 0x7F
    private const val BATTERY_CHARGING_MASK = 0x80
    private const val COMMAND_NOISE_MODE = 0x0C
    private const val COMMAND_BATTERY = 0x2F
    private const val COMMAND_REQUEST_DATA = 0xFE
    private const val FRAME_MARKER: Byte = 0xFF.toByte()
}
