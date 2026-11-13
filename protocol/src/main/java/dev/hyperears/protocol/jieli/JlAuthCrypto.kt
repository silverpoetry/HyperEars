// SPDX-License-Identifier: Apache-2.0
// Kotlin port contributed by Jgckm; source and bundled license are listed in THIRD_PARTY_NOTICES.md.
package dev.hyperears.protocol.jieli

/**
 * Device authentication (btcon) for JieLi RCSP connections.
 *
 * Algorithm and constants are ported from the official Apache-2.0 licensed WeChat mini-program OTA
 * SDK (`jl_auth_2.0.0.js`, Jieli-Tech/WeChat-Mini-Program-OTA). The handshake establishes trust
 * before RCSP commands are accepted; until it completes the headset ignores almost every command.
 *
 * Exchange (six packets, bare packets without the RCSP framing):
 * 1. app → device: `00` + 16 random bytes;
 * 2. device → app: `00` + 16-byte challenge;
 * 3. app → device: `[0x01] authHash(challenge)` (17 bytes);
 * 4. device → app: 17-byte confirmation starting with `0x01`;
 * 5. app → device: `02 'p' 'a' 's' 's'`;
 * 6. device → app: the same five bytes — authentication accepted.
 */
object JlAuthCrypto {
    private val IV: IntArray = intArrayOf(17, 34, 51, 51, 34, 17)

    /** Default link key; callers may substitute a 16-byte custom key when the SDK requires it. */
    private val DEFAULT_KEY: IntArray = intArrayOf(6, 119, 95, 135, 145, 141, 212, 35, 0, 93, 241, 216, 207, 12, 20, 43)

    private val ROUND: Array<IntArray> = arrayOf(
        intArrayOf(119, 241, 86, 36, 126, 71, 27, 134, 189, 112, 142, 30, 59, 115, 22, 3),
        intArrayOf(100, 172, 40, 90, 201, 179, 55, 197, 10, 16, 183, 163, 186, 177, 151, 70),
        intArrayOf(61, 5, 220, 102, 110, 246, 154, 248, 13, 88, 149, 103, 198, 170, 171, 236),
        intArrayOf(160, 104, 155, 150, 212, 235, 191, 67, 73, 54, 233, 106, 137, 216, 195, 138),
        intArrayOf(148, 99, 153, 188, 123, 190, 193, 34, 187, 92, 113, 213, 31, 146, 87, 93),
        intArrayOf(143, 68, 65, 29, 81, 230, 64, 23, 251, 253, 25, 50, 52, 184, 97, 42),
        intArrayOf(202, 35, 111, 218, 57, 247, 162, 1, 127, 214, 49, 231, 222, 128, 4, 221),
        intArrayOf(44, 89, 130, 175, 168, 224, 15, 205, 161, 18, 62, 48, 209, 28, 208, 58),
        intArrayOf(51, 114, 46, 79, 144, 2, 19, 6, 117, 206, 135, 194, 239, 178, 173, 125),
        intArrayOf(56, 21, 225, 82, 159, 122, 108, 47, 39, 196, 226, 129, 169, 207, 141, 192),
        intArrayOf(215, 223, 255, 96, 118, 20, 140, 94, 85, 9, 228, 8, 199, 66, 32, 252),
        intArrayOf(210, 80, 145, 217, 76, 98, 158, 232, 185, 166, 249, 26, 0, 33, 11, 250),
        intArrayOf(53, 156, 78, 75, 105, 72, 203, 14, 200, 164, 91, 234, 132, 7, 180, 24),
        intArrayOf(244, 174, 107, 219, 167, 204, 63, 139, 74, 12, 60, 37, 229, 84, 77, 69),
        intArrayOf(131, 237, 17, 240, 176, 83, 147, 242, 116, 38, 181, 157, 109, 124, 243, 45),
        intArrayOf(241, 86, 36, 126, 71, 27, 134, 189, 112, 142, 30, 59, 115, 22, 3, 182),
        intArrayOf(172, 40, 90, 201, 179, 55, 197, 10, 16, 183, 163, 186, 177, 151, 70, 136),
    )

    private val SBOX: IntArray = intArrayOf(1, 45, 226, 147, 190, 69, 21, 174, 120, 3, 135, 164, 184, 56, 207, 63, 8, 103, 9, 148, 235, 38, 168, 107, 189, 24, 52, 27, 187, 191, 114, 247, 64, 53, 72, 156, 81, 47, 59, 85, 227, 192, 159, 216, 211, 243, 141, 177, 255, 167, 62, 220, 134, 119, 215, 166, 17, 251, 244, 186, 146, 145, 100, 131, 241, 51, 239, 218, 44, 181, 178, 43, 136, 209, 153, 203, 140, 132, 29, 20, 129, 151, 113, 202, 95, 163, 139, 87, 60, 130, 196, 82, 92, 28, 232, 160, 4, 180, 133, 74, 246, 19, 84, 182, 223, 12, 26, 142, 222, 224, 57, 252, 32, 155, 36, 78, 169, 152, 158, 171, 242, 96, 208, 108, 234, 250, 199, 217, 0, 212, 31, 110, 67, 188, 236, 83, 137, 254, 122, 93, 73, 201, 50, 194, 249, 154, 248, 109, 22, 219, 89, 150, 68, 233, 205, 230, 70, 66, 143, 10, 193, 204, 185, 101, 176, 210, 198, 172, 30, 65, 98, 41, 46, 14, 116, 80, 2, 90, 195, 37, 123, 138, 42, 91, 240, 6, 13, 71, 111, 112, 157, 126, 16, 206, 18, 39, 213, 76, 79, 214, 121, 48, 104, 54, 117, 125, 228, 237, 128, 106, 144, 55, 162, 94, 118, 170, 197, 127, 61, 175, 165, 229, 25, 97, 253, 77, 124, 183, 11, 238, 173, 75, 34, 245, 231, 115, 35, 33, 200, 5, 225, 102, 221, 179, 88, 105, 99, 86, 15, 161, 49, 149, 23, 7, 58, 40)

    private val INV_SBOX: IntArray = intArrayOf(128, 0, 176, 9, 96, 239, 185, 253, 16, 18, 159, 228, 105, 186, 173, 248, 192, 56, 194, 101, 79, 6, 148, 252, 25, 222, 106, 27, 93, 78, 168, 130, 112, 237, 232, 236, 114, 179, 21, 195, 255, 171, 182, 71, 68, 1, 172, 37, 201, 250, 142, 65, 26, 33, 203, 211, 13, 110, 254, 38, 88, 218, 50, 15, 32, 169, 157, 132, 152, 5, 156, 187, 34, 140, 99, 231, 197, 225, 115, 198, 175, 36, 91, 135, 102, 39, 247, 87, 244, 150, 177, 183, 92, 139, 213, 84, 121, 223, 170, 246, 62, 163, 241, 17, 202, 245, 209, 23, 123, 147, 131, 188, 189, 82, 30, 235, 174, 204, 214, 53, 8, 200, 138, 180, 226, 205, 191, 217, 208, 80, 89, 63, 77, 98, 52, 10, 72, 136, 181, 86, 76, 46, 107, 158, 210, 61, 60, 3, 19, 251, 151, 81, 117, 74, 145, 113, 35, 190, 118, 42, 95, 249, 212, 85, 11, 220, 55, 49, 22, 116, 215, 119, 167, 230, 7, 219, 164, 47, 70, 243, 97, 69, 103, 227, 12, 162, 59, 28, 133, 24, 4, 29, 41, 160, 143, 178, 90, 216, 166, 126, 238, 141, 83, 75, 161, 154, 193, 14, 122, 73, 165, 44, 129, 196, 199, 54, 43, 127, 67, 149, 51, 242, 108, 104, 109, 240, 2, 40, 206, 221, 155, 234, 94, 153, 124, 20, 134, 207, 229, 66, 184, 64, 120, 45, 58, 233, 100, 31, 146, 144, 125, 57, 111, 224, 137, 48)

    /** Builds the 17-byte auth start packet: `0x00` followed by 16 random bytes. */
    fun authStart(random: ByteArray): ByteArray {
        require(random.size == 16) { "Random data must be 16 bytes" }
        val out = ByteArray(17)
        out[0] = 0x00
        random.copyInto(out, destinationOffset = 1)
        return out
    }

    /** Computes the 17-byte proof for a 16-byte device challenge; the first byte is `0x01`. */
    fun authResponse(challenge: ByteArray, key: ByteArray? = null): ByteArray {
        require(challenge.size == 16) { "Challenge must be 16 bytes" }
        if (key != null) require(key.size == 16) { "Link key must be 16 bytes" }
        val input = IntArray(16) { challenge[it].toInt() and 0xFF }
        val keyInts = if (key == null) {
            DEFAULT_KEY.copyOf()
        } else {
            IntArray(16) { key[it].toInt() and 0xFF }
        }
        val hash = hash(input, keyInts)
        val out = ByteArray(17)
        out[0] = 0x01
        for (index in 0 until 16) out[index + 1] = hash[index].toByte()
        return out
    }

    /** Five-byte acknowledgement the app sends after a successful proof. */
    fun authOk(): ByteArray = byteArrayOf(0x02, 0x70, 0x61, 0x73, 0x73)

    private fun hash(input: IntArray, key: IntArray): IntArray {
        val schedule = Array(17) { IntArray(16) }
        val mix = IntArray(16)
        var cursor = 0
        for (index in 0 until 16) {
            mix[index] = IV[cursor]
            cursor++
            if (cursor > 5) cursor = 0
        }
        val state = input.copyOf()
        scheduleKey(key, schedule)
        encrypt(state, schedule, false)
        for (index in 0 until 16) {
            state[index] = (state[index] xor input[index]) and 0xFF
            state[index] = (state[index] + mix[index]) and 0xFF
        }
        val secondKey = IntArray(16)
        secondKey[0] = (key[0] + 233) and 0xFF
        secondKey[1] = 229 xor key[1]
        secondKey[2] = (key[2] + 223) and 0xFF
        secondKey[3] = 193 xor key[3]
        secondKey[4] = (key[4] + 179) and 0xFF
        secondKey[5] = 167 xor key[5]
        secondKey[6] = (key[6] + 149) and 0xFF
        secondKey[7] = 131 xor key[7]
        secondKey[8] = 233 xor key[8]
        secondKey[9] = (key[9] + 229) and 0xFF
        secondKey[10] = 223 xor key[10]
        secondKey[11] = (key[11] + 193) and 0xFF
        secondKey[12] = 179 xor key[12]
        secondKey[13] = (key[13] + 167) and 0xFF
        secondKey[14] = 149 xor key[14]
        secondKey[15] = (key[15] + 131) and 0xFF
        scheduleKey(secondKey, schedule)
        encrypt(state, schedule, true)
        return state
    }

    private fun mixPair(state: IntArray) {
        var index = 0
        for (pair in 0 until 8) {
            val doubled = ((state[index] shl 1) + state[index + 1]) and 0xFF
            state[index + 1] = (state[index] + state[index + 1]) and 0xFF
            state[index] = doubled
            index += 2
        }
    }

    private fun permute(state: IntArray) {
        val copy = state.copyOf()
        state[0] = copy[8]
        state[1] = copy[11]
        state[2] = copy[12]
        state[3] = copy[15]
        state[4] = copy[2]
        state[5] = copy[1]
        state[6] = copy[6]
        state[7] = copy[5]
        state[8] = copy[10]
        state[9] = copy[9]
        state[10] = copy[14]
        state[11] = copy[13]
        state[12] = copy[0]
        state[13] = copy[7]
        state[14] = copy[4]
        state[15] = copy[3]
    }

    private fun rotateLeft3(state: IntArray) {
        for (index in 0 until 17) {
            val carry = (state[index] ushr 5) and 0xFF
            state[index] = (state[index] shl 3) and 0xFF
            state[index] = (state[index] + carry) and 0xFF
        }
    }

    private fun scheduleKey(key: IntArray, schedule: Array<IntArray>) {
        for (row in 0 until 17) for (column in 0 until 16) schedule[row][column] = 0
        val material = IntArray(17)
        material[16] = 0
        for (index in 0 until 16) {
            material[index] = key[index]
            material[16] = material[16] xor material[index]
        }
        for (index in 0 until 16) {
            schedule[0][index] = material[index]
        }
        val rolling = IntArray(17)
        for (index in 0 until 16) rolling[index] = material[index]
        rolling[16] = material[16]
        for (roundIndex in 0 until 16) {
            rotateLeft3(rolling)
            var cursor = roundIndex + 1
            for (column in 0 until 16) {
                schedule[roundIndex + 1][column] =
                    (rolling[cursor] + ROUND[roundIndex + 1][15 - column]) and 0xFF
                cursor++
                if (cursor > 16) cursor = 0
            }
        }
    }

    private fun round(
        roundIndex: Int,
        state: IntArray,
        schedule: Array<IntArray>,
    ) {
        for (index in 0 until 16) {
            state[index] = when (index) {
                0, 3, 4, 7, 8, 11, 12, 15 ->
                    (state[index] xor schedule[2 * roundIndex][index]) and 0xFF

                else -> (state[index] + schedule[2 * roundIndex][index]) and 0xFF
            }
        }
        val copy = state.copyOf()
        state[0] = SBOX[copy[0]]
        state[3] = SBOX[copy[3]]
        state[4] = SBOX[copy[4]]
        state[7] = SBOX[copy[7]]
        state[8] = SBOX[copy[8]]
        state[11] = SBOX[copy[11]]
        state[12] = SBOX[copy[12]]
        state[15] = SBOX[copy[15]]
        state[1] = INV_SBOX[copy[1]]
        state[2] = INV_SBOX[copy[2]]
        state[5] = INV_SBOX[copy[5]]
        state[6] = INV_SBOX[copy[6]]
        state[9] = INV_SBOX[copy[9]]
        state[10] = INV_SBOX[copy[10]]
        state[13] = INV_SBOX[copy[13]]
        state[14] = INV_SBOX[copy[14]]
        for (index in 0 until 16) {
            state[index] = when (index) {
                0, 3, 4, 7, 8, 11, 12, 15 ->
                    (state[index] + schedule[2 * roundIndex + 1][index]) and 0xFF

                else -> (state[index] xor schedule[2 * roundIndex + 1][index]) and 0xFF
            }
        }
        mixPair(state)
        permute(state)
        mixPair(state)
        permute(state)
        mixPair(state)
        permute(state)
        mixPair(state)
    }

    private fun encrypt(state: IntArray, schedule: Array<IntArray>, keyMix: Boolean) {
        val saved = state.copyOf()
        for (roundIndex in 0 until 8) {
            if (roundIndex == 2 && keyMix) {
                for (index in 0 until 16) {
                    state[index] = when (index) {
                        0, 3, 4, 7, 8, 11, 12, 15 ->
                            (saved[index] xor state[index]) and 0xFF

                        else -> (saved[index] + state[index]) and 0xFF
                    }
                }
            }
            round(roundIndex, state, schedule)
        }
        for (index in 0 until 16) {
            state[index] = when (index) {
                0, 3, 4, 7, 8, 11, 12, 15 ->
                    (state[index] xor schedule[16][index]) and 0xFF

                else -> (state[index] + schedule[16][index]) and 0xFF
            }
        }
    }
}
