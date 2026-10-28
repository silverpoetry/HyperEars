package dev.hyperears.protocol.rose

/** Separate F8 and RS Common frame boundaries; reports never accept outgoing markers/operators. */
object RoseLegacyWireCodec {
    data class Field(val type:Int,val data:List<Int>)
    fun rsQuery(sequence:Int)=RoseBudsFeelMk2WireCodec.command(sequence,15,
        byteArrayOf(0xfa.toByte(),1,7,8,9,12,13,14,18,42,43,44,45,46,51))
    fun rsWrite(sequence:Int,type:Int,value:Int)=RoseBudsFeelMk2WireCodec.command(sequence,2,byteArrayOf(type.toByte(),value.toByte()))
    fun f8Query(type:Int)=byteArrayOf(0,0x27,1,0,1,type.toByte())
    fun f8Write(command:Int,value:Int,eq:Boolean=false):ByteArray =
        byteArrayOf(0,command.toByte(),1,0,if(eq) 2 else 1)+(if(eq) byteArrayOf(0,value.toByte()) else byteArrayOf(value.toByte()))
    class Decoder(private val f8:Boolean) {
        private var pending=byteArrayOf()
        fun reset(){pending=byteArrayOf()}
        fun offer(bytes:ByteArray):List<Field> {
            if(bytes.size>256) return bytes.asList().chunked(256).flatMap { offer(it.toByteArray()) }
            pending+=bytes
            val result=mutableListOf<Field>()
            while(pending.size>=5) {
                if(pending[0].u() != if(f8) 0 else 0xdd) {pending=pending.drop(1).toByteArray();continue}
                val length=if(f8) pending[4].u() else pending[2].u()
                val size=length+5
                if(pending.size<size) break
                val frame=pending.take(size).toByteArray();pending=pending.drop(size).toByteArray()
                if(f8) {
                    if(frame[1].u() !in setOf(0x27,0x28)||frame[2].u()!=2||frame[3].u()!=0||length<3) continue
                    val width=frame[6].u()
                    if(length!=width+2) continue
                    result+=Field(frame[5].u(),frame.drop(7).map { it.u() })
                } else {
                    if(frame.last().u()!=0xaa || (frame.dropLast(2).sumOf { it.u() } and 255) != frame[frame.size-2].u()) continue
                    val payload=frame.sliceArray(3 until frame.size-2)
                    if(payload.size==2 || (payload.size==4 && payload[0].u()==12)) {
                        result+=Field(payload[0].u(),payload.drop(1).map { it.u() });continue
                    }
                    // RS Common dispatches the direct type byte; no aggregate LTV dialect is proven.
                }
            }
            return result
        }
    }
    private fun Byte.u()=toInt() and 255
}
