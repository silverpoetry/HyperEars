package dev.hyperears.protocol.samsung
/** GalaxyBudsClient754b6fdf explicit GearFitTest specs and FitTestDecoder. */
object SamsungFitTestCodec {
    fun supported(model:SamsungBudsCodec.Model)=model in setOf(SamsungBudsCodec.Model.BUDS2, SamsungBudsCodec.Model.BUDS2_PRO,
        SamsungBudsCodec.Model.BUDS_FE,SamsungBudsCodec.Model.BUDS_CORE,SamsungBudsCodec.Model.BUDS3,SamsungBudsCodec.Model.BUDS3_PRO,
        SamsungBudsCodec.Model.BUDS3_FE,SamsungBudsCodec.Model.BUDS4,SamsungBudsCodec.Model.BUDS4_PRO)
    fun command(model:SamsungBudsCodec.Model,start:Boolean):ByteArray {
        require(supported(model));return SamsungBudsCodec.packet(157,byteArrayOf(if(start)1 else 0),model)
    }
    fun result(frame:SamsungBudsCodec.Frame):List<Int>? = if(frame.id==158 && frame.payload.size==2)
        frame.payload.map{it.toInt() and 255}.takeIf{it.all{value->value in 0..2}}else null
}
