package dev.hyperears.protocol.oppo
/** Leaf CalibrationProtocol929d813f: fit is separate from Golden Sound tone/calibration writes. */
object OppoFitCodec {
    val queryWear=OppoWireCodec.packet(0x0109)
    val start=OppoWireCodec.packet(0x0405,payload=byteArrayOf(1))
    val stop=OppoWireCodec.packet(0x0405,payload=byteArrayOf(0))
    private fun event(frame:OppoWireCodec.Frame,id:Int):List<Int>? {
        val p=frame.payload.map{it.toInt()and 255}
        val offset=when(frame.command){0x0204->0;0x8202->if(p.firstOrNull()==0)1 else return null;else->return null}
        return if(p.getOrNull(offset)==id)p.drop(offset+1)else null
    }
    fun result(frame:OppoWireCodec.Frame):Pair<Int,Int>? {
        val p=event(frame,4)?:return null
        return if(p.size==4 && p[0]==1 && p[2]==2 && p[1] in setOf(0,1,6) && p[3] in setOf(0,1,6))p[1] to p[3]else null
    }
    fun wear(frame:OppoWireCodec.Frame):Pair<Boolean,Boolean>? {
        val p=if(frame.command==0x8109){val b=frame.payload.map{it.toInt()and 255};if(b.firstOrNull()!=0)return null;b.drop(1)}else event(frame,2)?:return null
        if(p.size!=5 || p[0]!=2)return null
        val pairs=p.drop(1).chunked(2)
        if(pairs.map{it[0]}.toSet()!=setOf(1,2))return null
        return ((pairs.single{it[0]==1}[1] and 2)!=0) to ((pairs.single{it[0]==2}[1] and 2)!=0)
    }
    fun error(frame:OppoWireCodec.Frame):Int?=if(frame.command==0x8405)frame.payload.singleOrNull()?.toInt()?.and(255)?.takeIf{it!=0}else null
}
