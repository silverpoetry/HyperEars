package dev.hyperears.protocol.rose

/** ROSELINK 3.8.2 constructor advertisement prefixes, not retail Bluetooth names. */
object RoseRsV2IdentityCodec {
    const val SERVICE_UUID = "0cf12d31-fac3-4553-bd80-d6832e7b3931"
    enum class Model(val prefix: String, val label: String, val noise: Boolean = false,
        val eqPresets: List<Int> = listOf(0,1,2), val audio: Boolean = true, val touch: Boolean = true,
        val bass: Boolean = false) {
        CERAMICS_MK2("0b0e0a0c","ROSE Ceramics MK2",true),
        CERAMICS_MK2_5736("45d7b286","ROSE Ceramics MK2 (5736)",true,audio=false),
        CERAMICS_MK2_8912F("0b0e208b","ROSE Ceramics MK2 (8912F)",true),
        OPENFEEL_MK2("0b0eb657","ROSE OpenFeel MK2",eqPresets=emptyList(),bass=true),
        OPENFEEL_L("45d7cbed","ROSE OpenFeel L",eqPresets=listOf(0,1)),
        SPORTFEEL_8912F("5d2f61f0","ROSE SportFeel (8912F)",eqPresets=listOf(0,1,2,3)),
        SPEAKFEEL("0b0f0a0b","ROSE SpeakFeel",touch=false),
        SPEAKFEEL2("0b0e3ce6","ROSE SPEAKFEEL",eqPresets=emptyList(),bass=true),
        ASTRO_X("0b0e0a0b","ROSE Astro X");
        val id: String get() = "rose-rsv2-${name.lowercase().replace('_','-')}"
        val audioModes: List<Int> get() = when(this) { OPENFEEL_L -> listOf(0,1); SPEAKFEEL2 -> listOf(0,1,2); SPEAKFEEL -> listOf(0,1,4,5,6); CERAMICS_MK2_5736 -> emptyList(); else -> listOf(0,1,2,3) }
        val touchMaster: Boolean get() = touch && this != SPEAKFEEL2
        val bassLevels: List<Int> get() = if (this == OPENFEEL_MK2) listOf(3) else if (this == SPEAKFEEL2) listOf(0,1) else emptyList()
        val touchActions: IntRange get() = if (noise || this == SPORTFEEL_8912F) 0..8 else 0..7
    }
    fun identify(manufacturerData: Map<Int,List<Int>>): Model? {
        if (manufacturerData.size !in 1..8) return null
        val matches = manufacturerData.mapNotNull { (company, payload) ->
            if (company !in 0..65535 || payload.size !in 2..512 || payload.any { it !in 0..255 }) return@mapNotNull null
            val prefix = listOf(company and 255, company ushr 8, payload[0], payload[1])
                .joinToString("") { it.toString(16).padStart(2,'0') }
            Model.entries.singleOrNull { it.prefix == prefix }
        }.distinct()
        return matches.singleOrNull()
    }
}
