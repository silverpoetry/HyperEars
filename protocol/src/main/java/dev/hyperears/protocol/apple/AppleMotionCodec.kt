/* SPDX-License-Identifier: GPL-3.0-or-later
 * RTBuddy wire layout researched by FIocker/aap-head-tracker and Untrosss/HeadTracker.
 * Bounded protobuf reader, quaternion math and calibration rewritten for HyperPods-X.
 */
package dev.hyperears.protocol.apple

import kotlin.math.*

data class Quaternion(val x:Double,val y:Double,val z:Double,val w:Double) {
    fun normalized():Quaternion {
        val n=sqrt(x*x+y*y+z*z+w*w)
        require(n.isFinite()&&n>1e-8)
        return Quaternion(x/n,y/n,z/n,w/n)
    }
    fun inverse()=Quaternion(-x,-y,-z,w)
    operator fun times(b:Quaternion)=Quaternion(w*b.x+x*b.w+y*b.z-z*b.y,
        w*b.y-x*b.z+y*b.w+z*b.x,w*b.z+x*b.y-y*b.x+z*b.w,w*b.w-x*b.x-y*b.y-z*b.z)
    fun rotationVector():List<Double> {
        val q=normalized().let{if(it.w<0)Quaternion(-it.x,-it.y,-it.z,-it.w)else it}
        val n=sqrt(q.x*q.x+q.y*q.y+q.z*q.z)
        if(n<1e-8)return listOf(0.0,0.0,0.0)
        val a=2*atan2(n,q.w)/n
        return listOf(q.x*a,q.y*a,q.z*a)
    }
}

object AppleMotionCodec {
    /** SensorDataWX field 4 requests the firmware's actual service descriptors. */
    fun descriptorRequest(sequence:Int=256):ByteArray = envelope(byteArrayOf(8)+varint(sequence)+byteArrayOf(0x22,0))
    private fun varint(value:Int):ByteArray {
        require(value>=0)
        var next=value;val bytes=mutableListOf<Byte>()
        do{val low=next and 127;next=next ushr 7;bytes+=(low or if(next>0)128 else 0).toByte()}while(next>0)
        return bytes.toByteArray()
    }
    private fun envelope(body:ByteArray):ByteArray = byteArrayOf(4, 0, 4, 0)+byteArrayOf(0x17,0,0,0,0x10,0,body.size.toByte(),(body.size ushr 8).toByte())+body
    fun subscription(service:Int,intervalMicros:Int,sequence:Int):ByteArray {
        require(service in 1..255&&intervalMicros in listOf(0,20000,40000))
        val configuration=byteArrayOf(1)+ByteArray(4){(intervalMicros ushr (8*it)).toByte()}
        val setting=byteArrayOf(8)+varint(service)+byteArrayOf(16,2,0x1a,5)+configuration
        return envelope(byteArrayOf(8)+varint(sequence)+byteArrayOf(0x42)+varint(setting.size)+setting)
    }
    data class SensorService(val id:Int,val name:String,val maxReportSize:Int,val protectedAccess:Boolean)
    fun services(p:ByteArray):List<SensorService> {
        val top=wxFields(p)?:return emptyList()
        val descriptors = top.filter { it.id == 5 }
        val services = descriptors.mapNotNull{field->
            val descriptor=fields(field.bytes?:return@mapNotNull null)?:return@mapNotNull null
            val id=descriptor.firstOrNull{it.id==1}?.number?:return@mapNotNull null
            if(id !in 1..255)return@mapNotNull null
            val metadata=AppleMotionIoSerial.dictionary(descriptor.firstOrNull{it.id==2}?.bytes?:return@mapNotNull null)?:return@mapNotNull null
            val name=metadata["AccessoryService"] as? String?:return@mapNotNull null
            if(!name.matches(Regex("[A-Za-z0-9_-]{1,48}")))return@mapNotNull null
            val size=(metadata["MaxReportSize"] as? Long)?.takeIf{it in 1..4096}?.toInt()?:0
            SensorService(id.toInt(),name,size,metadata["ProtectedAccess"]==true)
        }
        return services.takeIf { it.size == descriptors.size && it.map { service -> service.id }.distinct().size == it.size } ?: emptyList()
    }
    fun rtBuddySetup():List<ByteArray> = listOf(
        byteArrayOf(0,0,0,0,1,0,3,0,0,0,0,0,0,0,0,0),byteArrayOf(4,0,0,0,1,0,0),
        byteArrayOf(0,0,4,0,1,0,3,0,0,0,0,0,0,0,0,0),byteArrayOf(4,0,4,0,1,0,0)
    )
    /** Total frame length, excluding no bytes. Null means not enough header bytes. */
    fun frameLength(p:ByteArray):Int? {
        if(p.size<12)return null
        if (!p.copyOfRange(0,6).contentEquals(byteArrayOf(4,0,4,0,0x17,0))) return -1
        val descriptor=p.motionUnsigned(6) or (p.motionUnsigned(7) shl 8) or (p.motionUnsigned(8) shl 16) or (p.motionUnsigned(9) shl 24)
        // RTBuddy carries discovery/control descriptors as well as sensor data.
        // Their body is still length-delimited; rejecting one used to discard the
        // rest of the read, including any later motion frame in the same batch.
        if(descriptor==0||descriptor and (descriptor-1)!=0)return -1
        val length=p.motionUnsigned(10) or (p.motionUnsigned(11) shl 8)
        return if(length in 1..4000)12+length else -1
    }
    private data class Field(val id:Int,val number:Long?=null,val bytes:ByteArray?=null)
    private fun fields(p:ByteArray):List<Field>? {
        var pos=0
        fun varint():Long? {
            var result=0L
            for(i in 0..9){
                if(pos>=p.size)return null
                val b=p.motionUnsigned(pos++)
                if(i==9&&b>1)return null
                result=result or ((b and 127).toLong() shl (7*i))
                if(b<128)return result
            }
            return null
        }
        val result=mutableListOf<Field>()
        while(pos<p.size){
            if(result.size>=128)return null
            val key=varint()?:return null
            if(key<=0||key>Int.MAX_VALUE)return null
            val id=(key ushr 3).toInt()
            if(id==0)return null
            when((key and 7).toInt()){
                0->result+=Field(id,number=varint()?:return null)
                2->{val n=varint()?:return null;if(n<0||n>p.size-pos)return null
                    result+=Field(id,bytes=p.copyOfRange(pos,pos+n.toInt()));pos+=n.toInt()}
                1,5->{val n=if((key and 7L)==1L)8 else 4;if(pos+n>p.size)return null;pos+=n}
                else->return null
            }
        }
        return result
    }
    private fun wxFields(p:ByteArray):List<Field>? {
        if(p.size<12||!p.copyOfRange(0,4).contentEquals(byteArrayOf(4, 0, 4, 0))||p.motionUnsigned(4)!=0x17||p.motionUnsigned(5)!=0||frameLength(p)!=p.size||p.motionUnsigned(6)!=0||p.motionUnsigned(7)!=0||p.motionUnsigned(8)!=16||p.motionUnsigned(9)!=0)return null
        val body=p.copyOfRange(12,p.size)
        fields(body)?.let{return it}
        // WX log types with bit 4 carry a two-byte trailer outside the protobuf.
        if(body.size<3)return null
        val trimmed=fields(body.copyOf(body.size-2))?:return null
        return trimmed.takeIf{(it.firstOrNull{field->field.id==2}?.number?:0) and 4L !=0L}
    }
    fun decode(p:ByteArray,expectedService:Int?=null):MotionPose? {
        val top=wxFields(p)?:return null
        for(f in top){
            if(f.id !in listOf(3,7)||f.bytes==null)continue
            val nested=fields(f.bytes)?:continue
            val service=if(f.id==7)nested.firstOrNull{it.id==1}?.number?.toInt()?:continue else expectedService ?: continue
            if(expectedService == null || expectedService !in 1..255 || service != expectedService) continue
            for(blob in nested.filter{it.id==3}.mapNotNull{it.bytes}){
                if(blob.size!=58||blob.motionUnsigned(0)!=1||blob.motionUnsigned(9)!=3)continue
                fun signed(i:Int)=((blob.motionUnsigned(i) or (blob.motionUnsigned(i+1) shl 8)).toShort().toInt())/32768.0
                val x=signed(20);val y=signed(22);val z=signed(24)
                val n=x*x+y*y+z*z
                if(n !in 0.0..1.01)continue
                return MotionPose(Quaternion(x,y,z,sqrt(max(0.0,1-n))).normalized(),service)
            }
        }
        return null
    }
    fun acknowledgement(p:ByteArray):MotionAcknowledgement? {
        val top=wxFields(p)?:return null
        for(f in top.filter{it.id==9}){
            val nested=fields(f.bytes?:continue)?:continue
            val service=nested.firstOrNull{it.id==1}?.number?:continue
            val error=nested.firstOrNull{it.id==2}?.number?:0
            if(service in 0..255)return MotionAcknowledgement(service.toInt(),error)
        }
        return null
    }
}

/** Learns three sensor axes from deliberate right / up / right-tilt head movements. */
class MotionCalibration {
    private var origin:Quaternion?=null
    private var peak=listOf(0.0,0.0,0.0)
    private val axes=mutableListOf<List<Double>>()
    val progress get()=axes.size
    fun begin(q:Quaternion){origin=q.normalized();peak=listOf(0.0,0.0,0.0)}
    fun sample(q:Quaternion){
        val start=origin?:return
        val v=(start.inverse()*q).rotationVector()
        if(norm(v)>norm(peak)&&norm(v)<1.4)peak=v
    }
    fun finish():Boolean {
        origin=null
        val n=norm(peak)
        if(n !in 0.18..1.4||axes.size>=3)return false
        val axis=peak.map{it/n}
        if(axes.any{abs(dot(it,axis))>0.35})return false
        axes+=axis
        return true
    }
    fun matrix():List<Double>? {
        if(axes.size!=3)return null
        // Columns of B are measured yaw-right, pitch-up, roll-right axes.
        val a=axes[0];val b=axes[1];val c=axes[2]
        val det=dot(a,cross(b,c))
        if(abs(det)<0.8)return null
        // Orthonormalize measurement noise; a frame change must not amplify angles.
        val bp=b.mapIndexed{i,x->x-dot(a,b)*a[i]}.let{v->v.map{it/norm(v)}}
        val cp=c.mapIndexed{i,x->x-dot(a,c)*a[i]-dot(bp,c)*bp[i]}.let{v->v.map{it/norm(v)}}
        // HID reference -> head: pitch-up=-X, roll-right=-Y, yaw-right=+Z.
        return bp.map{-it}+cp.map{-it}+a
    }
    companion object {
        fun apply(matrix:List<Double>,vector:List<Double>):List<Double> {
            require(matrix.size==9&&matrix.all{it.isFinite()}&&vector.size==3&&vector.all{it.isFinite()})
            return (0..2).map{r->(0..2).sumOf{c->matrix[r*3+c]*vector[c]}}
        }
        fun norm(v:List<Double>)=sqrt(dot(v,v))
        private fun dot(a:List<Double>,b:List<Double>)=a.indices.sumOf{a[it]*b[it]}
        private fun cross(a:List<Double>,b:List<Double>)=listOf(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
    }
}

private fun ByteArray.motionUnsigned(index: Int) = this[index].toInt() and 255
data class MotionPose(val quaternion: Quaternion, val service: Int)
data class MotionAcknowledgement(val service: Int, val error: Long)
