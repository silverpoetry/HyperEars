package dev.hyperears.runtime

import android.os.Handler
import android.os.Looper
import dev.hyperears.protocol.sony.SonyGattSetup
import java.io.Closeable

/** Callback owner for the opt-in Tandem GATT preparation. One bounded timer, no polling. */
internal class SonyGattPreparationBridge(
    private val subscribeProbe:()->Boolean,
    private val readLength:()->Boolean,
    private val subscribeData:()->Unit,
    private val ready:()->Unit,
    private val failed:()->Unit,
    private val handler:Handler=Handler(Looper.getMainLooper()),
):Closeable {
    private val setup=SonyGattSetup()
    private var closed=false
    private var timer:Runnable?=null
    private var generation=0L
    private var active:SonyGattSetup.Action?=null
    @Synchronized fun services(hasProbe:Boolean)=dispatch(setup.services(hasProbe))
    @Synchronized fun mtu(value:Int)=dispatch(setup.mtuSettled(value))
    @Synchronized fun probeSubscribed(success:Boolean)=dispatch(setup.probeSubscribed(success))
    @Synchronized fun probeNotification(value:ByteArray)=dispatch(setup.probeNotification(value))
    @Synchronized fun length(value:ByteArray,success:Boolean) {
        if(active!=SonyGattSetup.Action.READ_LENGTH || closed)return
        if(!success)abort() else dispatch(setup.length(value))
    }
    @Synchronized fun dataSubscribed(success:Boolean)=dispatch(setup.dataSubscribed(success))
    @Synchronized fun fragments(frame:ByteArray)=setup.fragments(frame)
    private fun dispatch(action:SonyGattSetup.Action?) {
        if(closed || action==null)return
        active=action
        timer?.let(handler::removeCallbacks);timer=null
        val stamp=++generation
        val delay=when(action) {
            SonyGattSetup.Action.PROBE,SonyGattSetup.Action.READ_LENGTH,SonyGattSetup.Action.SUBSCRIBE_DATA->5_000L
            SonyGattSetup.Action.WAIT_PROBE->500L
            else->0L
        }
        if(delay>0) {
            timer=Runnable {synchronized(this) {if(!closed && generation==stamp)dispatch(setup.timeout(action))}}
                .also {handler.postDelayed(it,delay)}
        }
        when(action) {
            SonyGattSetup.Action.PROBE->if(!subscribeProbe())dispatch(setup.probeSubscribed(false))
            SonyGattSetup.Action.READ_LENGTH->if(!readLength())abort()
            SonyGattSetup.Action.SUBSCRIBE_DATA->subscribeData()
            SonyGattSetup.Action.READY->ready()
            SonyGattSetup.Action.FAILED->abort()
            SonyGattSetup.Action.WAIT_PROBE->Unit
        }
    }
    private fun abort() {if(!closed){close();failed()}}
    @Synchronized override fun close() {closed=true;generation++;timer?.let(handler::removeCallbacks);timer=null;setup.close()}
}
