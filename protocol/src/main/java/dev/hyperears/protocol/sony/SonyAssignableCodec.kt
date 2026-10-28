package dev.hyperears.protocol.sony

/** SonyPods 3f701ff8 V1 Table1 preset API; deliberately not V2's extended grammar. */
object SonyAssignableCodec {
    data class Key(val id:Int,val type:Int,val default:Int,val presets:List<Int>)
    data class Capability(val keys:List<Key>,val bytes:List<Int>)
    val presets=setOf(0,16,32,33,34)+ (48..57)+(64..71)+setOf(112,113,114,255)
    private val actions=setOf(0,1,2,3,16,17,33,34)
    private val functions=setOf(0,1,2,16,17,18,32,33,34)+(48..54)
    fun discovery(v2:Boolean,bytes:ByteArray):Boolean? {
        if(v2)return null
        val p=bytes.map{it.toInt()and 255}
        if(p.size<3||p[0]!=7||p[1]!=0||p.size!=3+p[2])return null
        return 0xF6 in p.drop(3)
    }
    fun capability(bytes:ByteArray):Capability? {
        val p=bytes.map{it.toInt()and 255}
        if(p.size<3||p[0]!=0xF1||p[1]!=6||p[2]>6)return null
        var i=3
        val keys=mutableListOf<Key>()
        repeat(p[2]) {
            if(i+4>p.size)return null
            val id=p[i++];val type=p[i++];val default=p[i++];val count=p[i++]
            if(id !in 0..5||type !in 0..2||default !in presets||count>32||keys.any{it.id==id})return null
            val choices=mutableListOf<Int>()
            repeat(count) {
                if(i+2>p.size)return null
                val preset=p[i++];val actionCount=p[i++]
                if(preset !in presets||preset in choices||actionCount>8||i+actionCount*2>p.size)return null
                val seen=mutableSetOf<Int>()
                repeat(actionCount) {
                    val action=p[i++];val function=p[i++]
                    if(action !in actions||function !in functions||!seen.add(action))return null
                }
                if(actionCount>0)choices+=preset
            }
            // Keep the transmitted order; never silently shift positional status/preset slots.
            if(choices.isEmpty())return null
            keys+=Key(id,type,default,choices)
        }
        if(i!=p.size)return null
        return Capability(keys,p)
    }
    fun vector(bytes:ByteArray,status:Boolean):List<Int>? {
        val p=bytes.map{it.toInt()and 255}
        if(p.size<3||p[0] !in (if(status)setOf(0xF3,0xF5)else setOf(0xF7,0xF9))||p[1]!=6||p[2]>6||p.size!=3+p[2])return null
        if(status&&p.drop(3).any{it !in 0..1})return null
        return p.drop(3)
    }
    fun bytes(vararg p:Int)=p.map(Int::toByte).toByteArray()
}
