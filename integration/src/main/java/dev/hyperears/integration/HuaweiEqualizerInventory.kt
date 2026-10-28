package dev.hyperears.integration
import kotlinx.serialization.Serializable
import dev.hyperears.protocol.huawei.HuaweiEqualizerInventoryCodec as Wire

@Serializable enum class HuaweiEqAction { CREATE, SELECT, UPDATE, DELETE }
@Serializable data class HuaweiEqSnapshot(val modelId:String,val revision:Long,val raw:List<Int>,
    val selected:Int,val builtIns:List<Int>,val slots:List<HuaweiCustomEqualizer>) {
    val nextId get()=(0x64..0x66).firstOrNull{id->slots.none{it.id==id}}
    fun supports(request:HuaweiDailyControlRequest.ManageEqualizer):Boolean {
        if(request.expected!=this)return false
        val slot=slots.singleOrNull{it.id==request.id}
        val curve=request.gains.size==10 && request.gains.all{it in -60..60}
        return when(request.action) {
            HuaweiEqAction.CREATE -> request.id==nextId && curve && Wire.validName(request.name) && slots.none{it.name==request.name}
            HuaweiEqAction.SELECT -> request.id in builtIns || slot!=null
            HuaweiEqAction.UPDATE -> slot!=null && request.id==selected && curve && request.name.isEmpty()
            HuaweiEqAction.DELETE -> slot!=null && request.id==selected && request.name.isEmpty() && request.gains.isEmpty()
        }
    }
}
internal object HuaweiEqRevisions {
    private val sequence=java.util.concurrent.atomic.AtomicLong()
    fun next()=sequence.incrementAndGet()
}
internal fun HuaweiDailyControlRequest.ManageEqualizer.packet():ByteArray {
    if(action==HuaweiEqAction.SELECT && id in expected.builtIns)
        return dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec.packet(0x2B49,listOf(1 to byteArrayOf(id.toByte())))
    val slot=expected.slots.singleOrNull{it.id==id}
    return Wire.write(id,if(action==HuaweiEqAction.CREATE)name else requireNotNull(slot).name,
        if(action==HuaweiEqAction.CREATE || action==HuaweiEqAction.UPDATE)gains else requireNotNull(slot).gains,
        action==HuaweiEqAction.DELETE)
}
