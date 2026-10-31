package dev.hyperears.protocol.sony

object SonyVendorToolsCodec {
    /** V1 actual support list only; this is a navigation hint, never an optimizer command. */
    fun optimizerFunction(payload:ByteArray):Boolean? {
        val p=payload.map{it.toInt() and 255}
        if(p.size<3 || p[0]!=7 || p[1]!=0 || p.size!=3+p[2])return null
        return 0x81 in p.drop(3)
    }
}
