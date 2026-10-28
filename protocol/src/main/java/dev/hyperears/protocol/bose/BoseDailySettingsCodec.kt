package dev.hyperears.protocol.bose

/** Bosectl 70570b2 / noisecancel 0391b61 interoperability fields; only STATUS is evidence. */
object BoseDailySettingsCodec {
    const val BUTTONS = 9
    const val PROMPTS = 3
    const val CNC = 5
    const val ANR = 6
    const val EQ = 7
    const val MULTIPOINT = 10
    const val SIDETONE = 11
    const val AUTO_ANSWER = 27
    const val AUTO_PAUSE = 24
    data class EqBand(val id: Int, val minimum: Int, val maximum: Int, val current: Int)
    data class Cnc(val rawLevel: Int, val enabled: Boolean)
    data class DeviceName(val flag:Int,val text:String)
    fun validAudioSettings(raw:List<Int>)=raw.size==5 && raw[0] in 0..10 && raw[1] in 0..1 && raw[2] in 0..2 && raw[3] in 0..1 && raw[4] in 0..1
    fun parseAudioSettings(frame:BoseBmapWireCodec.Frame):List<Int>? {
        if(frame.functionBlock!=31||frame.function!=10||frame.operator!=BoseBmapWireCodec.Operator.STATUS)return null
        return frame.payload.map{it.toInt() and 255}.takeIf(::validAudioSettings)
    }
    fun queryAudioSettings()=BoseBmapWireCodec.packet(31,10,BoseBmapWireCodec.Operator.GET)
    fun setAudioSettings(expected:List<Int>,index:Int,value:Int):ByteArray {
        require(validAudioSettings(expected)&&index in setOf(2,3))
        val next=expected.toMutableList().apply{this[index]=value}
        require(validAudioSettings(next))
        return BoseBmapWireCodec.packet(31,10,BoseBmapWireCodec.Operator.SET_GET,next.map(Int::toByte).toByteArray())
    }
    fun encodeDeviceName(name:String):ByteArray? = runCatching {
        if(name.isBlank()||name.any{it.isISOControl()})return null
        val buffer=Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(name))
        if(buffer.remaining() !in 1..31)return null
        ByteArray(buffer.remaining()).also{buffer.get(it)}
    }.getOrNull()
    fun parseDeviceName(frame:BoseBmapWireCodec.Frame):DeviceName? {
        if(!isState(frame,2)||frame.payload.size !in 1..255)return null
        val text=runCatching {Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(frame.payload,1,frame.payload.size-1)).toString()}.getOrNull()?:return null
        return DeviceName(frame.payload[0].toInt() and 255,text)
    }
    fun setDeviceName(name:String)=write(2,requireNotNull(encodeDeviceName(name)))
    data class Button(val id:Int,val event:Int,val current:Int,val mask:List<Int>)
    val buttonActions=(1..17).toSet()+setOf(19,20,21)
    fun parseButton(frame:BoseBmapWireCodec.Frame):Button? {
        if(!isState(frame,BUTTONS)||frame.payload.size !in 3..7)return null
        val p=frame.payload.map{it.toInt() and 255}
        if(p[0] !in setOf(0,2,3,4,16,128)||p[1] !in 1..12)return null
        return Button(p[0],p[1],p[2],p.drop(3))
    }
    fun setButton(id:Int,event:Int,action:Int):ByteArray {
        require(id in setOf(0,2,3,4,16,128)&&event in 1..12&&action in buttonActions)
        return write(BUTTONS,byteArrayOf(id.toByte(),event.toByte(),action.toByte()))
    }
    fun query(function: Int) = BoseBmapWireCodec.packet(1, function, BoseBmapWireCodec.Operator.GET)
    private fun isState(frame: BoseBmapWireCodec.Frame, function: Int) =
        frame.functionBlock == 1 && frame.function == function && frame.operator == BoseBmapWireCodec.Operator.STATUS
    fun parseEq(frame: BoseBmapWireCodec.Frame): List<EqBand>? {
        if (!isState(frame, EQ) || frame.payload.size % 4 != 0 || frame.payload.size > 12) return null
        val bands = frame.payload.asList().chunked(4).map {
            EqBand(it[3].toInt() and 255, it[0].toInt(), it[1].toInt(), it[2].toInt())
        }
        if (bands.any { it.id !in 0..2 || it.minimum > it.maximum || it.current !in it.minimum..it.maximum } ||
            bands.map { it.id }.distinct().size != bands.size) return null
        return bands
    }
    fun parsePrompts(frame: BoseBmapWireCodec.Frame): Int? =
        if (isState(frame, PROMPTS)) frame.payload.singleOrNull()?.toInt()?.and(255) else null
    fun parseMultipoint(frame: BoseBmapWireCodec.Frame): Boolean? =
        if (isState(frame, MULTIPOINT)) frame.payload.singleOrNull()?.let { it.toInt() and 2 != 0 } else null
    fun parseSidetone(frame: BoseBmapWireCodec.Frame): Int? =
        if (isState(frame, SIDETONE) && frame.payload.size == 2)
            frame.payload[1].toInt().takeIf { it in 0..3 } else null
    fun parseAutoAnswer(frame: BoseBmapWireCodec.Frame): Boolean? =
        if (isState(frame, AUTO_ANSWER)) frame.payload.singleOrNull()?.toInt()?.takeIf { it in 0..1 }?.let { it == 1 } else null
    fun setSidetone(level: Int): ByteArray {
        require(level in 0..3)
        return write(SIDETONE, byteArrayOf(1, level.toByte()))
    }
    fun parseAutoPause(frame: BoseBmapWireCodec.Frame): Boolean? =
        if (isState(frame, AUTO_PAUSE)) frame.payload.singleOrNull()?.toInt()?.takeIf { it in 0..1 }?.let { it == 1 } else null
    fun parseCnc(frame: BoseBmapWireCodec.Frame): Cnc? {
        if (!isState(frame, CNC) || frame.payload.size != 3 || frame.payload[0].toInt() != 11 ||
            frame.payload[1].toInt() !in 0..10 || frame.payload[2].toInt() !in 0..1) return null
        return Cnc(frame.payload[1].toInt(), frame.payload[2].toInt() == 1)
    }
    fun parseAnr(frame: BoseBmapWireCodec.Frame): Int? = if (isState(frame, ANR) && frame.payload.size == 2)
        frame.payload[0].toInt().takeIf { it in 0..3 } else null
    fun setEq(id: Int, value: Int): ByteArray {
        require(id in 0..2 && value in -128..127)
        return write(EQ, byteArrayOf(value.toByte(), id.toByte()))
    }
    fun setPrompts(raw: Int, enabled: Boolean): ByteArray {
        require(raw in 0..255)
        // Bit 5 is the owned toggle. Preserve language and opaque upper bits.
        return write(PROMPTS, byteArrayOf(((raw and 0xDF) or if (enabled) 0x20 else 0).toByte()))
    }
    fun setToggle(function: Int, enabled: Boolean): ByteArray {
        require(function in setOf(MULTIPOINT, AUTO_PAUSE, AUTO_ANSWER))
        return write(function, byteArrayOf(if (enabled) 1 else 0))
    }
    private fun write(function: Int, payload: ByteArray) =
        BoseBmapWireCodec.packet(1, function, BoseBmapWireCodec.Operator.SET_GET, payload)
}
