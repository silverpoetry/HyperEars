package dev.hyperears.protocol.rose

/** Parses the captured Ceramics X companion-advertisement identity fields. */
object RoseCeramicsXAdvertisementCodec {
    data class Advertisement(
        /** Last two bytes of the active Classic audio endpoint address, in display order. */
        val audioDeviceAddressSuffix: Int,
    )

    fun parse(manufacturerData: ByteArray): Advertisement? {
        if (manufacturerData.size != PAYLOAD_SIZE) return null
        if (manufacturerData[0] != MESSAGE_TYPE) return null
        if (manufacturerData[1] != PAYLOAD_LENGTH_FIELD) return null
        if (manufacturerData[2] != RESERVED_ZERO) return null

        return Advertisement(
            audioDeviceAddressSuffix =
                manufacturerData[AUDIO_ADDRESS_SUFFIX_OFFSET].unsigned() shl 8 or
                    manufacturerData[AUDIO_ADDRESS_SUFFIX_OFFSET + 1].unsigned(),
        )
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    const val MANUFACTURER_ID = 0x8418
    private const val PAYLOAD_SIZE = 13
    private const val AUDIO_ADDRESS_SUFFIX_OFFSET = 7
    private const val MESSAGE_TYPE: Byte = 0x01
    private const val PAYLOAD_LENGTH_FIELD: Byte = 0x09
    private const val RESERVED_ZERO: Byte = 0x00
}
