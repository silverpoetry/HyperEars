package dev.hyperears.protocol.samsung

/** Samsung Galaxy Buds message framing used by Buds2 Pro over its RFCOMM service. */
object SamsungBudsCodec {
    enum class Model { BUDS2_PRO, BUDS2, BUDS_FE, UNKNOWN }
    const val UNIVERSAL_ACK = 0x42
    const val STATUS_UPDATED = 0x60
    const val EXTENDED_STATUS_UPDATED = 0x61
    const val NOISE_CONTROLS_UPDATE = 0x77
    const val NOISE_CONTROLS = 0x78
    // GalaxyBudsClient TouchAndHoldNoiseControls: left ANC/ambient/off, then right.
    const val SET_TOUCH_AND_HOLD_NOISE_CONTROLS = 0x79
    const val SET_ANC_WITH_ONE_EARBUD = 0x6F
    const val SET_DETECT_CONVERSATIONS = 0x7A
    const val AMBIENT_VOLUME = 0x84
    const val MANAGER_INFO = 0x88
    const val EQUALIZER = 0x86
    const val SET_SIDETONE = 0x8B
    const val LOCK_TOUCHPAD = 0x90
    const val SET_TOUCHPAD_OPTION = 0x92
    const val OUTSIDE_DOUBLE_TAP = 0x95
    const val EXTRA_HIGH_AMBIENT = 0x96
    const val SET_SEAMLESS_CONNECTION = 0xAF

    enum class NoiseMode(val wire: Int) { OFF(0), ANC(1), AMBIENT(2) }

    data class Frame(val id: Int, val payload: ByteArray)

    data class Acknowledgement(val commandId: Int, val parameters: ByteArray)

    data class BatteryState(
        val left: Int?,
        val right: Int?,
        val case: Int?,
        val leftCharging: Boolean = false,
        val rightCharging: Boolean = false,
        val caseCharging: Boolean = false,
    )

    data class SettingsState(
        val equalizerEnabled: Boolean,
        val equalizerPreset: Int,
        val touchpadLocked: Boolean,
        val singleTapEnabled: Boolean,
        val doubleTapEnabled: Boolean,
        val tripleTapEnabled: Boolean,
        val touchHoldEnabled: Boolean,
        val doubleTapCallEnabled: Boolean,
        val touchHoldCallEnabled: Boolean,
        val touchHoldLeftAction: Int,
        val touchHoldRightAction: Int,
        val ambientVolume: Int,
        val voiceDetectEnabled: Boolean,
        val noiseControlsWithOneEarbud: Boolean,
        val seamlessConnectionEnabled: Boolean,
        val outsideDoubleTapEnabled: Boolean,
        val sidetoneEnabled: Boolean,
        val extraHighAmbientEnabled: Boolean,
        val touchHoldLeftCycle: Int? = null,
        val touchHoldRightCycle: Int? = null,
    )

    data class ExtendedStatus(
        val battery: BatteryState,
        val noiseMode: NoiseMode,
        val settings: SettingsState,
    )

    fun packet(id: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(id in 0..0xFF)
        require(payload.size <= MAX_PAYLOAD_SIZE)
        val body = byteArrayOf(id.toByte()) + payload
        val size = body.size + 2
        val frame = ByteArray(size + 4)
        frame[0] = SOM
        frame[1] = size.toByte()
        frame[2] = (size shr 8).toByte()
        body.copyInto(frame, 3)
        val crc = crc16Xmodem(body)
        frame[frame.lastIndex - 2] = crc.toByte()
        frame[frame.lastIndex - 1] = (crc shr 8).toByte()
        frame[frame.lastIndex] = EOM
        return frame
    }

    fun boolCommand(id: Int, enabled: Boolean): ByteArray =
        packet(id, byteArrayOf(enabled.byte()))

    fun noiseModeCommand(mode: NoiseMode): ByteArray = packet(NOISE_CONTROLS, byteArrayOf(mode.wire.toByte()))

    fun managerInfoCommand(androidSdk: Int = 35): ByteArray =
        packet(MANAGER_INFO, byteArrayOf(1, 2, androidSdk.coerceIn(0, 255).toByte()))

    fun equalizerCommand(enabled: Boolean, preset: Int): ByteArray {
        require(preset in 0..4)
        return packet(EQUALIZER, byteArrayOf(if (enabled) (preset + 1).toByte() else 0.toByte()))
    }

    fun touchpadLockCommand(enabled: Boolean, state: SettingsState): ByteArray = packet(
        LOCK_TOUCHPAD,
        byteArrayOf(
            (!enabled).byte(),
            state.singleTapEnabled.byte(), state.doubleTapEnabled.byte(),
            state.tripleTapEnabled.byte(), state.touchHoldEnabled.byte(),
            state.doubleTapCallEnabled.byte(), state.touchHoldCallEnabled.byte(),
        ),
    )

    fun touchpadOptionsCommand(leftAction: Int, rightAction: Int): ByteArray {
        require(leftAction in 1..4)
        require(rightAction in 1..4)
        return packet(SET_TOUCHPAD_OPTION, byteArrayOf(leftAction.toByte(), rightAction.toByte()))
    }

    fun seamlessConnectionCommand(enabled: Boolean): ByteArray = boolCommand(SET_SEAMLESS_CONNECTION, !enabled)

    fun touchHoldNoiseCyclesCommand(left: Int, right: Int): ByteArray {
        require(left in setOf(6, 5, 3) && right in setOf(6, 5, 3))
        fun flags(mask: Int) = byteArrayOf(
            (mask and 4 != 0).byte(), (mask and 2 != 0).byte(), (mask and 1 != 0).byte(),
        )
        return packet(SET_TOUCH_AND_HOLD_NOISE_CONTROLS, flags(left) + flags(right))
    }

    fun parseNoiseCycle(mask: Int): Int? = mask.takeIf { it in setOf(6, 5, 3) }

    fun parseFrame(bytes: ByteArray): Frame? {
        if (bytes.size < MIN_FRAME_SIZE || bytes[0] != SOM || bytes.last() != EOM) return null
        val size = (bytes[1].u() or (bytes[2].u() shl 8)) and 0x3FF
        if (bytes.size != size + 4 || size < 3) return null
        val body = bytes.copyOfRange(3, bytes.size - 3)
        val expected = bytes[bytes.lastIndex - 2].u() or (bytes[bytes.lastIndex - 1].u() shl 8)
        if (crc16Xmodem(body) != expected) return null
        return Frame(body[0].u(), body.copyOfRange(1, body.size))
    }

    fun parseBattery(frame: Frame, model: Model = Model.BUDS2_PRO): BatteryState? = when (frame.id) {
        STATUS_UPDATED -> frame.payload.takeIf { it.size >= 7 }?.let { p ->
            val charging = p.getOrNull(7)?.u() ?: 0
            BatteryState(
                p[1].u().percent(), p[2].u().percent(), p[6].u().percent(),
                charging and 0x10 != 0, charging and 0x04 != 0, charging and 0x01 != 0,
            )
        }
        EXTENDED_STATUS_UPDATED -> frame.payload.takeIf { it.size >= 8 }?.let { p ->
            val chargingIndex = when (model) {
                Model.BUDS2 -> 36
                Model.BUDS2_PRO, Model.BUDS_FE -> 43
                Model.UNKNOWN -> -1
            }
            val charging = p.getOrNull(chargingIndex)?.u() ?: 0
            BatteryState(p[2].u().percent(), p[3].u().percent(), p[7].u().percent(),
                charging and 0x10 != 0, charging and 0x04 != 0, charging and 0x01 != 0)
        }
        else -> null
    }

    fun parseAcknowledgement(frame: Frame): Acknowledgement? {
        if (frame.id != UNIVERSAL_ACK || frame.payload.isEmpty()) return null
        return Acknowledgement(frame.payload[0].u(), frame.payload.copyOfRange(1, frame.payload.size))
    }

    fun parseNoiseMode(frame: Frame): NoiseMode? = when (frame.id) {
        NOISE_CONTROLS_UPDATE -> frame.payload.firstOrNull()?.u()?.toNoiseMode()
        EXTENDED_STATUS_UPDATED -> frame.payload.getOrNull(12)?.u()?.toNoiseMode()
        else -> null
    }

    fun parseExtendedStatus(frame: Frame, model: Model = Model.BUDS2_PRO): ExtendedStatus? {
        if (frame.id != EXTENDED_STATUS_UPDATED || frame.payload.size < 35) return null
        val p = frame.payload
        if (model == Model.UNKNOWN) return null
        // Buds2 revision 7+ has the full seven-byte touch-lock command including calls.
        // Earlier firmware retains battery/noise support without advertising advanced settings.
        if (model == Model.BUDS2 && p[0].u() < 7) return null
        if (model == Model.BUDS_FE && p.size < 44) return null
        val touch = p[10].u()
        val eq = p[9].u()
        return ExtendedStatus(
            battery = parseBattery(frame, model) ?: return null,
            noiseMode = p[12].u().toNoiseMode() ?: return null,
            settings = SettingsState(
                equalizerEnabled = eq != 0,
                equalizerPreset = (eq - 1).coerceIn(0, 4),
                touchpadLocked = touch and 0x80 != 0x80,
                singleTapEnabled = touch and 0x08 != 0,
                doubleTapEnabled = touch and 0x04 != 0,
                tripleTapEnabled = touch and 0x02 != 0,
                touchHoldEnabled = touch and 0x01 != 0,
                doubleTapCallEnabled = touch and 0x10 != 0,
                touchHoldCallEnabled = touch and 0x20 != 0,
                touchHoldLeftAction = (p[11].u() shr 4).coerceIn(1, 4),
                touchHoldRightAction = (p[11].u() and 0x0F).coerceIn(1, 4),
                ambientVolume = p[23].u().coerceIn(0, 3),
                voiceDetectEnabled = model == Model.BUDS2_PRO && p[26].u() != 0,
                noiseControlsWithOneEarbud = p[28].u() != 0,
                seamlessConnectionEnabled = p[19].u() == 0,
                outsideDoubleTapEnabled = p[32].u() != 0,
                sidetoneEnabled = p[33].u() != 0,
                extraHighAmbientEnabled = model == Model.BUDS2_PRO && p.getOrNull(45)?.u() == 1,
                touchHoldLeftCycle = parseNoiseCycle((p[21].u() shr 4) and 7),
                touchHoldRightCycle = parseNoiseCycle(p[21].u() and 7),
            ),
        )
    }

    fun crc16Xmodem(data: ByteArray): Int {
        var crc = 0
        data.forEach { value ->
            crc = crc xor (value.u() shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    class Decoder {
        private var pending = ByteArray(0)
        fun offer(bytes: ByteArray): List<ByteArray> {
            pending += bytes
            val result = mutableListOf<ByteArray>()
            while (pending.size >= MIN_FRAME_SIZE) {
                val start = pending.indexOfFirst { it == SOM }
                if (start < 0) { pending = ByteArray(0); break }
                if (start > 0) pending = pending.copyOfRange(start, pending.size)
                if (pending.size < 3) break
                val size = (pending[1].u() or (pending[2].u() shl 8)) and 0x3FF
                val total = size + 4
                if (size < 3 || total > MAX_FRAME_SIZE) { pending = pending.copyOfRange(1, pending.size); continue }
                if (pending.size < total) break
                val candidate = pending.copyOfRange(0, total)
                pending = pending.copyOfRange(total, pending.size)
                if (parseFrame(candidate) != null) result += candidate
            }
            return result
        }
        fun reset() { pending = ByteArray(0) }
    }

    private fun Boolean.byte(): Byte = if (this) 1 else 0
    private fun Byte.u(): Int = toInt() and 0xFF
    private fun Int.percent(): Int? = takeIf { it in 0..100 }
    private fun Int.toNoiseMode(): NoiseMode? = NoiseMode.entries.firstOrNull { it.wire == this }

    private const val SOM: Byte = 0xFD.toByte()
    private const val EOM: Byte = 0xDD.toByte()
    private const val MIN_FRAME_SIZE = 7
    private const val MAX_PAYLOAD_SIZE = 1020
    private const val MAX_FRAME_SIZE = 1027
}
