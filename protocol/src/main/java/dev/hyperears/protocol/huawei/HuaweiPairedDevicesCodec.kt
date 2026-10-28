package dev.hyperears.protocol.huawei
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
object HuaweiPairedDevicesCodec {
    const val ENUMERATE=0x2b31
    const val CHANGE=0x2b36
    data class Row(val count:Int,val index:Int,val mac:String,val connection:Int,val preferred:Boolean,val auto:Boolean?,val name:String,val raw:List<Int>)
    fun query()=HuaweiFreebudsSppCodec.packet(ENUMERATE,listOf(1 to byteArrayOf()))
    fun change(frame:HuaweiFreebudsSppCodec.Frame)=frame.command==CHANGE && frame.params[127]==null
    fun parse(frame:HuaweiFreebudsSppCodec.Frame,auto:Boolean):Row? {
        if(frame.command!=ENUMERATE || frame.params[127]!=null || frame.bytes.size>2048)return null
        fun integer(id:Int):Int? {val b=frame.params[id]?:return null;if(b.size !in 1..4)return null;var v=b[0].toInt();for(i in 1 until b.size)v=(v shl 8) or (b[i].toInt()and 255);return v}
        fun byte(id:Int)=frame.params[id]?.singleOrNull()?.toInt()?.and(255)
        val count=integer(2)?.takeIf{it in 1..32}?:return null
        val index=integer(3)?.takeIf{it in 0 until count}?:return null
        val mac=frame.params[4]?.takeIf{it.size==6}?.joinToString(""){(it.toInt()and 255).toString(16).padStart(2,'0')}?:return null
        val connection=byte(5)?:return null
        val preferred=byte(7)?.takeIf{it in 0..1}?:return null
        val automatic=if(auto)(byte(8)?.takeIf{it in 0..1}?:return null) else null
        val nameBytes=frame.params[9]?.takeIf{it.size<=255}?:return null
        val name=runCatching{Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(nameBytes)).toString()}.getOrNull()?:return null
        return Row(count,index,mac,connection,preferred==1,automatic?.let{it==1},name,frame.bytes.map{it.toInt()and 255})
    }
    fun write(mac:String,action:Int):ByteArray {
        require(mac.matches(Regex("[0-9a-f]{12}")) && action in 0..5)
        return HuaweiFreebudsSppCodec.packet(if(action==0)0x2b32 else 0x2b33,listOf((if(action==0)1 else action) to mac.chunked(2).map{it.toInt(16).toByte()}.toByteArray()))
    }
}
