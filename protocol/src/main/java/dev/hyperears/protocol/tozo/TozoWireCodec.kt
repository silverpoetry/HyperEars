package dev.hyperears.protocol.tozo

/** TOZO protocol-0 command frame: big-endian command, u8 length, payload, payload sum modulo256. */
object TozoWireCodec {
    data class Frame(val command:Int,val payload:ByteArray)
    fun encode(command:Int,payload:ByteArray):ByteArray {
        require(command in 0..65535)
        require(payload.size<=255)
        return ByteArray(payload.size+4).also {
            it[0]=(command ushr 8).toByte()
            it[1]=command.toByte()
            it[2]=payload.size.toByte()
            payload.copyInto(it,3)
            it[it.lastIndex]=checksum(payload).toByte()
        }
    }
    /** One complete GATT value. Delimiter-free fragments must be reassembled before this boundary. */
    fun decode(bytes:ByteArray):Frame? {
        if(bytes.size<4)return null
        val size=bytes[2].toInt() and 255
        if(bytes.size!=size+4)return null
        val payload=bytes.copyOfRange(3,3+size)
        if(checksum(payload)!=(bytes.last().toInt() and 255))return null
        return Frame(((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255),payload)
    }
    private fun checksum(bytes:ByteArray)=bytes.sumOf {it.toInt() and 255} and 255
}
