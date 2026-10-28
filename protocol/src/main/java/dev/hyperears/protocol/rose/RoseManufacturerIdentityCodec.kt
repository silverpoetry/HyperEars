package dev.hyperears.protocol.rose

/** Only one exact model across all manufacturer records may refine a live connection. */
object RoseManufacturerIdentityCodec {
    fun modelId(data: Map<Int, List<Int>>): String? {
        if (data.size !in 1..8 || data.any { (company, value) -> company !in 0..65535 ||
                value.size !in 1..512 || value.any { it !in 0..255 } }) return null
        return data.entries.flatMap { (company, bytes) ->
            val single = mapOf(company to bytes)
            listOfNotNull(RoseRsV2IdentityCodec.identify(single)?.id,
                RoseZtIdentityCodec.identify(single)?.id,
                RoseLegacyIdentityCodec.identify(single)?.id)
        }.distinct().singleOrNull()
    }
}
