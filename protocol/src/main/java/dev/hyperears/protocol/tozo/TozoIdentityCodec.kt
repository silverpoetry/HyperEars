package dev.hyperears.protocol.tozo

import java.util.Locale

/** TOZO 5.4.3 BLE identity format. Product facts only; no connection or capability inference. */
object TozoIdentityCodec {
    enum class Nc20ProPart { EARBUDS, CASE }
    data class Identity(val classicAddress:String,val product:Int,val batch:Int?) {
        val mark:String get()=hex(product)+(batch?.takeIf {it!=0}?.let(::hex) ?: "")
        val nc20ProPart:Nc20ProPart? get()=when(mark) {
            "D50D","140E","210E"->Nc20ProPart.EARBUDS
            "D509","1409","2109"->Nc20ProPart.CASE
            else->null
        }
    }
    fun parseAdvertisement(bytes:ByteArray):Identity? {
        var offset=0
        var found:Identity?=null
        while(offset<bytes.size) {
            val length=bytes[offset].toInt() and 255
            if(length==0)break
            val end=offset+length+1
            if(end>bytes.size)return null
            if((bytes[offset+1].toInt() and 255)==255) {
                val value=parsePayload(bytes.copyOfRange(offset+2,end))
                if(value!=null) {
                    if(found!=null && found!=value)return null
                    found=value
                }
            }
            offset=end
        }
        return found
    }
    /** Android removes the first two manufacturer bytes as a little-endian company identifier. */
    fun parseManufacturer(companyId:Int,body:ByteArray):Identity? {
        if(companyId !in 0..65535)return null
        return parsePayload(byteArrayOf(companyId.toByte(),(companyId ushr 8).toByte())+body)
    }
    private fun parsePayload(value:ByteArray):Identity? {
        if(value.size !in 9..11)return null
        val wr=value[7]==0x57.toByte() && value[8]==0x52.toByte()
        val woer=value.size==11 && value[7]==0x57.toByte() && value[8]==0x4f.toByte() &&
            value[9]==0x45.toByte() && value[10]==0x52.toByte()
        val batch=when {
            wr && value.size==9->null
            wr && value.size==10->value[9].toInt() and 255
            woer->null
            else->return null
        }
        val address=value.take(6).joinToString(":") {hex(it.toInt() and 255)}
        return Identity(address,value[6].toInt() and 255,batch)
    }
    private fun hex(value:Int)=String.format(Locale.ROOT,"%02X",value)
}
