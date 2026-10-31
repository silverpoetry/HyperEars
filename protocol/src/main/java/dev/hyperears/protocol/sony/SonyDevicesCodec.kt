package dev.hyperears.protocol.sony

/** V2 Table2 peripheral family; never pass Table1 frames here. */
object SonyDevicesCodec {
    private val mac=Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")
    sealed interface Report {val type:Int}
    data class Capability(override val type:Int,val maxPaired:Int,val maxConnected:Int):Report
    data class Status(override val type:Int,val pairing:Boolean,val available:Boolean):Report
    data class Device(val address:String,val order:Int,val name:String,val deviceClass:Int?)
    data class Devices(override val type:Int,val devices:List<Device>,val playbackRight:Int):Report
    data class Toggle(override val type:Int,val enabled:Boolean):Report
    fun discovery(bytes:ByteArray):Set<Int>? {
        val p=bytes.u()
        if(p.size<3 || p[0]!=7 || p[1]!=0 || p.size!=3+p[2]*2)return null
        return p.drop(3).chunked(2).map{it[0]}.filter{it in 0x30..0x34}.toSet()
    }
    fun parse(bytes:ByteArray):Report? {
        val p=bytes.u();if(p.size<2)return null
        val type=p[1]
        if(type in setOf(1,3) && p[0] in setOf(0x37,0x39)) {
            if(p.size !in (if(p[0]==0x39)3..4 else 3..3) || p[2] !in 0..1)return null
            if(p.size==4 && p[3]!=0)return null // failed notification does not confirm a setting
            return Toggle(type,if(type==3)p[2]==1 else p[2]==0)
        }
        if(type !in setOf(0,2))return null
        when(p[0]) {
            0x31 -> return if(p.size==5 && p[3]<=p[2])Capability(type,p[2],p[3])else null
            0x33,0x35 -> return if(p.size==4 && p[2] in 0..1 && p[3] in 0..1)Status(type,p[2]==1,p[3]==0)else null
            0x37,0x39 -> {
                if(p.size<4)return null
                var offset=3;val devices=mutableListOf<Device>()
                repeat(p[2]) {
                    val lengthOffset=offset+if(type==2)21 else 18
                    if(lengthOffset>=p.size)return null
                    val address=bytes.copyOfRange(offset,offset+17).toString(Charsets.US_ASCII)
                    if(!mac.matches(address) || devices.any{it.address.equals(address,true)})return null
                    val order=p[offset+17];val size=p[lengthOffset]
                    if(lengthOffset+1+size>p.size)return null
                    val name=runCatching{bytes.copyOfRange(lengthOffset+1,lengthOffset+1+size).decodeToString(throwOnInvalidSequence=true)}.getOrNull() ?: return null
                    val deviceClass=if(type==2)(p[offset+18] shl 16) or (p[offset+19] shl 8) or p[offset+20]else null
                    if(order!=0 && devices.any{it.order==order})return null
                    devices+=Device(address.uppercase(),order,name,deviceClass)
                    offset=lengthOffset+1+size
                }
                if(offset+1!=p.size || (p[offset]!=0 && devices.none{it.order==p[offset]}))return null
                return Devices(type,devices,p[offset])
            }
            else -> return null
        }
    }
    fun bytes(vararg values:Int)=values.map(Int::toByte).toByteArray()
    private fun ByteArray.u()=map{it.toInt()and 255}
}
