package dev.hyperears.protocol.sony

/** V2 Table1 SYSTEM inquiry 0x0d; service IDs are raw SAR IDs, not gesture functions. */
object SonyQuickAccessCodec {
    data class Action(val id:Int,val default:Int,val services:List<Int>)
    data class Capability(val key:Int,val type:Int,val actions:List<Action>)
    fun discovery(bytes:ByteArray):Boolean? {
        val p=bytes.u()
        if(p.size<3 || p[0]!=7 || p[1]!=0 || p.size!=3+2*p[2])return null
        return p.drop(3).chunked(2).any{it[0]==0xFD}
    }
    fun capability(bytes:ByteArray):Capability? {
        val p=bytes.u()
        if(p.size<5 || p[0]!=0xF1 || p[1]!=13 || p[2] !in 0..2 || p[3] !in 0..2 || p[4]>8)return null
        var offset=5
        val result=mutableListOf<Action>()
        repeat(p[4]) {
            if(offset+3>p.size)return null
            val action=p[offset++];val default=p[offset++];val count=p[offset++]
            if(action !in setOf(0,1,2,3,16,17,33,34) || result.any{it.id==action} || count==0 || offset+count>p.size)return null
            val values=p.subList(offset,offset+count).toList();offset+=count
            if(values.distinct().size!=values.size)return null
            result+=Action(action,default,values)
        }
        return if(offset==p.size)Capability(p[2],p[3],result)else null
    }
    fun values(bytes:ByteArray):List<Int>? {
        val p=bytes.u()
        if(p.size<3 || p[0] !in setOf(0xF7,0xF9) || p[1]!=13 || p.size!=3+p[2])return null
        return p.drop(3)
    }
    fun availability(bytes:ByteArray):Boolean? {
        val p=bytes.u()
        return if(p.size==3 && p[0] in setOf(0xF3,0xF5) && p[1]==13 && p[2] in 0..1)p[2]==0 else null
    }
    fun bytes(vararg values:Int)=values.map(Int::toByte).toByteArray()
    private fun ByteArray.u()=map{it.toInt()and 255}
}
