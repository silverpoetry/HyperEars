package dev.hyperears.protocol.rose

/** Only one exact model across all manufacturer records may refine a live connection. */
object RoseManufacturerIdentityCodec {
    /** A discovery hint only: names never grant a model or a control capability. */
    fun candidateName(name: String): Boolean {
        fun normalized(value: String) = value.filter(Char::isLetterOrDigit).lowercase(java.util.Locale.ROOT)
        val candidate = normalized(name)
        if (candidate.isEmpty()) return false
        // Original and 7034 share this source display name; only advertisement distinguishes them.
        if (candidate in setOf("ceramicsx", "furinaendlesssoloofsolitude")) return true
        val labels = RoseRsV2IdentityCodec.Model.entries.map { it.label } +
            RoseLegacyIdentityCodec.Model.entries.map { it.label } + RoseZtWireCodec.Model.entries.map { it.display }
        return labels.any { label -> candidate == normalized(label) ||
            candidate == normalized(label.removePrefix("ROSE ").removePrefix("ROSESELSA ")) }
    }

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
