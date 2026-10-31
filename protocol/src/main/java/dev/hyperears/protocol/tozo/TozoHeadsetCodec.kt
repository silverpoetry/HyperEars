package dev.hyperears.protocol.tozo

/** Protocol-0 headset messages verified against TOZO5.4.3 and NC20 Pro D50D logs. */
object TozoHeadsetCodec {
    enum class Noise(val code:Int) { OFF(0),ANC(1),TRANSPARENCY(2),WIND(3),LEISURE(4),CUSTOM(5),ADAPTIVE(6) }
    sealed interface Report {
        data class Battery(val left:Int,val right:Int?):Report
        data class Firmware(val left:String,val right:String?):Report
        data class Noise(val mode:TozoHeadsetCodec.Noise):Report
        data class Acknowledgement(val command:Int,val status:Int):Report
    }
    fun parse(bytes:ByteArray):Report? {
        val frame=TozoWireCodec.decode(bytes)?:return null
        val p=frame.payload.map {it.toInt() and 255}
        return when(frame.command) {
            0x0001->if(p.size==3 || p.size==6)Report.Firmware(p.take(3).joinToString("."),
                if(p.size==6)p.drop(3).joinToString(".") else null) else null
            0x0002,0x200e->if(p.size in 1..2 && p.all {it in 0..100})Report.Battery(p[0],p.getOrNull(1)) else null
            0x0030,0x2030->if(p.size==1)Noise.entries.firstOrNull {it.code==p[0]}?.let {Report.Noise(it)} else null
            in noiseCommands->if(p.size==1)Report.Acknowledgement(frame.command,p[0]) else null
            else->null
        }
    }
    fun reads()=listOf(0x0001,0x0002,0x0030).map {TozoWireCodec.encode(it,byteArrayOf())}
    fun setNoise(mode:Noise):ByteArray {
        val command=when(mode) {
            Noise.OFF,Noise.ANC->0x1004
            Noise.TRANSPARENCY->0x1005
            Noise.WIND->0x1007
            Noise.LEISURE->0x1008
            Noise.CUSTOM->0x100e
            Noise.ADAPTIVE->0x1011
        }
        return TozoWireCodec.encode(command,byteArrayOf(if(mode==Noise.OFF)0 else 1))
    }
    private val noiseCommands=setOf(0x1004,0x1005,0x1007,0x1008,0x100e,0x1011)
}
