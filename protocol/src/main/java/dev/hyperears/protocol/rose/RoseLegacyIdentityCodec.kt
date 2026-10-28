package dev.hyperears.protocol.rose

/** Exact ROSELINK 3.8.2 config manufacturer prefixes; generic RS-Common is not an identity. */
object RoseLegacyIdentityCodec {
    const val SERVICE_UUID = "0cf12d31-fac3-4553-bd80-d6832e7b3931"
    enum class Model(val prefixes: List<String>,val label:String,val spp:Boolean,val head:Boolean=false) {
        AGATE(listOf("18840505"),"ROSE AGATE",false),
        AGATE_X(listOf("18840506"),"ROSE AGATE X",false),
        CERAMICS(listOf("18840100","18840103"),"ROSE CERAMICS",false),
        CERAMICS_X_7034(listOf("18840112"),"ROSE Ceramics X (7034)",true),
        EXPLORER(listOf("18840107"),"ROSE EXPLORER",true),
        BUDSFREE(listOf("12cc3e4c"),"ROSE BudsFree i",false),
        CAMBRIAN(listOf("0b0c0a0c"),"ROSE Cambrian",true,true),
        OPENFEEL(listOf("0b0a0a0b","0b0a0a0c","0b0a0a0d"),"ROSE OpenFeel",false),
        OPENFUN(listOf("0b0a0a0e","0b0a0a10"),"ROSE OpenFun",true),
        OPENLINK(listOf("0b0a5b9d"),"ROSE OpenLink",true),
        SPORTFEEL(listOf("0b0a0a0f"),"ROSE SportFeel",true),
        SWIFT(listOf("0b0b0a0b"),"ROSE SWIFT",false,true);
        val f8 get() = this in setOf(AGATE, AGATE_X, CERAMICS, CERAMICS_X_7034, EXPLORER)
        val id get()="rose-legacy-"+name.lowercase(java.util.Locale.ROOT)
    }
    fun identify(data:Map<Int,List<Int>>):Model? {
        if(data.size !in 1..8) return null
        val found=data.mapNotNull { (company,bytes) ->
            if(company !in 0..65535 || bytes.size !in 2..512 || bytes.any { it !in 0..255 }) return@mapNotNull null
            val prefix=listOf(company and 255,company ushr 8,bytes[0],bytes[1]).joinToString("") { "%02x".format(java.util.Locale.ROOT,it) }
            Model.entries.singleOrNull { prefix in it.prefixes }
        }.distinct()
        return found.singleOrNull()
    }
}
