package dev.hyperears.protocol.huawei

/** Full inventory and action grammar from OpenFreebuds 0b4f14c0 config_equalizer.py. */
object HuaweiEqualizerInventoryCodec {
    data class Report(val complete:Boolean,val selected:Int?,val builtIns:List<Int>,
        val slots:List<HuaweiDailySettingsCodec.CustomPreset>,val raw:List<Int>)
    fun report(frame:HuaweiFreebudsSppCodec.Frame):Report? = runCatching {
        require(frame.command==0x2B4A && 127 !in frame.params)
        val fields=frame.params
        fun one(key:Int)=fields[key]?.let { require(it.size==1);it[0].toInt() and 255 }
        val supported=one(1);require(supported==null || supported in 0..1)
        val selected=one(2)
        val bands=one(5);require(bands==null || bands==10)
        fields[6]?.let{require(it.size==10 && it.all{v->v.toInt() in -60..60})}
        val builtIns=fields[3]?.map{it.toInt() and 255}.orEmpty()
        require(builtIns.distinct().size==builtIns.size && builtIns.none{it in 0x64..0x66})
        val bytes=fields[8]
        require(bytes==null || bytes.size<=108 && bytes.size%36==0)
        val slots=bytes?.asList()?.chunked(36)?.map { record ->
            val id=record[0].toInt() and 255
            require(id in 0x64..0x66 && record[1].toInt()==10)
            val gains=record.subList(2,12).map(Byte::toInt);require(gains.all{it in -60..60})
            val padded=record.subList(12,36)
            val nameBytes=padded.takeWhile{it!=0.toByte()}
            val name=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(nameBytes.toByteArray())).toString()
            require(name.isNotBlank())
            HuaweiDailySettingsCodec.CustomPreset(id,name,gains)
        }.orEmpty()
        require(slots.map{it.id}.distinct().size==slots.size)
        require(fields.keys.any{it in 1..8})
        val complete=supported!=0 && selected!=null && 3 in fields && bytes!=null &&
            (selected in builtIns || slots.any{it.id==selected})
        Report(complete,selected,builtIns,slots,fields.entries.flatMap{(key,value)->listOf(key,value.size)+value.map{it.toInt() and 255}})
    }.getOrNull()
    fun write(id:Int,name:String,gains:List<Int>,delete:Boolean):ByteArray {
        require(id in 0x64..0x66 && validName(name) && gains.size==10 && gains.all{it in -60..60})
        return HuaweiFreebudsSppCodec.packet(0x2B49,listOf(1 to byteArrayOf(id.toByte()),2 to byteArrayOf(10),
            3 to gains.map(Int::toByte).toByteArray(),4 to name.toByteArray(Charsets.UTF_8),5 to byteArrayOf(if(delete)2 else 1)))
    }
    fun validName(name:String)=name.isNotBlank() && '\u0000' !in name && name.toByteArray(Charsets.UTF_8).size<=24 &&
        runCatching { Charsets.UTF_8.newEncoder().canEncode(name) }.getOrDefault(false)
}
