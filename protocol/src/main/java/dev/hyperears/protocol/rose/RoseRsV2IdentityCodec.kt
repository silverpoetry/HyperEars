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
        ASTRO_X("0b0e0a0b","ROSE Astro X"),
        MIRROR("0b0a0a13","ROSE Mirror",audio=false),
        SWIFT2("5999064c","ROSE SWIFT 2",eqPresets=emptyList(),audio=false),
        LUMINOUS_AGUMON("0b0a34fd","ROSE Agumon",eqPresets=emptyList(),audio=false),
        LUMINOUS_GABUMON("0b0a2aed","ROSE Gabumon",eqPresets=emptyList(),audio=false),
        LUMINOUS_PATAMON("0b0ae916","ROSE Patamon",eqPresets=emptyList(),audio=false),
        LUMINOUS_TAILMON("0b0a941f","ROSE Tailmon",eqPresets=emptyList(),audio=false),
        CERAMICS_U_HSC_2710("0b137957","ROSE Ceramics U (HSC-2710)",true,eqPresets=listOf(3,0,1,2)),
        FIRST_ANGEL("0b0e64be","ROSE First Angel",eqPresets=listOf(1,0),audio=false),
        HYACINTH("0b120a0b","ROSE Hyacinth"),
        JASMINE("0b0a0a11","ROSE Jasmine",eqPresets=emptyList(),audio=false),
        KY01("0a0b0a0d","ROSE KY01",audio=false),
        MAGICIAN("0b0e0a0d","ROSE Magician",eqPresets=emptyList(),touch=false),
        MOKA("68f5b8eb","ROSE Moka",touch=false),
        OPAL("0b0a0a12","ROSE Opal",true,audio=false),
        ZIRCON("0b110a0b","ROSE Zircon",true),
        BUDSFEEL_MK2_SOURCE("0b150a0b","ROSE BudsFeel MK2",true,eqPresets=emptyList()),
        EARFEEL_I7_SOURCE("b8e5290f","ROSE EarFeel i7",true,eqPresets=listOf(0,1,2,3)),
        CERAMICS_U_SOURCE("0b0d0a0b","ROSE Ceramics U",true,eqPresets=listOf(3,0,1,2)),
        FURINA_SOURCE("0b110a0c","ROSE Furina",true);
        /** These profiles refine existing adapter classes/IDs, never register duplicate adapters. */
        val existingAdapterId: String? get() = when(this) {
            BUDSFEEL_MK2_SOURCE -> "rose-budsfeel-mk2"
            EARFEEL_I7_SOURCE -> "rose-earfeel-i7"
            CERAMICS_U_SOURCE -> "rose-ceramics-u"
            FURINA_SOURCE -> "furina-endless-budsfeel"
            else -> null
        }
        val ancLevels: List<Int> get() = if(this in setOf(CERAMICS_MK2,CERAMICS_MK2_5736,CERAMICS_MK2_8912F,CERAMICS_U_HSC_2710,ZIRCON,EARFEEL_I7_SOURCE,CERAMICS_U_SOURCE,FURINA_SOURCE)) listOf(5,3,1) else emptyList()
        val transparencyLevels: List<Int> get() = if(this in setOf(CERAMICS_U_HSC_2710,CERAMICS_U_SOURCE)) listOf(5,1) else ancLevels
        val languageSupported: Boolean get() = this in setOf(CERAMICS_MK2,CERAMICS_MK2_8912F,HYACINTH,JASMINE,KY01,MAGICIAN,SPEAKFEEL,ZIRCON,CERAMICS_U_SOURCE,FURINA_SOURCE)
        val wearDetectionSupported: Boolean get() = this in setOf(ASTRO_X,ZIRCON,EARFEEL_I7_SOURCE,FURINA_SOURCE)
        val gainAndProtection: Boolean get() = this in setOf(OPENFEEL_L, OPENFEEL_MK2, SPEAKFEEL2, ASTRO_X, EARFEEL_I7_SOURCE)
        val gainSupported: Boolean get() = gainAndProtection || this in setOf(CERAMICS_U_HSC_2710,CERAMICS_U_SOURCE)
        val hearingProtectionSupported: Boolean get() = gainAndProtection
        val findSupported: Boolean get() = this != MOKA
        val noiseValues: Set<Int> get() = if (!noise) emptySet() else if (this == OPAL) setOf(1,2,3) else setOf(1,2,3,4)
        val aggregateBattery: Boolean get() = this in setOf(SPEAKFEEL, SPEAKFEEL2, SWIFT2, FIRST_ANGEL, MAGICIAN, MOKA)
        val caseBattery: Boolean get() = !aggregateBattery && this !in setOf(LUMINOUS_AGUMON,LUMINOUS_GABUMON,LUMINOUS_PATAMON,LUMINOUS_TAILMON,JASMINE)
        val id: String get() = existingAdapterId ?: "rose-rsv2-${name.lowercase().replace('_','-')}"
        val audioModes: List<Int> get() = when(this) { OPENFEEL_L, HYACINTH, MOKA -> listOf(0,1); CERAMICS_U_HSC_2710, CERAMICS_U_SOURCE -> listOf(0,1,2); BUDSFEEL_MK2_SOURCE -> listOf(0,1,3); MAGICIAN -> listOf(0,1,4,5); SPEAKFEEL2 -> listOf(0,1,2); SPEAKFEEL -> listOf(0,1,4,5,6); CERAMICS_MK2_5736 -> emptyList(); else -> listOf(0,1,2,3) }
        val touchMaster: Boolean get() = touch && this != SPEAKFEEL2
        val bassLevels: List<Int> get() = if (this == OPENFEEL_MK2) listOf(3) else if (this == SPEAKFEEL2) listOf(0,1) else emptyList()
        val touchActions: IntRange get() = if (this in setOf(CERAMICS_MK2,CERAMICS_MK2_5736,CERAMICS_MK2_8912F,CERAMICS_U_HSC_2710,KY01,OPAL,ZIRCON,SPORTFEEL_8912F,BUDSFEEL_MK2_SOURCE,EARFEEL_I7_SOURCE,CERAMICS_U_SOURCE,FURINA_SOURCE)) 0..8 else 0..7
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
