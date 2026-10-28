package dev.hyperears.protocol.sony
/** Independent protocol facts: V1 Table1 VPT and sound position. */
object SonyVirtualSoundCodec {
    fun functions(payload:ByteArray):List<Int>? {
        val p=payload.map{it.toInt() and 255}
        if(p.size<3 || p[0]!=7 || p[1]!=0 || p.size!=3+p[2])return null
        return listOf(0x41 to 1,0x42 to 2).filter{it.first in p.drop(3)}.map{it.second}
    }
    fun values(type:Int):Set<Int> = when(type){1->(0..4).toSet();2->setOf(0,1,2,3,0x11,0x12);else->emptySet()}
    fun report(payload:ByteArray):Pair<Int,Int>? {
        val p=payload.map{it.toInt() and 255}
        if(p.size!=3 || p[0] !in setOf(0x47,0x49) || p[2] !in values(p[1]))return null
        return p[1] to p[2]
    }
    fun read(type:Int):ByteArray {require(type in 1..2);return byteArrayOf(0x46,type.toByte())}
    fun write(type:Int,value:Int):ByteArray {require(value in values(type));return byteArrayOf(0x48,type.toByte(),value.toByte())}
}
