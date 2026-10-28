package dev.hyperears.protocol.sony

/** V2 Table1 only: fixed and selectable action blocks are distinct from V1. */
object SonyAssignableV2Codec {
    data class Action(val id:Int,val default:Int,val functions:List<Int>)
    data class Key(val id:Int,val type:Int,val presets:List<Int>,val actions:Map<Int,List<Action>>)
    data class Capability(val bytes:List<Int>,val keys:List<Key>)
    data class Mapping(val preset:Int,val actions:List<Pair<Int,Int>>)
    private val actions=setOf(0,1,2,3,16,17,33,34)
    private val functions=(0..4)+listOf(16,17)+(32..36)+(48..59)+(64..72)+(80..89)+(112..114)
    fun discovery(bytes:ByteArray):Set<Int>? {
        val p=bytes.u()
        if(p.size<3||p[0]!=7||p[1]!=0||p.size!=3+p[2]*2)return null
        return p.drop(3).chunked(2).mapNotNull{when(it[0]){0xF3->3;0xFE->14;else->null}}.toSet()
    }
    fun capability(bytes:ByteArray,type:Int):Capability? {
        val p=bytes.u();val countIndex=if(type==14)3 else 2
        if(type !in setOf(3,14)||p.size<=countIndex||p[0]!=0xF1||p[1]!=type||p[countIndex]>6)return null
        var i=countIndex+1;val keys=mutableListOf<Key>()
        repeat(p[countIndex]) {
            if(i+4>p.size)return null
            val id=p[i++];val kind=p[i++];val default=p[i++];val count=p[i++]
            if(id !in 0..5||keys.any{it.id==id}||kind !in 0..2||default !in SonyAssignableCodec.presets||count !in 1..32)return null
            val blocks=linkedMapOf<Int,List<Action>>()
            repeat(count) {
                if(i+3>p.size)return null
                val preset=p[i++];val fixed=p[i++];val selectable=p[i++]
                if(preset !in SonyAssignableCodec.presets||preset in blocks||fixed+selectable>8)return null
                val list=mutableListOf<Action>()
                repeat(fixed) {
                    if(i+2>p.size)return null
                    val action=p[i++];val function=p[i++]
                    if(action !in actions||list.any{it.id==action}||function !in functions)return null
                    list+=Action(action,function,listOf(function))
                }
                repeat(selectable) {
                    if(i+3>p.size)return null
                    val action=p[i++];val function=p[i++];val choices=p[i++]
                    if(action !in actions||list.any{it.id==action}||function !in functions||choices !in 1..64||i+choices>p.size)return null
                    val values=p.subList(i,i+choices);i+=choices
                    if(values.any{it !in functions}||values.distinct().size!=values.size||function !in values)return null
                    list+=Action(action,function,values.toList())
                }
                blocks[preset]=list
            }
            keys+=Key(id,kind,blocks.keys.toList(),blocks)
        }
        return if(i==p.size)Capability(p,keys)else null
    }
    fun vector(bytes:ByteArray,type:Int,status:Boolean):List<Int>? {
        val p=bytes.u()
        if(p.size<3||p[0] !in (if(status)setOf(0xF3,0xF5)else setOf(0xF7,0xF9))||p[1]!=type||p[2]>6||p.size!=3+p[2])return null
        if(status&&p.drop(3).any{it !in 0..1})return null
        return p.drop(3)
    }
    fun mappings(bytes:ByteArray,type:Int):List<Mapping>? {
        val p=bytes.u()
        if(p.size<3||p[0] !in setOf(0xFB,0xFD)||p[1]!=type||p[2]>6)return null
        var i=3;val result=mutableListOf<Mapping>()
        repeat(p[2]) {
            if(i+2>p.size)return null
            val preset=p[i++];val count=p[i++]
            if(preset !in SonyAssignableCodec.presets||count>8||i+count*2>p.size)return null
            val values=mutableListOf<Pair<Int,Int>>()
            repeat(count) {val action=p[i++];val function=p[i++]
                if(action !in actions||function !in functions||values.any{it.first==action})return null
                values+=action to function
            }
            result+=Mapping(preset,values)
        }
        return if(i==p.size)result else null
    }
    private fun ByteArray.u()=map{it.toInt() and 255}
}
