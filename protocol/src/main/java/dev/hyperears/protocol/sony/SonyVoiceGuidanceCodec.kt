package dev.hyperears.protocol.sony

/** V1 Table2 facts; concrete Gadgetbridge getter includes setting01 after inquiry01. */
object SonyVoiceGuidanceCodec {
    fun function(payload:ByteArray):Boolean? {
        val p=payload.map{it.toInt() and 255}
        if(p.size<3 || p[0]!=7 || p[1]!=0 || p.size!=3+p[2])return null
        return 0x39 in p.drop(3)
    }
    fun read()=byteArrayOf(0x46,1,1)
    fun report(payload:ByteArray):Boolean? {
        val p=payload.map{it.toInt() and 255}
        if(p.size!=4 || p[0] !in setOf(0x47,0x49) || p[1]!=1 || p[2]!=1 || p[3] !in 0..1)return null
        return p[3]==1
    }
    fun write(enabled:Boolean)=byteArrayOf(0x48,1,1,if(enabled)1 else 0)
}
