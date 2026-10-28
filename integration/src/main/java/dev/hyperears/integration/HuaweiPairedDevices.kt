package dev.hyperears.integration
import kotlinx.serialization.Serializable
import dev.hyperears.protocol.huawei.HuaweiPairedDevicesCodec as Wire
import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec
@Serializable enum class HuaweiPairedAction(val wire:Int){PREFER(0),CONNECT(1),DISCONNECT(2),FORGET(3),ENABLE_AUTO(4),DISABLE_AUTO(5)}
@Serializable data class HuaweiPairedDevice(val mac:String,val name:String,val connection:Int,val preferred:Boolean,val autoConnect:Boolean?,val raw:List<Int>){val connected get()=connection>0;val playing get()=connection==9}
@Serializable data class HuaweiPairedSnapshot(val revision:Long,val devices:List<HuaweiPairedDevice>) {
    fun supports(target:String,action:HuaweiPairedAction):Boolean {
        val row=devices.singleOrNull{it.mac==target}?:return false
        return when(action){HuaweiPairedAction.PREFER->!row.preferred;HuaweiPairedAction.CONNECT->!row.connected;HuaweiPairedAction.DISCONNECT->row.connected;HuaweiPairedAction.FORGET->true;HuaweiPairedAction.ENABLE_AUTO->row.autoConnect==false;HuaweiPairedAction.DISABLE_AUTO->row.autoConnect==true}
    }
}
internal class HuaweiPairedCollector(private val auto:Boolean) {
    private val rows=linkedMapOf<Int,Wire.Row>()
    private var count:Int?=null
    private var invalid=false
    private var revision=0L
    var complete=false;private set
    fun reset(){rows.clear();count=null;invalid=false;complete=false;revision++}
    fun offer(frame:HuaweiFreebudsSppCodec.Frame):Pair<Boolean,HuaweiPairedSnapshot?>? {
        val row=Wire.parse(frame,auto)?:return null
        if(complete)reset()
        if(invalid)return true to null
        if(count!=null && count!=row.count || rows.any{(index,value)->index!=row.index && value.mac==row.mac} || rows[row.index]?.let{it!=row}==true){invalid=true;rows.clear();return true to null}
        count=row.count;rows[row.index]=row
        if(rows.size!=row.count)return true to null
        complete=true
        return true to HuaweiPairedSnapshot(++revision,(0 until row.count).map{index->rows.getValue(index).let{HuaweiPairedDevice(it.mac,it.name,it.connection,it.preferred,it.auto,it.raw)}})
    }
}
