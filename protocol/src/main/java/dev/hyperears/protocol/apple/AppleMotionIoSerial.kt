/* SPDX-License-Identifier: GPL-3.0-or-later
 * Bounded reader for the OS serialization wire format documented by Apple's XNU.
 * Only sensor descriptor metadata is exposed by MotionCodec; device identifiers are discarded.
 */
package dev.hyperears.protocol.apple

internal object AppleMotionIoSerial {
    fun dictionary(bytes:ByteArray):Map<*,*>? = runCatching {
        require(bytes.size in 8..16384 && bytes.take(4)==listOf<Byte>(0xd3.toByte(),0,0,0))
        Reader(bytes).readDictionary()
    }.getOrNull()

    private class Reader(private val bytes:ByteArray) {
        fun readDictionary(): Map<*,*>? { val result = value(0) as? Map<*,*>; require(offset == bytes.size); return result }
        private var offset=4
        private val objects=mutableListOf<Any?>()
        private fun word():Long {
            require(offset+4<=bytes.size)
            var value=0L
            repeat(4){value=value or ((bytes[offset++].toLong() and 255) shl (8*it))}
            return value
        }
        fun value(depth:Int):Any? {
            require(depth<=8&&objects.size<256)
            val header=word();val kind=((header ushr 24) and 127).toInt();val count=(header and 0xffffff).toInt()
            if(kind==12){require(count in objects.indices&&objects[count]!=null);return objects[count]}
            val index=objects.size;objects+=null
            val result:Any?=when(kind){
                1->{
                    require(count<=64)
                    val map=linkedMapOf<String,Any?>()
                    repeat(count){val key=value(depth+1) as? String?:error("dictionary key");require(key !in map);map[key]=value(depth+1)}
                    map
                }
                2,3->{require(count<=128);List(count){value(depth+1)}}
                4->{require(count in listOf(8,16,32,64));val low=word();val high=word();low or (high shl 32)}
                8,9,10->{
                    require(count<=16384&&offset+count<=bytes.size)
                    val raw=bytes.copyOfRange(offset,offset+count);offset+=(count+3) and -4
                    require(offset<=bytes.size)
                    if(kind==10)raw else raw.toString(Charsets.UTF_8).trimEnd('\u0000')
                }
                11->{require(count in 0..1);count==1}
                else->error("unsupported type")
            }
            objects[index]=result
            return result
        }
    }
}
