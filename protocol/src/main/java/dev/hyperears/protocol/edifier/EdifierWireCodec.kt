package dev.hyperears.protocol.edifier

/**
 * Edifier (BES/恒玄) command framing carried over its private Bluetooth Classic RFCOMM service.
 *
 * Protocol verified on real hardware (Edifier W860NB PRO via LSPosed hook of Edifier Connect v8.4.39):
 *
 * Frame format (BLE v2):
 * - Send:   [0xAA][APP_CODE=0xEC][CMD_INDEX][LEN_H][LEN_L][PAYLOAD...][CRC8]
 * - Receive: [0xBB][APP_CODE][CMD_INDEX][LEN_H][LEN_L][PAYLOAD...][CRC8]  (older: 0xCC)
 *
 * CRC: sum of all preceding bytes & 0xFF (verified: AA+EC+D8+00+00 = 0x26E -> &0xFF = 0x6E)
 * Payloads in both directions use XOR `0xA5`. ANC set plaintext is [ancIndex][ancValue].
 */
object EdifierWireCodec {
    const val SEND_HEADER = 0xAA
    const val RECEIVE_HEADER = 0xBB
    const val RECEIVE_HEADER_OLD = 0xCC
    const val APP_CODE = 0xEC

    /**
     * XOR key for response payload encryption (Encryption10).
     * From ECCommand.EncryptionCode: Encryption10.value = Opcodes.IF_ACMPEQ = 165 = 0xA5.
     * Verified: battery response 0x99 ^ 0xA5 = 0x3C = 60%.
     */
    const val RESPONSE_XOR_KEY = 0xA5

    // Command indices (verified from Edifier Connect App + real device)
    const val CMD_BATTERY_QUERY = 0xD0        // 208 — query battery
    const val CMD_ANC_QUERY = 0xCC            // 204 — query ANC/noise state
    const val CMD_ANC_SET = 0xC1              // 193 — set ANC mode
    const val CMD_DEVICE_STATE_QUERY = 0xF2   // 242 — query TWS/device state
    const val CMD_VERSION_QUERY = 0xC6        // 198 — query version
    const val CMD_NAME_QUERY = 0xC9           // 201 — query name
    const val CMD_FUNCTION_QUERY = 0xD8       // 216 — query device capabilities
    const val CMD_GAME_STATE_QUERY = 0x08     // 8   — query game mode
    const val CMD_GAME_STATE_SET = 0x09       // 9   — set game mode

    // ANC ancIndex for W860NB PRO (ANC16 slot, verified on device)
    const val ANC_INDEX = 0x10

    // ANC ancValue mapping (verified on device):
    // 1=deep noise cancelling, 2=comfort NC, 3=wind noise, 4=ambient, 5=NC off
    const val ANC_VALUE_DEEP = 1
    const val ANC_VALUE_COMFORT = 2
    const val ANC_VALUE_WIND = 3
    const val ANC_VALUE_AMBIENT = 4
    const val ANC_VALUE_OFF = 5

    data class Frame(
        val header: Int,
        val appCode: Int,
        val commandIndex: Int,
        val payload: ByteArray,
        val bytes: ByteArray,
    )

    sealed interface BatteryState {
        /** One authoritative pack level, used by the legacy `0xD0` response. */
        data class Aggregate(
            val percent: Int,
        ) : BatteryState

        /**
         * Component levels carried by the TWS `0xF2` device-state response.
         *
         * The captured Evo Pro frame proves the left/right fields. The remaining bytes are kept
         * out of the public model until their charging/case semantics are independently verified.
         */
        data class TwsComponents(
            val leftPercent: Int,
            val rightPercent: Int,
            val casePercent: Int? = null,
            val caseCharging: Boolean = false,
        ) : BatteryState
    }

    data class AncState(
        val mode: Int,
        val level: Int?,
    )

    // ── Pre-built query packets ──

    val queryBattery: ByteArray = packet(CMD_BATTERY_QUERY)
    val queryAnc: ByteArray = packet(CMD_ANC_QUERY)
    val queryDeviceState: ByteArray = packet(CMD_DEVICE_STATE_QUERY)
    val queryFunction: ByteArray = packet(CMD_FUNCTION_QUERY)
    val queryVersion: ByteArray = packet(CMD_VERSION_QUERY)
    val queryGameState: ByteArray = packet(CMD_GAME_STATE_QUERY)

    fun setAnc(ancValue: Int, ancIndex: Int = ANC_INDEX): ByteArray =
        packet(
            CMD_ANC_SET,
            byteArrayOf(ancIndex.toByte(), ancValue.toByte()),
            xorKey = RESPONSE_XOR_KEY,
        )

    /** Game mode: payload is a single byte 1=on / 0=off, XOR-encrypted like ANC. */
    fun setGameMode(enabled: Boolean): ByteArray =
        packet(
            CMD_GAME_STATE_SET,
            byteArrayOf(if (enabled) 0x01 else 0x00),
            xorKey = RESPONSE_XOR_KEY,
        )

    // ── Parsing ──

    /** Parses command-specific battery telemetry encrypted with [RESPONSE_XOR_KEY]. */
    fun parseBatteryState(frame: Frame): BatteryState? {
        if (!isProtocolResponse(frame)) return null
        return when (frame.commandIndex) {
            // Verified: BB EC D0 00 01 99 11 -> 99 xor A5 = 3C = 60%.
            CMD_BATTERY_QUERY -> frame.payload
                .singleOrNull()
                ?.decryptPercent()
                ?.let { BatteryState.Aggregate(it) }

            // Captured Evo Pro response:
            // BB EC F2 00 06 A6 C1 C7 A5 A6 B4 CC
            // decrypted payload: 03 64 62 00 03 11. Byte 0 is metadata; bytes 1/2 are the
            // independently displayed left/right levels; byte 3 is charge-case percent and
            // byte 4 is the case state (1=charging, 2=not, 3=offline).
            CMD_DEVICE_STATE_QUERY -> frame.payload
                .takeIf { it.size >= TWS_COMPONENT_FIELD_COUNT }
                ?.let { payload ->
                    val left = payload[TWS_LEFT_BATTERY_OFFSET].decryptPercent() ?: return@let null
                    val right = payload[TWS_RIGHT_BATTERY_OFFSET].decryptPercent() ?: return@let null
                    val caseState = payload.getOrNull(TWS_CASE_STATE_OFFSET)
                        ?.unsigned()?.let { it xor RESPONSE_XOR_KEY }
                    // Verified on-device (FitClip Ultra): byte3 = case percent, byte4 = case
                    // state (1=charging, 2=not, 3=offline). Case omitted when state is offline.
                    val casePercent = when (caseState) {
                        TWS_CASE_STATE_CHARGING,
                        TWS_CASE_STATE_NOT_CHARGING,
                        -> payload.getOrNull(TWS_CASE_BATTERY_OFFSET)?.decryptPercent()

                        TWS_CASE_STATE_OFFLINE -> null
                        else -> null
                    }
                    BatteryState.TwsComponents(
                        leftPercent = left,
                        rightPercent = right,
                        casePercent = casePercent,
                        caseCharging = caseState == TWS_CASE_STATE_CHARGING,
                    )
                }

            else -> null
        }
    }

    /**
     * Parse an ANC response. Response payload is XOR-encrypted with [RESPONSE_XOR_KEY].
     * Verified: `BB EC CC 00 02 B5 A0 CA` -> payload=[0xB5,0xA0] -> XOR 0xA5 = [0x10, 0x05]
     * -> ancIndex=16, ancValue=5 (NC off).
     */
    fun parseAncState(frame: Frame): AncState? {
        if (!isProtocolResponse(frame)) return null
        if (frame.commandIndex != CMD_ANC_QUERY && frame.commandIndex != CMD_ANC_SET) {
            return null
        }
        if (frame.payload.isEmpty()) return null
        val mode = (frame.payload[0].unsigned() xor RESPONSE_XOR_KEY)
        val level = frame.payload.getOrNull(1)?.unsigned()?.let { it xor RESPONSE_XOR_KEY }
        return AncState(mode = mode, level = level)
    }

    /** Parses game-mode state from a 0x08 query or 0x09 set response (1=on, 0=off). */
    fun parseGameModeState(frame: Frame): Boolean? {
        if (!isProtocolResponse(frame)) return null
        if (frame.commandIndex != CMD_GAME_STATE_QUERY && frame.commandIndex != CMD_GAME_STATE_SET) {
            return null
        }
        val value = frame.payload.firstOrNull()?.unsigned()?.let { it xor RESPONSE_XOR_KEY } ?: return null
        return when (value) {
            0 -> false
            1 -> true
            else -> null
        }
    }

    /** A device-originated BES/Edifier frame eligible to establish protocol evidence. */
    fun isProtocolResponse(frame: Frame): Boolean =
        (frame.header == RECEIVE_HEADER || frame.header == RECEIVE_HEADER_OLD) &&
            frame.appCode == APP_CODE

    // ── Frame building ──

    fun packet(
        commandIndex: Int,
        payload: ByteArray = byteArrayOf(),
        xorKey: Int = 0, // 0 = no encryption
    ): ByteArray {
        require(commandIndex in 0..0xFF)
        val encryptedPayload = if (xorKey != 0) xorEncrypt(payload, xorKey) else payload
        val length = encryptedPayload.size
        val frame = ByteArray(5 + encryptedPayload.size + 1).apply {
            this[0] = SEND_HEADER.toByte()
            this[1] = APP_CODE.toByte()
            this[2] = commandIndex.toByte()
            this[3] = ((length shr 8) and 0xFF).toByte()
            this[4] = (length and 0xFF).toByte()
            encryptedPayload.copyInto(this, destinationOffset = 5)
        }
        // Calculate CRC
        var crc = 0
        for (i in 0 until frame.size - 1) {
            crc += frame[i].unsigned()
        }
        frame[frame.size - 1] = (crc and 0xFF).toByte()
        return frame
    }

    fun xorEncrypt(data: ByteArray, key: Int): ByteArray {
        val result = ByteArray(data.size)
        for (i in data.indices) {
            result[i] = ((data[i].unsigned() xor key) and 0xFF).toByte()
        }
        return result
    }

    // ── Decoder ──

    class Decoder(initialCapacity: Int = 256) {
        private var bytes = ByteArray(initialCapacity.coerceAtLeast(16))
        private var size = 0

        fun offer(chunk: ByteArray, xorKey: Int = 0): List<Frame> {
            if (chunk.isEmpty()) return emptyList()
            append(chunk)
            val frames = mutableListOf<Frame>()
            while (true) {
                discardNoise()
                if (size < 6) return frames // minimum: header(1) + appCode(1) + cmd(1) + len(2) + crc(1)

                val header = peek(0)
                if (header != RECEIVE_HEADER && header != RECEIVE_HEADER_OLD && header != SEND_HEADER) {
                    discard(1)
                    continue
                }

                val lengthField = (peek(3) shl 8) or peek(4)
                val frameLength = 5 + lengthField + 1 // header + appCode + cmd + len(2) + payload + crc
                if (size < frameLength) return frames

                // Verify CRC
                val candidate = peekBytes(frameLength)
                var crcSum = 0
                for (i in 0 until frameLength - 1) {
                    crcSum += candidate[i].unsigned()
                }
                val expectedCrc = crcSum and 0xFF
                val actualCrc = candidate[frameLength - 1].unsigned()
                if (expectedCrc != actualCrc) {
                    discard(1)
                    continue
                }

                // Valid frame, consume it
                discard(frameLength)
                val rawPayload = candidate.copyOfRange(5, 5 + lengthField)
                val decryptedPayload = if (xorKey != 0) xorEncrypt(rawPayload, xorKey) else rawPayload
                frames += Frame(
                    header = header,
                    appCode = candidate[1].unsigned(),
                    commandIndex = candidate[2].unsigned(),
                    payload = decryptedPayload,
                    bytes = candidate,
                )
            }
        }

        fun reset() {
            size = 0
        }

        private fun append(chunk: ByteArray) {
            ensureCapacity(size + chunk.size)
            chunk.copyInto(bytes, destinationOffset = size)
            size += chunk.size
        }

        private fun discardNoise() {
            var count = 0
            while (count < size) {
                val b = peek(count)
                if (b == RECEIVE_HEADER || b == RECEIVE_HEADER_OLD || b == SEND_HEADER) break
                count++
            }
            if (count > 0) discard(count)
        }

        private fun peek(index: Int): Int = bytes[index].unsigned()
        private fun peekBytes(count: Int): ByteArray = bytes.copyOfRange(0, count)

        private fun discard(count: Int) {
            if (count >= size) {
                size = 0
                return
            }
            bytes.copyInto(bytes, destinationOffset = 0, startIndex = count, endIndex = size)
            size -= count
        }

        private fun ensureCapacity(required: Int) {
            if (required <= bytes.size) return
            var capacity = bytes.size
            while (capacity < required) capacity *= 2
            bytes = bytes.copyOf(capacity)
        }
    }

    fun ByteArray.hex(): String =
        joinToString(" ") { "%02X".format(it.unsigned()) }

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private fun Byte.decryptPercent(): Int? =
        (unsigned() xor RESPONSE_XOR_KEY).takeIf { it in 0..100 }

    private const val TWS_COMPONENT_FIELD_COUNT = 3
    private const val TWS_LEFT_BATTERY_OFFSET = 1
    private const val TWS_RIGHT_BATTERY_OFFSET = 2
    private const val TWS_CASE_BATTERY_OFFSET = 3
    private const val TWS_CASE_STATE_OFFSET = 4

    // BoxStateEnum: 1=Charging, 2=NotCharging, 3=Offline(undetectable).
    private const val TWS_CASE_STATE_CHARGING = 1
    private const val TWS_CASE_STATE_NOT_CHARGING = 2
    private const val TWS_CASE_STATE_OFFLINE = 3
}
